package com.orleiluis.timbrechord.core;

import java.util.Arrays;
import java.util.Locale;

/**
 * Acumula medidas do som enquanto a música toca: equilíbrio entre graves/médios/agudos, brilho,
 * presença, dinâmica e o quanto o espectro está "preenchido" entre as notas (típico de distorção).
 * Entrada: PCM mono 44,1 kHz. É uma leitura da mixagem inteira, não da guitarra isolada.
 *
 * As faixas que transformam cada medida em um índice de 0 a 1 foram ajustadas com gravações reais
 * (pop com voz, rock, jazz, piano, violão, orquestra), para que músicas diferentes caiam em pontos
 * diferentes da escala em vez de todas no mesmo extremo.
 */
public final class ToneAnalyzer {
    private static final int RATE = 44100;
    private static final int N = 8192;
    public static final float[] EDGES = {60, 120, 250, 500, 1000, 2000, 4000, 8000, 16000};
    public static final int BANDS = EDGES.length - 1;
    private static final float GATE_RMS = 0.004f;
    /** Faixas em que se mede o contraste entre as notas e o "chão" entre elas. */
    private static final float[] CONTRAST_EDGES = {300, 600, 1200, 2400};
    private static final float CONTRAST_STEP = 0.25f;
    private static final int CONTRAST_BINS = 320;

    public static final class Features {
        /** Segundos de música realmente analisados (silêncio não conta). */
        public double seconds;
        /** Energia de cada faixa em dB relativa ao total (60-120, 120-250, 250-500, 500-1k, 1k-2k, 2k-4k, 4k-8k, 8k-16k Hz). */
        public final float[] bandDb = new float[BANDS];
        /** Centro do espectro entre 150 Hz e 8 kHz (média geométrica ao longo da música). */
        public float centroidHz;
        /** 0..1: planicidade do espectro entre 500 Hz e 2 kHz. */
        public float flatness;
        /** Fator de crista médio em dB (pico/RMS). */
        public float crestDb;
        /** Variação de volume ao longo da música em dB: baixa = som denso e comprimido. */
        public float dynamicsDb;
        /** Distância em dB entre as notas e o chão entre elas, nos trechos mais limpos: baixa = espectro preenchido. */
        public float contrastDb;
        public float bpm;
        /** Índices 0..1 derivados. */
        public float drive, brightness, bass, mids, presence;

        public boolean enough() { return seconds >= 8; }

        public String describe() {
            return String.format(Locale.US,
                    "amostra %.0f s | brilho %.0f%% | graves %.0f%% | medios %.0f%% | presenca %.0f%% | saturacao estimada %.0f%% | "
                            + "contraste %.1f dB | dinamica %.1f dB | crista %.1f dB | centroide %.0f Hz",
                    seconds, brightness * 100, bass * 100, mids * 100, presence * 100, drive * 100,
                    contrastDb, dynamicsDb, crestDb, centroidHz);
        }
    }

    private final Fft fft = new Fft(N);
    private final float[] window = Fft.hann(N);
    private final float[] re = new float[N], im = new float[N];
    private final float[] block = new float[N];
    private final float[] sortBuf = new float[N / 2];
    private int blockPos;

    private final double[] bandSum = new double[BANDS];
    private final int[] contrastHist = new int[CONTRAST_BINS];
    private double centroidLogSum, flatLogSum, crestSum, rmsDbSum, rmsDbSqSum;
    private long frames;

    public synchronized void reset() {
        Arrays.fill(bandSum, 0);
        Arrays.fill(contrastHist, 0);
        centroidLogSum = flatLogSum = crestSum = rmsDbSum = rmsDbSqSum = 0;
        frames = 0;
        blockPos = 0;
    }

    public synchronized double seconds() { return frames * (double) N / RATE; }

    public synchronized void feed(short[] pcm, int n) {
        for (int i = 0; i < n; i++) {
            block[blockPos++] = pcm[i] / 32768f;
            if (blockPos == N) {
                blockPos = 0;
                analyzeBlock();
            }
        }
    }

