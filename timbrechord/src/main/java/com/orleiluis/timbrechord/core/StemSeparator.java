package com.orleiluis.timbrechord.core;

import java.nio.FloatBuffer;

/**
 * Separa uma música em faixas com a rede do Demucs (htdemucs_6s), fazendo em Java as mesmas contas que
 * o Demucs faz em Python em volta da rede:
 *  - normaliza a música inteira pela média e desvio do mono (como demucs.separate);
 *  - corta em trechos do tamanho do treino com 25% de sobreposição e junta com pesos triangulares
 *    (como demucs.apply.apply_model, shifts=0); o último trecho é completado com o áudio de antes;
 *  - em cada trecho: STFT (HTDemucs._spec), rede, iSTFT do ramo de frequência (HTDemucs._ispec)
 *    somada ao ramo do tempo.
 * O resultado sai em blocos já prontos, na ordem, para não guardar a música separada inteira na memória.
 */
public final class StemSeparator {
    public static final int SAMPLE_RATE = 44100;
    public static final float OVERLAP = 0.25f;

    /** Áudio estéreo em 44,1 kHz, de -1 a 1. Os pedidos ficam sempre dentro de [0, total). */
    public interface Source {
        void read(long start, int count, float[] left, float[] right) throws Exception;
    }

    /** Recebe stems[fonte][canal][offset .. offset+count), já na escala da música original. */
    public interface Sink {
        void write(float[][][] stems, int offset, int count) throws Exception;
    }

    /** Chamado depois de cada trecho; devolver false cancela. */
    public interface Progress {
        boolean update(int done, int total);
    }

    public static final class CancelledException extends Exception {
        public CancelledException() { super("separação cancelada"); }
    }

    private final StemModel model;
    private final int seg, nfft, hop, bins, frames, sources;
    private final float[] window, envelope, weight;
    private final Fft fft;
    private final float[] re, im;

    public StemSeparator(StemModel model, int sources, int segment, int nfft, int hop) {
        this.model = model;
        this.sources = sources;
        this.seg = segment;
        this.nfft = nfft;
        this.hop = hop;
        if (hop * 4 != nfft) throw new IllegalArgumentException("o Demucs usa hop = nfft/4");
        this.bins = nfft / 2;
        this.frames = (segment + hop - 1) / hop;
        fft = new Fft(nfft);
        re = new float[nfft];
        im = new float[nfft];
        window = new float[nfft];
        for (int i = 0; i < nfft; i++) window[i] = (float) (0.5 - 0.5 * Math.cos(2 * Math.PI * i / nfft)); // periódica, como torch.hann_window
        // soma das janelas ao quadrado que cobrem cada amostra (iSTFT); dentro do trecho sempre são 4 quadros
        envelope = new float[hop];
        int first = (hop / 2 * 3) + nfft / 2; // início do trecho dentro do sinal da iSTFT (pad do Demucs + center)
        for (int r = 0; r < hop; r++) {
            double sum = 0;
            int phase = (r + first) % hop;
            for (int q = 0; q < nfft / hop; q++) sum += (double) window[phase + q * hop] * window[phase + q * hop];
            envelope[r] = (float) sum;
        }
        // pesos triangulares do apply_model
        weight = new float[segment];
        int half = segment / 2;
        float max = 0;
        for (int i = 0; i < segment; i++) {
            float v = i < half ? i + 1 : segment - i;
            weight[i] = v;
            max = Math.max(max, v);
        }
        for (int i = 0; i < segment; i++) weight[i] /= max;
    }

    public int frames() { return frames; }

    public int bins() { return bins; }

    public static int chunkCount(long total, int segment) {
        int stride = (int) ((1 - OVERLAP) * segment);
        return (int) ((total + stride - 1) / stride);
    }

