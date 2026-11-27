package com.orleiluis.timbrechord.core;

import java.util.Arrays;

/**
 * Estimativa de andamento (BPM) pela autocorrelação dos ataques do áudio. Entrada: PCM mono 44,1 kHz.
 *
 * O período do tempo é o que melhor explica a autocorrelação nele e nos múltiplos dele (2, 3, 4 tempos):
 * assim um tempo isolado mais forte não puxa o valor, e o resultado não pula para o dobro ou a metade.
 * Ao vivo vale a janela dos últimos 30 s; songBpm() usa a música inteira ouvida até agora.
 */
public final class TempoEstimator {
    private static final int BLOCK = 441;          // 100 valores por segundo
    private static final int ENV_LEN = 3000;       // 30 s (ao vivo)
    private static final int MIN_LAG = 30;         // 200 BPM
    private static final int MAX_LAG = 110;        // ~55 BPM

    private final float[] onset = new float[ENV_LEN];
    private int pos, count, inBlock, sinceEstimate;
    private final float[] recent = new float[15];
    private int recentCount, recentPos;
    private volatile float bpm;
    /** Ataques desde o começo (para o andamento da música inteira). */
    private float[] history = new float[6000];
    private int historyCount;

    /** 0 enquanto não há estimativa. */
    public float bpm() { return bpm; }

    public synchronized void reset() {
        Arrays.fill(onset, 0);
        pos = count = inBlock = sinceEstimate = recentCount = recentPos = 0;
        Arrays.fill(ring, 0);
        Arrays.fill(prevBand, 0);
        Arrays.fill(bandMean, 0);
        havePrev = false;
        ringPos = 0;
        bpm = 0;
        historyCount = 0;
    }

    // ---- ataques por faixa de frequência: bumbo, caixa, palhetada e chimbal pesam igual. Pela energia
    //      total realçando agudos, o chimbal (colcheias, semicolcheias) dominava e o pulso saía no dobro.
    private static final int FFT = 1024;
    private static final int[] BAND_EDGES = bandEdges();
    private static final int BANDS = BAND_EDGES.length - 1;
    private final Fft fft = new Fft(FFT);
    private final float[] window = hann(FFT);
    private final float[] ring = new float[FFT];
    private int ringPos;
    private final float[] re = new float[FFT], im = new float[FFT];
    private final float[] prevBand = new float[BANDS], bandMean = new float[BANDS];
    private boolean havePrev;

    private static int[] bandEdges() {
        // 8 faixas em escala logarítmica, ~50 Hz a ~11 kHz (resolução de 43 Hz por posição)
        double lo = 50, hi = 11000;
        int[] e = new int[9];
        for (int b = 0; b <= 8; b++) e[b] = (int) Math.round(lo * Math.pow(hi / lo, b / 8.0) * FFT / 44100.0);
        for (int b = 1; b <= 8; b++) if (e[b] <= e[b - 1]) e[b] = e[b - 1] + 1;
        return e;
    }

    private static float[] hann(int n) {
        float[] w = new float[n];
        for (int i = 0; i < n; i++) w[i] = (float) (0.5 - 0.5 * Math.cos(2 * Math.PI * i / n));
        return w;
    }

    public synchronized void feed(short[] pcm, int n) {
        for (int i = 0; i < n; i++) {
            ring[ringPos] = pcm[i] / 32768f;
            ringPos = (ringPos + 1) % FFT;
            if (++inBlock < BLOCK) continue;
            inBlock = 0;
            onset[pos] = flux();
            if (historyCount == history.length) history = Arrays.copyOf(history, history.length * 2);
            history[historyCount++] = onset[pos];
            pos = (pos + 1) % ENV_LEN;
            if (count < ENV_LEN) count++;
            if (++sinceEstimate >= 100 && count >= 600) {
                sinceEstimate = 0;
                estimate();
            }
        }
    }

    /** Quanto o som subiu desde o quadro anterior, somando as faixas (cada uma pela sua média). */
    private float flux() {
        for (int k = 0; k < FFT; k++) {
            re[k] = ring[(ringPos + k) % FFT] * window[k];
            im[k] = 0f;
        }
        fft.transform(re, im);
        float total = 0;
        for (int b = 0; b < BANDS; b++) {
            double e = 0;
            for (int k = BAND_EDGES[b]; k < BAND_EDGES[b + 1]; k++) e += re[k] * re[k] + im[k] * im[k];
            float level = (float) Math.log1p(1e4 * e / (BAND_EDGES[b + 1] - BAND_EDGES[b]));
            float rise = havePrev ? Math.max(0, level - prevBand[b]) : 0;
            prevBand[b] = level;
            // cada faixa conta pelo tamanho típico das subidas dela (média lenta, ~3 s)
            bandMean[b] += 0.003f * (rise - bandMean[b]);
            total += rise / (bandMean[b] + 0.05f);
        }
        havePrev = true;
        return total / BANDS;
    }

