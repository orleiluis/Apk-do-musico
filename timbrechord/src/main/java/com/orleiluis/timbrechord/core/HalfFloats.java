package com.orleiluis.timbrechord.core;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Os pesos do modelo vêm no APK em float16 (metade do tamanho) e são expandidos para float32 uma vez,
 * no primeiro uso. O arquivo de pesos só tem float32 contíguos, então a conversão é número a número.
 */
public final class HalfFloats {
    private HalfFloats() {}

    private static final int[] TABLE = new int[65536];

    static {
        for (int h = 0; h < 65536; h++) TABLE[h] = Float.floatToRawIntBits(toFloat(h));
    }

    public static float toFloat(int h) {
        int sign = (h >>> 15) & 1, exp = (h >>> 10) & 0x1f, mant = h & 0x3ff;
        float v;
        if (exp == 0) v = mant * (float) Math.pow(2, -24);                 // subnormal
        else if (exp == 31) v = mant == 0 ? Float.POSITIVE_INFINITY : Float.NaN;
        else v = Float.intBitsToFloat(((exp - 15 + 127) << 23) | (mant << 13));
        return sign == 1 ? -v : v;
    }

    /** Lê float16 little-endian e escreve float32 little-endian. Devolve quantos números converteu. */
    public static long expand(InputStream in, OutputStream out) throws IOException {
        byte[] src = new byte[1 << 16];
        byte[] dst = new byte[src.length * 2];
        long count = 0;
        int carry = -1;
        int n;
        while ((n = in.read(src)) > 0) {
            int o = 0, i = 0;
            if (carry >= 0) {
                o = put(dst, 0, carry | ((src[0] & 0xff) << 8));
                carry = -1;
                i = 1;
                count++;
            }
            for (; i + 1 < n; i += 2) {
                o = put(dst, o, (src[i] & 0xff) | ((src[i + 1] & 0xff) << 8));
                count++;
            }
            if (i < n) carry = src[i] & 0xff;
            out.write(dst, 0, o);
        }
        if (carry >= 0) throw new IOException("arquivo de pesos cortado");
        return count;
    }

    private static int put(byte[] dst, int o, int half) {
        int bits = TABLE[half];
        dst[o] = (byte) bits;
        dst[o + 1] = (byte) (bits >>> 8);
        dst[o + 2] = (byte) (bits >>> 16);
        dst[o + 3] = (byte) (bits >>> 24);
        return o + 4;
    }
}
