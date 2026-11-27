package com.orleiluis.timbrechord.core;

/**
 * Troca a taxa de amostragem de um canal de áudio (por exemplo 48 kHz -> 44,1 kHz) com sinc janelada
 * (Kaiser), em fluxo: entra um bloco, sai o que já dá para calcular. A separação precisa de 44,1 kHz,
 * e a qualidade aqui vai direto para as faixas que a pessoa ouve, por isso não é interpolação linear.
 */
public final class SincResampler {
    private static final int PHASES = 512;
    private final int inRate, outRate;
    private final int width;          // meia largura do filtro, em amostras de entrada
    private final float[] table;      // h(t) para t = i / PHASES, i = 0 .. width*PHASES (+1)
    private float[] hist = new float[4096];
    private int count;                // amostras guardadas em hist
    private long histStart;           // índice (absoluto, na entrada) de hist[0]; negativo no começo = zeros
    private long outIndex;
    private long inTotal;
    private float[] out = new float[4096];

    public SincResampler(int inRate, int outRate) {
        this.inRate = inRate;
        this.outRate = outRate;
        double cutoff = Math.min(1.0, (double) outRate / inRate) * 0.94;
        width = (int) Math.ceil(16 / cutoff);
        double beta = 8.6;
        table = new float[width * PHASES + 2];
        double i0b = bessel0(beta);
        for (int i = 0; i < table.length; i++) {
            double t = i / (double) PHASES;
            double x = t / width;
            double kaiser = x >= 1 ? 0 : bessel0(beta * Math.sqrt(1 - x * x)) / i0b;
            double sinc = t == 0 ? 1 : Math.sin(Math.PI * cutoff * t) / (Math.PI * cutoff * t);
            table[i] = (float) (cutoff * sinc * kaiser);
        }
        histStart = -width;
        count = width; // zeros antes do começo
    }

    private static double bessel0(double x) {
        double sum = 1, term = 1, q = x * x / 4;
        for (int k = 1; k < 60; k++) {
            term *= q / ((double) k * k);
            sum += term;
            if (term < 1e-12 * sum) break;
        }
        return sum;
    }

    public boolean passthrough() { return inRate == outRate; }

    /** Amostras de saída do último feed/finish. */
    public float[] output() { return out; }

    /** Entrega n amostras; devolve quantas saíram em output(). */
    public int feed(float[] in, int n) {
        inTotal += n;
        append(in, n);
        return produce(Long.MAX_VALUE);
    }

    /** Fim do áudio: solta o que falta. */
    public int finish() {
        float[] zeros = new float[width + 2];
        append(zeros, zeros.length);
        long expected = (inTotal * outRate + inRate - 1) / inRate;
        return produce(expected);
    }

    private void append(float[] in, int n) {
        if (count + n > hist.length) {
            // descarta o que nenhum ponto de saída futuro usa
            long needFrom = (outIndex * inRate) / outRate - width;
            int drop = (int) Math.max(0, Math.min(count, needFrom - histStart));
            System.arraycopy(hist, drop, hist, 0, count - drop);
            count -= drop;
            histStart += drop;
            if (count + n > hist.length) {
                float[] bigger = new float[Math.max(hist.length * 2, count + n)];
                System.arraycopy(hist, 0, bigger, 0, count);
                hist = bigger;
            }
        }
        System.arraycopy(in, 0, hist, count, n);
        count += n;
    }

    private int produce(long limit) {
        int made = 0;
        long available = histStart + count; // primeiro índice que ainda não chegou
        while (outIndex < limit) {
            long num = outIndex * inRate;
            long base = num / outRate;
            double frac = (num % outRate) / (double) outRate;
            if (base + width + 1 > available) break;
            double acc = 0;
            for (int k = -width + 1; k <= width; k++) {
                long idx = base + k;
                int h = (int) (idx - histStart);
                if (h < 0 || h >= count) continue;
                double t = Math.abs(k - frac) * PHASES;
                int ti = (int) t;
                if (ti + 1 >= table.length) continue;
                double tf = t - ti;
                acc += hist[h] * (table[ti] + (table[ti + 1] - table[ti]) * tf);
            }
            if (made >= out.length) {
                float[] bigger = new float[out.length * 2];
                System.arraycopy(out, 0, bigger, 0, made);
                out = bigger;
            }
            out[made++] = (float) acc;
            outIndex++;
        }
        return made;
    }
}
