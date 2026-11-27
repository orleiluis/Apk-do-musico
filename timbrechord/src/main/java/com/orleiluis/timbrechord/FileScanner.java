package com.orleiluis.timbrechord;

import android.content.Context;
import android.media.AudioFormat;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;

import com.orleiluis.timbrechord.core.ChartRecorder;
import com.orleiluis.timbrechord.core.ChordChart;
import com.orleiluis.timbrechord.core.LiveAnalyzer;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.ShortBuffer;

/**
 * Analisa um arquivo de música inteiro em segundo plano, bem mais rápido do que ele toca, e preenche
 * o mapa de acordes da música. É isso que deixa o próximo acorde e a grade aparecerem adiantados
 * desde a primeira vez que o arquivo é aberto. O áudio é só lido e analisado na memória.
 */
final class FileScanner {
    static final FileScanner shared = new FileScanner();

    private FileScanner() {}

    private Thread thread;
    /** Muda a cada análise nova ou cancelada: a análise antiga percebe e para sozinha. */
    private volatile int generation;
    private volatile boolean running, failed;
    private volatile float progress;

    boolean isRunning() { return running; }

    /** A análise antecipada não deu certo: a tela volta a aprender os acordes enquanto a música toca. */
    boolean failed() { return failed; }

    /** 0..1 */
    float progress() { return progress; }

    synchronized void start(Context context, final Uri uri, final ChordChart chart, final boolean simpleChords) {
        cancel();
        final Context app = context.getApplicationContext();
        final int mine = generation;
        failed = false;
        progress = 0;
        running = true;
        thread = new Thread(new Runnable() {
            @Override
            public void run() { scan(app, uri, chart, simpleChords, mine); }
        }, "timbrechord-analise");
        // a música que está tocando tem prioridade sobre a análise
        thread.setPriority(Thread.MIN_PRIORITY + 1);
        thread.start();
    }

    synchronized void cancel() {
        generation++;
        Thread t = thread;
        thread = null;
        if (t != null && t != Thread.currentThread()) {
            t.interrupt();
            try { t.join(1500); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
        }
        running = false;
        failed = false;
        progress = 0;
    }

    private void scan(Context context, Uri uri, ChordChart chart, boolean simpleChords, int mine) {
        MediaExtractor extractor = new MediaExtractor();
        MediaCodec codec = null;
        boolean done = false;
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
            if (format == null) throw new IllegalStateException("sem áudio");
            long durationUs = format.containsKey(MediaFormat.KEY_DURATION) ? format.getLong(MediaFormat.KEY_DURATION) : 0;
            codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME));
            format.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT);
            codec.configure(format, null, null, 0);
            codec.start();

            LiveAnalyzer analyzer = new LiveAnalyzer();
            analyzer.chords.setSimpleMode(simpleChords);
            ChartRecorder recorder = new ChartRecorder();
            Resampler resampler = new Resampler(analyzer);
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean inputDone = false;
            int rate = format.containsKey(MediaFormat.KEY_SAMPLE_RATE) ? format.getInteger(MediaFormat.KEY_SAMPLE_RATE) : 44100;
            int channels = format.containsKey(MediaFormat.KEY_CHANNEL_COUNT) ? format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) : 2;
            boolean floatPcm = false;
            short[] pcm = new short[0], mono = new short[0];
            double lastTempoAt = 0;

            while (generation == mine && !done) {
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
                        int ch = Math.max(1, channels);
                        int frames = n / ch;
                        if (mono.length < frames) mono = new short[frames];
                        for (int i = 0; i < frames; i++) {
                            int sum = 0;
                            for (int c = 0; c < ch; c++) sum += pcm[i * ch + c];
                            mono[i] = (short) (sum / ch);
                        }
                        resampler.feed(mono, frames, rate);
                        // a posição da música é o tanto de áudio já analisado
                        double media = resampler.delivered() / (double) LiveAnalyzer.SAMPLE_RATE;
                        recorder.update(chart, analyzer.chords, media, 1);
                        if (media - lastTempoAt > 8) {
                            lastTempoAt = media;
                            chart.setBpm(analyzer.tempo.songBpm());
                        }
                        if (durationUs > 0) progress = (float) Math.min(1.0, info.presentationTimeUs / (double) durationUs);
                    }
                    codec.releaseOutputBuffer(out, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) done = true;
                }
            }
            if (done && generation == mine) {
                double total = resampler.delivered() / (double) LiveAnalyzer.SAMPLE_RATE;
                // o gravador escreve com atraso: o último acorde vai até o fim da música
                if (total > chart.end()) chart.mark(chart.end(), total, analyzer.chords.current());
                // andamento da música inteira (não só do fim): é o que a tela mostra daqui em diante
                chart.setBpm(analyzer.tempo.songBpm());
                chart.setKey(analyzer.chords.key());
                chart.setDuration(total);
                chart.setComplete(true);
                progress = 1;
            }
        } catch (Exception e) {
            if (generation == mine) failed = true;
        } finally {
            if (codec != null) {
                try { codec.stop(); } catch (Exception ignored) { }
                codec.release();
            }
            extractor.release();
            if (generation == mine) running = false;
        }
    }
}
