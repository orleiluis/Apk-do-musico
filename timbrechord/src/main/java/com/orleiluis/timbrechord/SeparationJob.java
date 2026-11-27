package com.orleiluis.timbrechord;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.PowerManager;
import android.os.SystemClock;

import com.orleiluis.timbrechord.core.OrtStemModel;
import com.orleiluis.timbrechord.core.StemSeparator;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;
import java.nio.channels.FileChannel;

/**
 * Separa uma música em 6 faixas em segundo plano (uma por vez). Leva minutos: o serviço
 * SeparationService segura o app vivo com uma notificação enquanto isso, e a tela só lê o estado daqui.
 */
final class SeparationJob {
    static final SeparationJob shared = new SeparationJob();

    private SeparationJob() {}

    static final int IDLE = 0, RUNNING = 1, DONE = 2, FAILED = 3, CANCELLED = 4;

    private volatile int state = IDLE;
    private volatile String songId = "", title = "", error = "", stage = "";
    private volatile float progress;
    private volatile int etaSeconds = -1;
    private volatile boolean cancelRequested;
    private Thread thread;

    int state() { return state; }

    boolean isRunning() { return state == RUNNING; }

    String songId() { return songId; }

    String title() { return title; }

    String error() { return error; }

    String stage() { return stage; }

    /** 0..1 */
    float progress() { return progress; }

    /** Tempo que falta, em segundos (-1 enquanto não dá para estimar). */
    int etaSeconds() { return etaSeconds; }

    synchronized boolean start(Context context, final Uri uri, final String id, final String name) {
        if (state == RUNNING) return false;
        final Context app = context.getApplicationContext();
        songId = id;
        title = name;
        error = "";
        stage = "Começando…";
        progress = 0;
        etaSeconds = -1;
        cancelRequested = false;
        state = RUNNING;
        thread = new Thread(new Runnable() {
            @Override
            public void run() { work(app, uri, id, name); }
        }, "timbrechord-separacao");
        thread.start();
        try {
            app.startForegroundService(new Intent(app, SeparationService.class));
        } catch (Exception ignored) {
            // sem o serviço a separação continua enquanto o app estiver aberto
        }
        return true;
    }

    void cancel() { cancelRequested = true; }

    /** A tela já tratou o fim (trocou o player para as faixas): volta ao estado parado. */
    synchronized void acknowledge() {
        if (state != RUNNING) state = IDLE;
    }

    private void work(Context app, Uri uri, String id, String name) {
        PowerManager.WakeLock lock = null;
        File mix = new File(app.getCacheDir(), "separacao_mix.pcm");
        OrtStemModel model = null;
        OutputStream[] outs = null;
        try {
            PowerManager pm = (PowerManager) app.getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                // a tela pode apagar no meio: o processador continua trabalhando
                lock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "timbrechord:separacao");
                lock.acquire(3 * 60 * 60 * 1000L);
            }
            stage = "Preparando o modelo (só na primeira vez demora mais)";
            final ModelStore.Info info = ModelStore.prepare(app);
            check();

            stage = "Lendo a música";
            final long frames = AudioDecoder.decode(app, uri, mix, new AudioDecoder.Progress() {
                @Override
                public boolean update(float fraction) {
                    progress = 0.04f * fraction;
                    return !cancelRequested;
                }
            });
            check();

            stage = "Carregando o modelo";
            // 2 linhas: com mais, cada uma reserva memória própria (no teste, 4 linhas usaram mais memória e foram mais lentas)
            int threads = Math.max(1, Math.min(2, Runtime.getRuntime().availableProcessors()));
            // econômico: sem a reserva do ONNX Runtime, a memória volta ao sistema a cada trecho
            model = new OrtStemModel(info.dir, info.parts, info.sources.length, info.segment, info.bins, info.frames, threads, true);
            check();

            File part = StemStore.partialDir(app, id);
            ModelStore.deleteTree(part);
            if (!part.mkdirs()) throw new java.io.IOException("não consegui criar a pasta das faixas");
            final OutputStream[] streams = new OutputStream[info.sources.length];
            outs = streams;
            for (int i = 0; i < streams.length; i++)
                streams[i] = new BufferedOutputStream(new FileOutputStream(new File(part, i + ".pcm")), 1 << 18);

