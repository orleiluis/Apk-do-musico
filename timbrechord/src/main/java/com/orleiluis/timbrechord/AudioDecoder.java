package com.orleiluis.timbrechord;

import android.content.Context;
import android.media.AudioFormat;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;

import com.orleiluis.timbrechord.core.SincResampler;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.ShortBuffer;

/**
 * Decodifica o arquivo de música inteiro para PCM de 16 bits estéreo em 44,1 kHz, num arquivo temporário
 * (a separação lê trechos dele). Mono vira estéreo; mais de 2 canais ficam só com os dois primeiros.
 */
final class AudioDecoder {
    private AudioDecoder() {}

    interface Progress {
        /** 0..1; devolver false cancela. */
        boolean update(float fraction);
    }

    /** Devolve quantas amostras (por canal) foram gravadas. */
    static long decode(Context context, Uri uri, File out, Progress progress) throws Exception {
        MediaExtractor extractor = new MediaExtractor();
        MediaCodec codec = null;
        try (OutputStream file = new BufferedOutputStream(new FileOutputStream(out), 1 << 18)) {
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
            if (format == null) throw new IOException("o arquivo não tem áudio");
            long durationUs = format.containsKey(MediaFormat.KEY_DURATION) ? format.getLong(MediaFormat.KEY_DURATION) : 0;
            codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME));
            format.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT);
            codec.configure(format, null, null, 0);
            codec.start();

            int rate = format.containsKey(MediaFormat.KEY_SAMPLE_RATE) ? format.getInteger(MediaFormat.KEY_SAMPLE_RATE) : 44100;
            int channels = format.containsKey(MediaFormat.KEY_CHANNEL_COUNT) ? format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) : 2;
            boolean floatPcm = false;
            SincResampler rl = null, rr = null;
            float[] left = new float[0], right = new float[0];
            byte[] bytes = new byte[0];
            long written = 0;
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean inputDone = false, done = false;
            while (!done) {
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
                int o = codec.dequeueOutputBuffer(info, 5000);
                if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat of = codec.getOutputFormat();
                    rate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                    channels = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                    floatPcm = of.containsKey(MediaFormat.KEY_PCM_ENCODING)
                            && of.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT;
                    rl = rr = null;
                } else if (o >= 0) {
                    ByteBuffer buf = codec.getOutputBuffer(o);
                    if (buf != null && info.size > 0) {
                        buf.position(info.offset);
                        buf.limit(info.offset + info.size);
                        int ch = Math.max(1, channels);
                        int frames;
                        if (floatPcm) {
                            FloatBuffer fb = buf.order(ByteOrder.nativeOrder()).asFloatBuffer();
                            frames = fb.remaining() / ch;
                            if (left.length < frames) { left = new float[frames]; right = new float[frames]; }
                            for (int i = 0; i < frames; i++) {
                                left[i] = fb.get(i * ch);
                                right[i] = ch > 1 ? fb.get(i * ch + 1) : left[i];
                            }
                        } else {
                            ShortBuffer sb = buf.order(ByteOrder.nativeOrder()).asShortBuffer();
                            frames = sb.remaining() / ch;
                            if (left.length < frames) { left = new float[frames]; right = new float[frames]; }
                            for (int i = 0; i < frames; i++) {
                                left[i] = sb.get(i * ch) / 32768f;
                                right[i] = ch > 1 ? sb.get(i * ch + 1) / 32768f : left[i];
                            }
                        }
                        if (rate != StemStore.SAMPLE_RATE) {
                            if (rl == null) {
                                rl = new SincResampler(rate, StemStore.SAMPLE_RATE);
                                rr = new SincResampler(rate, StemStore.SAMPLE_RATE);
                            }
                            int a = rl.feed(left, frames), b = rr.feed(right, frames);
                            bytes = writeFrames(file, rl.output(), rr.output(), Math.min(a, b), bytes);
                            written += Math.min(a, b);
                        } else {
                            bytes = writeFrames(file, left, right, frames, bytes);
                            written += frames;
                        }
                        if (durationUs > 0 && progress != null
                                && !progress.update((float) Math.min(1.0, info.presentationTimeUs / (double) durationUs)))
                            throw new com.orleiluis.timbrechord.core.StemSeparator.CancelledException();
                    }
                    codec.releaseOutputBuffer(o, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) done = true;
                }
            }
            if (rl != null) {
                int a = rl.finish(), b = rr.finish();
                bytes = writeFrames(file, rl.output(), rr.output(), Math.min(a, b), bytes);
                written += Math.min(a, b);
            }
            if (written == 0) throw new IOException("o arquivo não tem som");
            return written;
        } finally {
            if (codec != null) {
                try { codec.stop(); } catch (Exception ignored) { }
                codec.release();
            }
            extractor.release();
        }
    }

    private static byte[] writeFrames(OutputStream out, float[] l, float[] r, int n, byte[] bytes) throws IOException {
        if (bytes.length < n * 4) bytes = new byte[n * 4];
        for (int i = 0; i < n; i++) {
            int a = pcm(l[i]), b = pcm(r[i]);
            bytes[4 * i] = (byte) a;
            bytes[4 * i + 1] = (byte) (a >> 8);
            bytes[4 * i + 2] = (byte) b;
            bytes[4 * i + 3] = (byte) (b >> 8);
        }
        out.write(bytes, 0, n * 4);
        return bytes;
    }

    static int pcm(float v) {
        int s = Math.round(v * 32767f);
        return s > 32767 ? 32767 : Math.max(-32768, s);
    }
}
