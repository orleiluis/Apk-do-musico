package com.orleiluis.timbrechord;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.media.MediaCodec;
import android.media.PlaybackParams;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;

import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.ShortBuffer;
import java.nio.channels.FileChannel;

/**
 * Toca um arquivo de música do aparelho (MP3, M4A, FLAC, OGG, WAV...) e entrega o mesmo áudio ao
 * analisador, sem precisar de captura nem de microfone. O arquivo é decodificado aos poucos, na
 * memória; nada é copiado nem gravado.
 * Depois de separada, a música toca a partir das faixas (StemStore), misturadas pelas barras de volume.
 */
final class FilePlayer {
    static final FilePlayer shared = new FilePlayer();

    private FilePlayer() {}

    private Thread thread;
    private volatile boolean stopRequested;
    private volatile boolean wantPlaying;
    private volatile long pendingSeekUs = -1;
    private volatile float speed = 1f;

    private volatile boolean loaded, ended, failed;
    private volatile String title = "";
    private volatile String error = "";
    private volatile long durationUs, positionUs;
    private Context app;
    private Uri source;
    /** Faixas separadas tocando no lugar do arquivo (null = o arquivo original). */
    private volatile StemStore.StemSet stems;
    private long startAtUs;

    /** Há um arquivo escolhido (continua verdadeiro se ele falhar: a barra fica na tela para tentar de novo). */
    boolean isLoaded() { return loaded; }

    boolean isPlaying() { return loaded && wantPlaying && !ended && !failed; }

    boolean isEnded() { return loaded && ended; }

    String title() { return title; }

    /** Tocando as faixas separadas (as barras de volume valem). */
    boolean isStems() { return loaded && stems != null; }

    StemStore.StemSet stems() { return stems; }

    /** Mensagem do problema que parou o arquivo ("" se está tudo certo). Some ao tocar de novo. */
    String error() { return error; }

    int durationMs() { return (int) (durationUs / 1000); }

    int positionMs() { return (int) (positionUs / 1000); }

    /** Abre o arquivo e começa a tocar. Fecha o que estava aberto antes. */
    synchronized void open(Context context, Uri uri, String displayName) {
        close();
        app = context.getApplicationContext();
        source = uri;
        title = displayName == null ? "" : displayName;
        durationUs = 0;
        loaded = true;
        startAtUs = 0;
        start();
    }

    /** Toca as faixas separadas, a partir de startMs (para trocar sem voltar ao começo). */
    synchronized void openStems(Context context, StemStore.StemSet set, String displayName, int startMs, boolean playing) {
        close();
        app = context.getApplicationContext();
        stems = set;
        title = displayName == null ? "" : displayName;
        durationUs = set.frames * 1_000_000L / StemStore.SAMPLE_RATE;
        loaded = true;
        startAtUs = Math.max(0, startMs) * 1000L;
        start();
        wantPlaying = playing;
    }

    /** Começa (ou recomeça, depois de um erro) a tocar o arquivo escolhido, do início. */
    private void start() {
        final Context context = app;
        final Uri uri = source;
        final StemStore.StemSet set = stems;
        error = "";
        failed = false;
        positionUs = startAtUs;
        pendingSeekUs = startAtUs > 0 ? startAtUs : -1;
        startAtUs = 0;
        ended = false;
        stopRequested = false;
        wantPlaying = true;
        thread = new Thread(new Runnable() {
            @Override
            public void run() {
                if (set != null) stemLoop(set);
                else decodeLoop(context, uri);
            }
        }, "timbrechord-arquivo");
        thread.start();
    }

    synchronized void play() {
        if (!loaded) return;
        if (failed) {
            // a tentativa anterior parou com erro: abre o arquivo de novo
            stopThread();
            start();
            return;
        }
        if (ended) pendingSeekUs = 0;
        wantPlaying = true;
    }

    void pause() { wantPlaying = false; }

    /** Velocidade de reprodução (1 = normal). O tom não muda; a análise continua no andamento original. */
    void setSpeed(float value) { speed = Math.max(0.25f, Math.min(2f, value)); }

    void seekTo(int ms) {
        if (loaded) pendingSeekUs = Math.max(0, ms) * 1000L;
    }

    synchronized void close() {
        stopThread();
        loaded = false;
        ended = false;
        failed = false;
        error = "";
        title = "";
        app = null;
        source = null;
        stems = null;
        durationUs = positionUs = 0;
    }