            final ShortBuffer pcm;
            try (RandomAccessFile raf = new RandomAccessFile(mix, "r"); FileChannel ch = raf.getChannel()) {
                pcm = ch.map(FileChannel.MapMode.READ_ONLY, 0, ch.size()).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer();
            }
            stage = "Separando as faixas";
            mark(app, id, name, progress);
            final long begin = SystemClock.elapsedRealtime();
            final byte[][] bytes = {new byte[0]};
            new StemSeparator(model, info.sources.length, info.segment, info.nfft, info.hop).separate(
                    new StemSeparator.Source() {
                        @Override
                        public void read(long start, int count, float[] left, float[] right) {
                            int base = (int) (start * 2);
                            for (int i = 0; i < count; i++) {
                                left[i] = pcm.get(base + 2 * i) / 32768f;
                                right[i] = pcm.get(base + 2 * i + 1) / 32768f;
                            }
                        }
                    },
                    frames,
                    new StemSeparator.Sink() {
                        @Override
                        public void write(float[][][] stems, int offset, int count) throws Exception {
                            if (bytes[0].length < count * 4) bytes[0] = new byte[count * 4];
                            byte[] b = bytes[0];
                            for (int s = 0; s < stems.length; s++) {
                                float[] l = stems[s][0], r = stems[s][1];
                                for (int i = 0; i < count; i++) {
                                    int a = AudioDecoder.pcm(l[offset + i]), c = AudioDecoder.pcm(r[offset + i]);
                                    b[4 * i] = (byte) a;
                                    b[4 * i + 1] = (byte) (a >> 8);
                                    b[4 * i + 2] = (byte) c;
                                    b[4 * i + 3] = (byte) (c >> 8);
                                }
                                streams[s].write(b, 0, count * 4);
                            }
                        }
                    },
                    new StemSeparator.Progress() {
                        @Override
                        public boolean update(int done, int total) {
                            progress = 0.04f + 0.96f * done / total;
                            mark(app, id, name, progress);
                            long spent = SystemClock.elapsedRealtime() - begin;
                            etaSeconds = (int) Math.round(spent / 1000.0 / done * (total - done));
                            return !cancelRequested;
                        }
                    });
            for (OutputStream s : streams) s.close();
            //noinspection ResultOfMethodCallIgnored
            marker(app).delete();
            outs = null;
            StemStore.commit(app, id, name, info.sources, frames);
            progress = 1;
            stage = "";
            state = DONE;
        } catch (StemSeparator.CancelledException e) {
            //noinspection ResultOfMethodCallIgnored
            marker(app).delete();
            ModelStore.deleteTree(StemStore.partialDir(app, id));
            stage = "";
            state = CANCELLED;
        } catch (Throwable e) {
            //noinspection ResultOfMethodCallIgnored
            marker(app).delete();
            ModelStore.deleteTree(StemStore.partialDir(app, id));
            String why = e instanceof OutOfMemoryError ? "a memória do celular não foi suficiente"
                    : e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            error = "Não consegui separar as faixas: " + why + ".";
            stage = "";
            state = FAILED;
        } finally {
            if (outs != null) for (OutputStream s : outs) try { if (s != null) s.close(); } catch (Exception ignored) { }
            if (model != null) model.close();
            //noinspection ResultOfMethodCallIgnored
            mix.delete();
            if (lock != null && lock.isHeld()) lock.release();
            etaSeconds = -1;
        }
    }

    // ---- marca em disco: se o Android fechar o app no meio, a próxima abertura sabe onde parou
    private static java.io.File marker(Context c) { return new java.io.File(c.getFilesDir(), "separacao_em_andamento.txt"); }

    private static void mark(Context c, String id, String name, float progress) {
        try (java.io.FileOutputStream out = new java.io.FileOutputStream(marker(c))) {
            out.write((Math.round(progress * 100) + "\n" + id + "\n" + name).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception ignored) { }
    }

    /** Mensagem sobre uma separação que o sistema interrompeu (app fechado), ou "" se não houve. Lê uma vez só. */
    static String interruptedMessage(Context c) {
        java.io.File f = marker(c);
        if (shared.isRunning() || !f.exists()) return "";
        String text = "";
        try { text = new String(java.nio.file.Files.readAllBytes(f.toPath()), java.nio.charset.StandardCharsets.UTF_8); }
        catch (Exception ignored) { }
        //noinspection ResultOfMethodCallIgnored
        f.delete();
        String[] parts = text.split("\n", 3);
        String where = parts.length > 0 && !parts[0].isEmpty() ? " em " + parts[0] + "%" : "";
        String title = parts.length > 2 ? " de \u201c" + parts[2] + "\u201d" : "";
        return "A separação" + title + " parou" + where + ": o Android fechou o app, provavelmente por falta de memória. "
                + "Feche outros apps e tente de novo.";
    }

    private void check() throws StemSeparator.CancelledException {
        if (cancelRequested) throw new StemSeparator.CancelledException();
    }
}