    public void separate(Source src, long total, Sink sink, Progress progress) throws Exception {
        if (total <= 0) return;
        // ---- média e desvio do mono da música inteira
        double sum = 0, sumSq = 0;
        int block = 1 << 16;
        float[] l = new float[Math.max(block, seg)], r = new float[Math.max(block, seg)];
        for (long pos = 0; pos < total; pos += block) {
            int n = (int) Math.min(block, total - pos);
            src.read(pos, n, l, r);
            for (int i = 0; i < n; i++) {
                double m = (l[i] + r[i]) * 0.5;
                sum += m;
                sumSq += m * m;
            }
        }
        double mean = sum / total;
        double var = total > 1 ? (sumSq - sum * mean) / (total - 1) : 0;
        double std = Math.sqrt(Math.max(var, 0));
        if (!(std > 1e-8)) std = 1;
        float fMean = (float) mean, fStd = (float) std, inv = (float) (1 / std);

        int stride = (int) ((1 - OVERLAP) * seg);
        int chunks = chunkCount(total, seg);
        float[][][] acc = new float[sources][2][seg];
        float[][][] chunk = new float[sources][2][seg];
        float[][][] out = new float[sources][2][seg];
        float[] accW = new float[seg];
        FloatBuffer mixIn = model.mixInput();
        long base = 0;
        int index = 0;
        for (long offset = 0; offset < total; offset += stride, index++) {
            int length = (int) Math.min(seg, total - offset);
            int delta = seg - length;
            long start = offset - delta / 2;
            long from = Math.max(0, start), to = Math.min(total, start + seg);
            // ---- trecho de entrada: normalizado, zeros fora da música
            for (int i = 0; i < seg; i++) {
                mixIn.put(i, 0f);
                mixIn.put(seg + i, 0f);
            }
            int got = (int) (to - from);
            src.read(from, got, l, r);
            int at = (int) (from - start);
            for (int i = 0; i < got; i++) {
                mixIn.put(at + i, (l[i] - fMean) * inv);
                mixIn.put(seg + at + i, (r[i] - fMean) * inv);
            }
            stft(mixIn, model.specInput());
            model.run();
            assemble(model.freqOutput(), model.timeOutput(), chunk);

            // ---- soma com peso; base == offset
            int skip = delta / 2;
            for (int i = 0; i < length; i++) accW[i] += weight[i];
            for (int s = 0; s < sources; s++)
                for (int c = 0; c < 2; c++) {
                    float[] a = acc[s][c], y = chunk[s][c];
                    for (int i = 0; i < length; i++) a[i] += weight[i] * y[skip + i];
                }
            // ---- o que vem antes do próximo trecho está pronto
            long end = Math.min(total, offset + stride);
            if (offset + stride >= total) end = total;
            int ready = (int) (end - base);
            for (int s = 0; s < sources; s++)
                for (int c = 0; c < 2; c++) {
                    float[] a = acc[s][c], o = out[s][c];
                    for (int i = 0; i < ready; i++) o[i] = a[i] / accW[i] * fStd + fMean;
                    System.arraycopy(a, ready, a, 0, seg - ready);
                    java.util.Arrays.fill(a, seg - ready, seg, 0f);
                }
            System.arraycopy(accW, ready, accW, 0, seg - ready);
            java.util.Arrays.fill(accW, seg - ready, seg, 0f);
            sink.write(out, 0, ready);
            base = end;
            if (progress != null && !progress.update(index + 1, chunks)) throw new CancelledException();
        }
    }

    private static int reflect(int i, int n) {
        if (i < 0) return -i;
        if (i >= n) return 2 * (n - 1) - i;
        return i;
    }

    /** HTDemucs._spec + _magnitude (cac), os dois canais numa FFT complexa só. */
    void stft(FloatBuffer mix, FloatBuffer spec) {
        int pad = hop / 2 * 3;
        float scale = (float) (1 / Math.sqrt(nfft)); // normalized=True
        int plane = bins * frames;
        for (int j = 0; j < frames; j++) {
            int begin = j * hop - pad;
            for (int n = 0; n < nfft; n++) {
                int k = reflect(begin + n, seg);
                re[n] = mix.get(k) * window[n];
                im[n] = mix.get(seg + k) * window[n];
            }
            fft.transform(re, im);
            for (int f = 0; f < bins; f++) {
                int g = (nfft - f) & (nfft - 1);
                float zr = re[f], zi = im[f], wr = re[g], wi = im[g];
                int idx = f * frames + j;
                spec.put(idx, (zr + wr) * 0.5f * scale);              // L.re
                spec.put(plane + idx, (zi - wi) * 0.5f * scale);      // L.im
                spec.put(2 * plane + idx, (zi + wi) * 0.5f * scale);  // R.re
                spec.put(3 * plane + idx, (wr - zr) * 0.5f * scale);  // R.im
            }
        }
    }

    /** Ramo do tempo + iSTFT do ramo de frequência (HTDemucs._mask + _ispec), em y[fonte][canal][amostra]. */
    void assemble(FloatBuffer freq, FloatBuffer time, float[][][] y) {
        int plane = bins * frames;
        int pad = hop / 2 * 3;
        float scale = (float) (Math.sqrt(nfft) / nfft); // normalized=True e a divisão por N da transformada inversa
        for (int s = 0; s < sources; s++) {
            float[] y0 = y[s][0], y1 = y[s][1];
            int t0 = (s * 2) * seg, t1 = (s * 2 + 1) * seg;
            for (int k = 0; k < seg; k++) {
                y0[k] = 0f;
                y1[k] = 0f;
            }
            int b = s * 4 * plane;
            for (int j = 0; j < frames; j++) {
                // Z = L + i·R, com os espectros completados pela simetria (sinais reais)
                java.util.Arrays.fill(re, 0f);
                java.util.Arrays.fill(im, 0f);
                for (int f = 0; f < bins; f++) {
                    int idx = f * frames + j;
                    float ar = freq.get(b + idx), ai = freq.get(b + plane + idx);
                    float br = freq.get(b + 2 * plane + idx), bi = freq.get(b + 3 * plane + idx);
                    if (f == 0) {
                        ai = 0f; // a transformada real ignora a parte imaginária do DC
                        bi = 0f;
                        re[0] = ar;
                        im[0] = br;
                        continue;
                    }
                    // inversa pela conjugada: guarda conj(Z)
                    re[f] = ar - bi;
                    im[f] = -(ai + br);
                    re[nfft - f] = ar + bi;
                    im[nfft - f] = -(-ai + br);
                }
                im[0] = -im[0];
                fft.transform(re, im);
                int begin = j * hop - pad;
                for (int n = 0; n < nfft; n++) {
                    int k = begin + n;
                    if (k < 0 || k >= seg) continue;
                    float w = window[n] * scale / envelope[k % hop];
                    y0[k] += re[n] * w;
                    y1[k] -= im[n] * w;
                }
            }
            for (int k = 0; k < seg; k++) {
                y0[k] += time.get(t0 + k);
                y1[k] += time.get(t1 + k);
            }
        }
    }
}