    private void stopThread() {
        stopRequested = true;
        wantPlaying = false;
        Thread t = thread;
        thread = null;
        if (t != null && t != Thread.currentThread()) {
            t.interrupt();
            try { t.join(1500); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
        }
    }

    private void decodeLoop(Context context, Uri uri) {
        MediaExtractor extractor = new MediaExtractor();
        MediaCodec codec = null;
        AudioTrack track = null;
        try {
            extractor.setDataSource(context, uri, null);
            MediaFormat format = null;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat f = extractor.getTrackFormat(i);
                String mime = f.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) {
                    extractor.selectTrack(i);
                    format = f;
                    break;
                }
            }
            if (format == null) throw new IllegalStateException("o arquivo não tem áudio");
            if (format.containsKey(MediaFormat.KEY_DURATION)) durationUs = format.getLong(MediaFormat.KEY_DURATION);
            codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME));
            // pede PCM de 16 bits; alguns aparelhos entregam float mesmo assim (tratado abaixo)
            format.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT);
            codec.configure(format, null, null, 0);
            codec.start();

            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean inputDone = false, outputDone = false, trackPlaying = false;
            float appliedSpeed = -1f;
            int rate = format.containsKey(MediaFormat.KEY_SAMPLE_RATE) ? format.getInteger(MediaFormat.KEY_SAMPLE_RATE) : 44100;
            int channels = format.containsKey(MediaFormat.KEY_CHANNEL_COUNT) ? format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) : 2;
            boolean floatPcm = false;
            short[] pcm = new short[0], mono = new short[0];
            Resampler resampler = new Resampler(AppState.analyzer);

            while (!stopRequested) {
                long seek = pendingSeekUs;
                if (seek >= 0) {
                    pendingSeekUs = -1;
                    extractor.seekTo(seek, MediaExtractor.SEEK_TO_CLOSEST_SYNC);
                    codec.flush();
                    if (track != null) {
                        track.pause();
                        track.flush();
                        trackPlaying = false;
                    }
                    inputDone = outputDone = false;
                    ended = false;
                    positionUs = seek;
                    resampler.reset();
                }
                if (!wantPlaying || outputDone) {
                    if (track != null && trackPlaying) {
                        track.pause();
                        trackPlaying = false;
                    }
                    try { Thread.sleep(40); } catch (InterruptedException e) { break; }
                    continue;
                }

                if (!inputDone) {
                    int in = codec.dequeueInputBuffer(5000);
                    if (in >= 0) {
                        ByteBuffer buf = codec.getInputBuffer(in);
                        int size = buf == null ? -1 : extractor.readSampleData(buf, 0);
                        if (size < 0) {
                            codec.queueInputBuffer(in, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            codec.queueInputBuffer(in, 0, size, extractor.getSampleTime(), 0);
                            extractor.advance();
                        }
                    }
                }

                int out = codec.dequeueOutputBuffer(info, 5000);
                if (out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat of = codec.getOutputFormat();
                    rate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                    channels = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                    floatPcm = of.containsKey(MediaFormat.KEY_PCM_ENCODING)
                            && of.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT;
                    if (track != null) track.release();
                    track = newTrack(rate, channels);
                    trackPlaying = false;
                    resampler.reset();
                } else if (out >= 0) {
                    ByteBuffer buf = codec.getOutputBuffer(out);
                    if (buf != null && info.size > 0) {
                        buf.position(info.offset);
                        buf.limit(info.offset + info.size);
                        int n;
                        if (floatPcm) {
                            FloatBuffer fb = buf.order(ByteOrder.nativeOrder()).asFloatBuffer();
                            n = fb.remaining();
                            if (pcm.length < n) pcm = new short[n];
                            for (int i = 0; i < n; i++) {
                                float f = Math.max(-1f, Math.min(1f, fb.get(i)));
                                pcm[i] = (short) Math.round(f * 32767f);
                            }
                        } else {
                            ShortBuffer sb = buf.order(ByteOrder.nativeOrder()).asShortBuffer();
                            n = sb.remaining();
                            if (pcm.length < n) pcm = new short[n];
                            sb.get(pcm, 0, n);
                        }

                        // análise: mono em 44,1 kHz
                        int frames = n / Math.max(1, channels);
                        if (mono.length < frames) mono = new short[frames];
                        for (int i = 0; i < frames; i++) {
                            int sum = 0;
                            for (int c = 0; c < channels; c++) sum += pcm[i * channels + c];
                            mono[i] = (short) (sum / channels);
                        }
                        // um tropeço da análise não pode parar a música
                        try { resampler.feed(mono, frames, rate); } catch (RuntimeException ignored) { }

                        if (track == null) track = newTrack(rate, channels);
                        if (!trackPlaying) {
                            track.play();
                            trackPlaying = true;
                            appliedSpeed = -1f; // reaplica sempre que a faixa volta a tocar
                        }
                        float wanted = speed;
                        if (wanted != appliedSpeed) {
                            // um aparelho que recuse a mudança continua tocando na velocidade normal
                            try { track.setPlaybackParams(new PlaybackParams().setSpeed(wanted).setPitch(1f)); }
                            catch (RuntimeException ignored) { }
                            appliedSpeed = wanted;
                        }
                        // escrita bloqueante: é ela que segura a decodificação no ritmo da música
                        int written = 0;
                        while (written < n && !stopRequested && pendingSeekUs < 0 && wantPlaying) {
                            int w = track.write(pcm, written, n - written);
                            if (w <= 0) break;
                            written += w;
                        }
                        positionUs = info.presentationTimeUs;
                    }
                    codec.releaseOutputBuffer(out, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        outputDone = true;
                        ended = true;
                        wantPlaying = false;
                        if (durationUs > 0) positionUs = durationUs;
                    }
                }
            }
        } catch (Exception e) {
            if (!stopRequested) {
                // o arquivo continua escolhido: a barra mostra o motivo e o botão tenta de novo
                error = "Não consegui tocar o arquivo: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()) + ".";
                failed = true;
                wantPlaying = false;
            }
        } finally {
            if (track != null) {
                try { track.pause(); track.flush(); } catch (Exception ignored) { }
                track.release();
            }
            if (codec != null) {
                try { codec.stop(); } catch (Exception ignored) { }
                codec.release();
            }
            extractor.release();
        }
    }

    /** Mistura as faixas separadas com os volumes das barras. O analisador recebe a soma sem volumes (a música inteira). */
    private void stemLoop(StemStore.StemSet set) {
        final int block = 1024, rate = StemStore.SAMPLE_RATE;
        int count = set.files.length;
        AudioTrack track = null;
        try {
            ShortBuffer[] pcm = new ShortBuffer[count];
            for (int i = 0; i < count; i++) {
                try (RandomAccessFile raf = new RandomAccessFile(set.files[i], "r"); FileChannel ch = raf.getChannel()) {
                    pcm[i] = ch.map(FileChannel.MapMode.READ_ONLY, 0, ch.size()).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer();
                }
            }
            long frames = set.frames, pos = 0;
            float[] gains = new float[count], previous = new float[count];
            StemMixer.shared.effective(set.sources, previous);
            short[] out = new short[block * 2], mono = new short[block];
            Resampler resampler = new Resampler(AppState.analyzer);
            boolean trackPlaying = false;
            float appliedSpeed = -1f;
            track = newTrack(rate, 2);
            while (!stopRequested) {
                long seek = pendingSeekUs;
                if (seek >= 0) {
                    pendingSeekUs = -1;
                    pos = Math.min(frames, seek * rate / 1_000_000L);
                    track.pause();
                    track.flush();
                    trackPlaying = false;
                    ended = pos >= frames;
                    positionUs = pos * 1_000_000L / rate;
                    resampler.reset();
                }
                if (!wantPlaying || ended) {
                    if (trackPlaying) {
                        track.pause();
                        trackPlaying = false;
                    }
                    try { Thread.sleep(40); } catch (InterruptedException e) { break; }
                    continue;
                }
                int n = (int) Math.min(block, frames - pos);
                if (n <= 0) {
                    ended = true;
                    wantPlaying = false;
                    positionUs = durationUs;
                    continue;
                }
                StemMixer.shared.effective(set.sources, gains);
                int base = (int) (pos * 2);
                for (int i = 0; i < n; i++) {
                    float k = (i + 1) / (float) n; // rampa curta: mexer na barra não estala
                    float l = 0, r = 0, all = 0;
                    for (int s = 0; s < count; s++) {
                        float g = previous[s] + (gains[s] - previous[s]) * k;
                        short a = pcm[s].get(base + 2 * i), b = pcm[s].get(base + 2 * i + 1);
                        l += g * a;
                        r += g * b;
                        all += a + b;
                    }
                    out[2 * i] = clip(l);
                    out[2 * i + 1] = clip(r);
                    mono[i] = clip(all * 0.5f);
                }
                System.arraycopy(gains, 0, previous, 0, count);
                try { resampler.feed(mono, n, rate); } catch (RuntimeException ignored) { }
                if (!trackPlaying) {
                    track.play();
                    trackPlaying = true;
                    appliedSpeed = -1f;
                }
                float wanted = speed;
                if (wanted != appliedSpeed) {
                    try { track.setPlaybackParams(new PlaybackParams().setSpeed(wanted).setPitch(1f)); }
                    catch (RuntimeException ignored) { }
                    appliedSpeed = wanted;
                }
                int written = 0;
                while (written < 2 * n && !stopRequested && pendingSeekUs < 0 && wantPlaying) {
                    int w = track.write(out, written, 2 * n - written);
                    if (w <= 0) break;
                    written += w;
                }
                pos += n;
                positionUs = pos * 1_000_000L / rate;
            }
        } catch (Exception e) {
            if (!stopRequested) {
                error = "Não consegui tocar as faixas: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()) + ".";
                failed = true;
                wantPlaying = false;
            }
        } finally {
            if (track != null) {
                try { track.pause(); track.flush(); } catch (Exception ignored) { }
                track.release();
            }
        }
    }

    private static short clip(float v) {
        int s = Math.round(v);
        return (short) (s > 32767 ? 32767 : Math.max(-32768, s));
    }

    private static AudioTrack newTrack(int rate, int channels) {
        int mask = channels == 1 ? AudioFormat.CHANNEL_OUT_MONO : AudioFormat.CHANNEL_OUT_STEREO;
        if (channels > 2) throw new IllegalStateException("arquivos com mais de 2 canais não são suportados");
        int min = AudioTrack.getMinBufferSize(rate, mask, AudioFormat.ENCODING_PCM_16BIT);
        return new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build())
                .setAudioFormat(new AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(rate)
                        .setChannelMask(mask)
                        .build())
                .setBufferSizeInBytes(Math.max(min > 0 ? min * 2 : 0, 16384))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setSessionId(AudioManager.AUDIO_SESSION_ID_GENERATE)
                .build();
    }
}