    private void analyzeBlock() {
        double energy = 0;
        float peak = 0;
        for (int i = 0; i < N; i++) {
            float v = block[i];
            energy += v * v;
            float a = Math.abs(v);
            if (a > peak) peak = a;
            re[i] = v * window[i];
            im[i] = 0;
        }
        float rms = (float) Math.sqrt(energy / N);
        if (rms < GATE_RMS) return;
        fft.transform(re, im);

        double hz = (double) RATE / N;
        double total = 0, magSum = 0, magWeighted = 0, logSum = 0, linSum = 0;
        int flatBins = 0;
        double[] bands = new double[BANDS];
        int kLo = (int) Math.ceil(EDGES[0] / hz), kHi = Math.min(N / 2 - 1, (int) (EDGES[BANDS] / hz));
        int b = 0;
        for (int k = kLo; k <= kHi; k++) {
            double f = k * hz;
            if (f >= EDGES[BANDS]) break;
            double p = re[k] * re[k] + im[k] * im[k];
            re[k] = (float) p; // daqui em diante re[] guarda a potência
            while (b < BANDS - 1 && f >= EDGES[b + 1]) b++;
            bands[b] += p;
            total += p;
            if (f >= 150 && f < 8000) {
                double m = Math.sqrt(p);
                magSum += m;
                magWeighted += m * f;
            }
            if (f >= 500 && f < 2000) {
                logSum += Math.log(p + 1e-12);
                linSum += p;
                flatBins++;
            }
        }
        if (total <= 0 || magSum <= 0) return;

        // contraste: média dos 20% de bins mais fortes menos a dos 30% mais fracos, em cada faixa
        double contrast = 0;
        for (int c = 0; c + 1 < CONTRAST_EDGES.length; c++) {
            int from = (int) Math.ceil(CONTRAST_EDGES[c] / hz), to = (int) Math.ceil(CONTRAST_EDGES[c + 1] / hz);
            int n = to - from;
            for (int i = 0; i < n; i++) sortBuf[i] = (float) (10 * Math.log10(re[from + i] + 1e-14));
            Arrays.sort(sortBuf, 0, n);
            int lowN = (int) (n * 0.3), highFrom = (int) (n * 0.8);
            double low = 0, high = 0;
            for (int i = 0; i < lowN; i++) low += sortBuf[i];
            for (int i = highFrom; i < n; i++) high += sortBuf[i];
            contrast += high / (n - highFrom) - low / lowN;
        }
        contrast /= CONTRAST_EDGES.length - 1;
        int bin = (int) (contrast / CONTRAST_STEP);
        contrastHist[Math.max(0, Math.min(CONTRAST_BINS - 1, bin))]++;

        for (int i = 0; i < BANDS; i++) bandSum[i] += bands[i] / total;
        centroidLogSum += Math.log(magWeighted / magSum);
        if (flatBins > 0 && linSum > 0) flatLogSum += Math.log10(Math.exp(logSum / flatBins) / (linSum / flatBins) + 1e-9);
        crestSum += 20 * Math.log10(Math.max(peak, 1e-6f) / rms);
        double rmsDb = 20 * Math.log10(rms);
        rmsDbSum += rmsDb;
        rmsDbSqSum += rmsDb * rmsDb;
        frames++;
    }

    public synchronized Features snapshot(float bpm) {
        Features f = new Features();
        f.bpm = bpm;
        f.seconds = frames * (double) N / RATE;
        if (frames == 0) return f;
        for (int b = 0; b < BANDS; b++) f.bandDb[b] = (float) (10 * Math.log10(bandSum[b] / frames + 1e-9));
        f.centroidHz = (float) Math.exp(centroidLogSum / frames);
        f.flatness = (float) Math.pow(10, flatLogSum / frames);
        f.crestDb = (float) (crestSum / frames);
        double mean = rmsDbSum / frames;
        f.dynamicsDb = (float) Math.sqrt(Math.max(0, rmsDbSqSum / frames - mean * mean));
        f.contrastDb = percentile(0.80);

        double lowDb = 10 * Math.log10(share(0, 1) + 1e-9);   // 60-250 Hz
        double midDb = 10 * Math.log10(share(3, 4) + 1e-9);   // 500 Hz-2 kHz
        double presDb = 10 * Math.log10(share(5, 5) + 1e-9);  // 2-4 kHz
        f.brightness = unit(log2(f.centroidHz), log2(900), log2(2500));
        f.bass = unit(lowDb, -12, -1);
        f.mids = unit(midDb, -13, -2);
        f.presence = unit(presDb - midDb, -18, -4);
        // distorção deixa o som denso (pouca variação de volume) E preenche o espaço entre as notas
        // (pouco contraste); só uma das duas coisas não basta: piano tem pouco contraste em acordes
        // cheios, e música eletrônica é densa sem ter guitarra saturada
        float filled = unit(34 - f.contrastDb, 0, 8);
        float dense = unit(6 - f.dynamicsDb, 0, 4);
        f.drive = clamp01((float) Math.sqrt(filled * dense) * (0.75f + 0.25f * f.presence));
        return f;
    }

    private float percentile(double q) {
        long target = (long) Math.ceil(frames * q), seen = 0;
        for (int i = 0; i < CONTRAST_BINS; i++) {
            seen += contrastHist[i];
            if (seen >= target) return (i + 0.5f) * CONTRAST_STEP;
        }
        return CONTRAST_BINS * CONTRAST_STEP;
    }

    private double share(int from, int to) {
        double s = 0;
        for (int b = from; b <= to; b++) s += bandSum[b] / frames;
        return s;
    }

    private static double log2(double v) { return Math.log(Math.max(v, 1)) / Math.log(2); }

    private static float unit(double v, double lo, double hi) { return clamp01((float) ((v - lo) / (hi - lo))); }

    private static float clamp01(float v) { return v < 0 ? 0 : (v > 1 ? 1 : v); }
}
