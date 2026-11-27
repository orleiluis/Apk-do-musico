package com.orleiluis.timbrechord.core;

/** FFT radix-2 em ponto flutuante, com tabelas pré-calculadas para um tamanho fixo. */
public final class Fft {
    public final int n;
    private final int levels;
    private final float[] cos, sin;
    private final int[] rev;

    public Fft(int n) {
        if (n < 2 || (n & (n - 1)) != 0) throw new IllegalArgumentException("tamanho precisa ser potência de 2");
        this.n = n;
        this.levels = 31 - Integer.numberOfLeadingZeros(n);
        cos = new float[n / 2];
        sin = new float[n / 2];
        for (int i = 0; i < n / 2; i++) {
            double a = 2 * Math.PI * i / n;
            cos[i] = (float) Math.cos(a);
            sin[i] = (float) Math.sin(a);
        }
        rev = new int[n];
        for (int i = 0; i < n; i++) rev[i] = Integer.reverse(i) >>> (32 - levels);
    }

    /** Transforma no lugar. */
    public void transform(float[] re, float[] im) {
        for (int i = 0; i < n; i++) {
            int j = rev[i];
            if (j > i) {
                float t = re[i]; re[i] = re[j]; re[j] = t;
                t = im[i]; im[i] = im[j]; im[j] = t;
            }
        }
        for (int size = 2; size <= n; size <<= 1) {
            int half = size >> 1, step = n / size;
            for (int i = 0; i < n; i += size) {
                for (int j = i, k = 0; j < i + half; j++, k += step) {
                    int l = j + half;
                    float tre = re[l] * cos[k] + im[l] * sin[k];
                    float tim = -re[l] * sin[k] + im[l] * cos[k];
                    re[l] = re[j] - tre;
                    im[l] = im[j] - tim;
                    re[j] += tre;
                    im[j] += tim;
                }
            }
        }
    }

    public static float[] hann(int n) {
        float[] w = new float[n];
        for (int i = 0; i < n; i++) w[i] = (float) (0.5 - 0.5 * Math.cos(2 * Math.PI * i / (n - 1)));
        return w;
    }
}