    private void estimate() {
        int len = count;
        float[] o = new float[len];
        int start = (pos - len + ENV_LEN) % ENV_LEN;
        for (int i = 0; i < len; i++) o[i] = onset[(start + i) % ENV_LEN];
        float value = period(o, len);
        if (value <= 0) return;
        recent[recentPos] = value;
        recentPos = (recentPos + 1) % recent.length;
        if (recentCount < recent.length) recentCount++;
        float[] sorted = Arrays.copyOf(recent, recentCount);
        Arrays.sort(sorted);
        bpm = sorted[recentCount / 2];
    }

    /** Andamento da música inteira ouvida até agora (0 se ainda é pouco). Leva alguns ms numa música longa. */
    public float songBpm() {
        float[] o;
        int n;
        synchronized (this) {
            n = historyCount;
            if (n < 600) return bpm;
            o = Arrays.copyOf(history, n);
        }
        float v = period(o, n);
        return v > 0 ? v : bpm;
    }

    private static final int HARMONICS = 2, REFINE = 8;
    /** Preferência por andamentos perto de 115 BPM (para decidir entre o dobro e a metade). */
    private static final double CENTER = 115, WIDTH = 0.8;
    private static final double HALF_WEIGHT = 0.35;

    /** BPM pelo período que melhor explica a autocorrelação nele e nos múltiplos; 0 sem ataques. */
    static float period(float[] o, int len) {
        // tira a tendência (média móvel de ~1 s): mudanças de volume não viram "ataques"
        float[] x = new float[len];
        int window = 50;
        double[] prefix = new double[len + 1];
        for (int i = 0; i < len; i++) prefix[i + 1] = prefix[i] + o[i];
        double energy = 0;
        for (int i = 0; i < len; i++) {
            int a = Math.max(0, i - window), b = Math.min(len, i + window + 1);
            x[i] = (float) (o[i] - (prefix[b] - prefix[a]) / (b - a));
            energy += (double) x[i] * x[i];
        }
        if (energy < 1e-6) return 0;
        int maxLag = Math.min(len - 1, (int) (MAX_LAG * 1.03 * REFINE) + 2);
        double[] ac = new double[maxLag + 1];
        for (int lag = 1; lag <= maxLag; lag++) {
            double s = 0;
            for (int i = lag; i < len; i++) s += (double) x[i] * x[i - lag];
            ac[lag] = s / (len - lag);
        }
        // 1) qual andamento: poucos múltiplos + o meio do tempo (separa o certo de um 3:2 dele),
        //    com a preferência por andamentos perto de 115 BPM decidindo entre o dobro e a metade
        double best = -1e18;
        double bestP = -1;
        for (double p = MIN_LAG; p <= MAX_LAG; p += 0.05) {
            double score = choose(ac, maxLag, p);
            if (Double.isNaN(score)) continue;
            double b = 6000.0 / p;
            double prior = Math.exp(-0.5 * Math.pow(Math.log(b / CENTER) / Math.log(2) / WIDTH, 2));
            score = score > 0 ? score * prior : score / prior;
            if (score > best) { best = score; bestP = p; }
        }
        if (bestP <= 0 || best <= 0) return 0;
        // 2) precisão: perto dele (±3%), o período que mais acerta os 8 primeiros múltiplos
        double fine = bestP, fineScore = -1e18;
        for (double p = bestP * 0.97; p <= bestP * 1.03; p += 0.005) {
            double sum = 0;
            int used = 0;
            for (int k = 1; k <= REFINE; k++) {
                double l = k * p;
                if ((int) l + 1 > maxLag) break;
                sum += at(ac, l);
                used++;
            }
            if (used > 0 && sum / used > fineScore) { fineScore = sum / used; fine = p; }
        }
        bestP = fine;
        if (bestP <= 0 || best <= 0) return 0;
        return (float) (6000.0 / bestP);
    }

    /** Nota do período p para escolher o andamento (NaN se não cabe na autocorrelação). */
    private static double choose(double[] ac, int maxLag, double p) {
        double on = 0, half = 0;
        int used = 0;
        for (int k = 1; k <= HARMONICS; k++) {
            double l = k * p;
            if ((int) l + 1 > maxLag) break;
            on += at(ac, l);
            half += at(ac, l - 0.5 * p);
            used++;
        }
        return used == 0 ? Double.NaN : (on + HALF_WEIGHT * half) / used;
    }

    private static double at(double[] ac, double l) {
        int li = (int) l;
        double f = l - li;
        return ac[li] + (ac[li + 1] - ac[li]) * f;
    }
}
