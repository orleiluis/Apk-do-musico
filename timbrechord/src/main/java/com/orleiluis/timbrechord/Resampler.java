package com.orleiluis.timbrechord;

import com.orleiluis.timbrechord.core.LiveAnalyzer;

/** Leva o áudio mono para 44,1 kHz (interpolação linear; é só para a análise) e entrega ao analisador. */
final class Resampler {
    private final LiveAnalyzer target;
    private double phase;
    private short last;
    private short[] out = new short[0];
    private long delivered;

    Resampler(LiveAnalyzer target) { this.target = target; }

    void reset() { phase = 0; last = 0; }

    /** Amostras (em 44,1 kHz) já entregues ao analisador. */
    long delivered() { return delivered; }

    void feed(short[] in, int n, int rate) {
        if (n <= 0) return;
        if (rate == LiveAnalyzer.SAMPLE_RATE) {
            target.feed(in, n);
            delivered += n;
            return;
        }
        double step = rate / (double) LiveAnalyzer.SAMPLE_RATE;
        int max = (int) (n / step) + 2;
        if (out.length < max) out = new short[max];
        int count = 0;
        // phase = posição dentro de in[], onde -1 é a última amostra do bloco anterior
        while (phase < n - 1 && count < max) {
            int i = (int) Math.floor(phase);
            double frac = phase - i;
            int a = i < 0 ? last : in[i], b = in[i + 1];
            out[count++] = (short) Math.round(a + (b - a) * frac);
            phase += step;
        }
        phase -= n;
        last = in[n - 1];
        if (count > 0) {
            target.feed(out, count);
            delivered += count;
        }
    }
}
