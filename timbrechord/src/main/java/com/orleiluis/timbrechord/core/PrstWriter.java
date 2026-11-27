package com.orleiluis.timbrechord.core;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Grava a cadeia no formato .prst da Valeton GP-150 (arquivo de 1128 bytes, 12 blocos de 68 bytes).
 * Mesmo layout do gravador que já funciona no ToneMatch: parte de um preset de referência e
 * reescreve nome, blocos e checksum.
 */
public final class PrstWriter {
    private PrstWriter() {}

    public static final int FILE_SIZE = 1128;
    private static final int NAME_OFFSET = 44, NAME_LENGTH = 16;
    private static final int BLOCK_START = 132, BLOCK_SIZE = 68, ENABLE_REL = 0, FXID_REL = 4, PARAM_REL = 8;
    private static final long EMPTY_FXID = 0x06000003L;

    public static byte[] create(byte[] template, EffectCatalog catalog, ToneChain chain) throws IOException {
        if (template == null || template.length != FILE_SIZE)
            throw new IOException("Modelo .prst inválido: esperado " + FILE_SIZE + " bytes.");
        byte[] data = Arrays.copyOf(template, template.length);
        chain.normalize(catalog);
        writeName(data, presetName(chain.name));
        data[4] = 0;

        ToneChain.Block ns = chain.block("NS");
        boolean snapToneActive = ns != null && ns.enabled && !ns.isNone();
        boolean wahActive = false;
        float wahPosition = Float.NaN;

        for (int i = 0; i < EffectCatalog.BLOCK_ORDER.length; i++) {
            String module = EffectCatalog.BLOCK_ORDER[i];
            int block = BLOCK_START + i * BLOCK_SIZE;
            ToneChain.Block bs = chain.block(module);

            if ("VOL".equals(module)) {
                EffectCatalog.Effect vol = catalog.effect("VOL", "Volume");
                putUInt32LE(data, block + ENABLE_REL, 1);
                if (vol != null) putUInt32LE(data, block + FXID_REL, vol.fxid);
                Arrays.fill(data, block + PARAM_REL, block + BLOCK_SIZE, (byte) 0);
                Float v = bs == null ? null : bs.params.get("Volume");
                putFloat(data, block + PARAM_REL, clamp(v == null ? 100 : v, 0, 100));
                continue;
            }

            boolean enabled = bs != null && bs.enabled;
            if (("AMP".equals(module) || "CAB".equals(module)) && snapToneActive) enabled = false;
            EffectCatalog.Effect effect = bs == null || bs.isNone() ? null : catalog.effect(module, bs.effectName);

            if (effect == null || effect.isNone()) {
                putUInt32LE(data, block + ENABLE_REL, 0);
                putUInt32LE(data, block + FXID_REL, EMPTY_FXID);
                Arrays.fill(data, block + PARAM_REL, block + BLOCK_SIZE, (byte) 0);
                continue;
            }
            putUInt32LE(data, block + ENABLE_REL, enabled ? 1 : 0);
            putUInt32LE(data, block + FXID_REL, effect.fxid);
            Arrays.fill(data, block + PARAM_REL, block + BLOCK_SIZE, (byte) 0);
            for (EffectCatalog.Param pm : effect.params) {
                if (pm.algId < 0 || pm.algId > 14) continue;
                Float given = bs.params.get(pm.name);
                float value = clamp(given == null ? pm.defaultValue : given, pm.min, pm.max);
                putFloat(data, block + PARAM_REL + pm.algId * 4, value);
                if ("WAH".equals(module) && "position".equalsIgnoreCase(pm.name)) wahPosition = value;
            }
            if ("WAH".equals(module) && enabled) wahActive = true;
        }
        configureWahPedal(data, wahActive, wahPosition);
        data[13] = 21;
        updateChecksum(data);
        return data;
    }

    /** Nome do preset como cabe na pedaleira: ASCII, até 15 caracteres. */
    public static String presetName(String name) {
        String n = name == null ? "" : java.text.Normalizer.normalize(name, java.text.Normalizer.Form.NFD)
                .replaceAll("[^A-Za-z0-9 _-]", "").replaceAll("\\s+", " ").trim();
        if (n.isEmpty()) n = "Timbre Chord";
        if (n.length() > 15) n = n.substring(0, 15).trim();
        return n;
    }

    /** Liga o pedal de expressão ao wah quando o bloco WAH está ativo (igual ao gravador de referência). */
    private static void configureWahPedal(byte[] data, boolean wahActive, float position) {
        if (!wahActive) return;
        int marker = findUInt16LE(data, 0x3060, 928);
        if (marker < 0 || marker + 4 > data.length) return;
        int length = getUInt16LE(data, marker + 2), base = marker + 4;
        if (base + 108 > Math.min(base + length, data.length)) return;
        for (int i = 0; i < 9; i++) {
            int at = base + 12 * i;
            putUInt16LE(data, at, 0xffff);
            putUInt16LE(data, at + 2, 0);
            putFloat(data, at + 4, 0);
            putFloat(data, at + 8, 100);
        }
        if (!Float.isNaN(position) && Math.round(position) == 48) { putUInt16LE(data, base, 11); putUInt16LE(data, base + 2, 0); }
        else { putUInt16LE(data, base, 1); putUInt16LE(data, base + 2, 3); }
        putFloat(data, base + 4, 0);
        putFloat(data, base + 8, 100);
    }

    /** CRC-16 (polinômio 0xA001) dos bytes 12..1123, guardado nos bytes 14 e 15. */
    public static int checksum(byte[] d) {
        byte b14 = d[14], b15 = d[15];
        d[14] = 0;
        d[15] = 0;
        int crc = 0xffff;
        for (int i = 12; i < Math.min(1124, d.length); i++) {
            crc ^= d[i] & 255;
            for (int b = 0; b < 8; b++) crc = (crc & 1) != 0 ? (crc >>> 1) ^ 0xa001 : crc >>> 1;
        }
        d[14] = b14;
        d[15] = b15;
        return crc;
    }

    private static void updateChecksum(byte[] d) {
        int crc = checksum(d);
        d[14] = (byte) ((crc >>> 8) & 255);
        d[15] = (byte) (crc & 255);
    }

    private static int findUInt16LE(byte[] d, int value, int start) {
        for (int i = Math.max(0, start); i + 1 < d.length; i++) if (getUInt16LE(d, i) == value) return i;
        return -1;
    }
    private static int getUInt16LE(byte[] d, int o) { return (d[o] & 255) | ((d[o + 1] & 255) << 8); }
    private static void putUInt16LE(byte[] d, int o, int v) { d[o] = (byte) (v & 255); d[o + 1] = (byte) ((v >>> 8) & 255); }
    private static float clamp(float v, float min, float max) { return Math.max(min, Math.min(max, v)); }
    private static void putFloat(byte[] d, int o, float v) { ByteBuffer.wrap(d, o, 4).order(ByteOrder.LITTLE_ENDIAN).putFloat(v); }
    private static void putUInt32LE(byte[] d, int o, long v) { ByteBuffer.wrap(d, o, 4).order(ByteOrder.LITTLE_ENDIAN).putInt((int) (v & 0xffffffffL)); }
    private static void writeName(byte[] d, String name) {
        Arrays.fill(d, NAME_OFFSET, NAME_OFFSET + NAME_LENGTH, (byte) 0);
        byte[] b = name.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(b, 0, d, NAME_OFFSET, Math.min(b.length, NAME_LENGTH - 1));
    }
}
