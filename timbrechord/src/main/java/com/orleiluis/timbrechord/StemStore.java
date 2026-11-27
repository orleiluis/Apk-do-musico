package com.orleiluis.timbrechord;

import android.content.Context;

import com.orleiluis.timbrechord.core.MiniJson;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Faixas separadas de cada música, na memória do app: um arquivo por faixa, PCM de 16 bits estéreo
 * em 44,1 kHz (o player lê direto do disco). Ficam todas guardadas, sem limite, até a pessoa apagar
 * na lista; tocam de novo sem precisar do arquivo original.
 */
final class StemStore {
    private StemStore() {}

    static final int SAMPLE_RATE = 44100;

    static final class StemSet {
        final String id, title;
        final String[] sources;
        final File[] files;
        final long frames;

        StemSet(String id, String title, String[] sources, File[] files, long frames) {
            this.id = id;
            this.title = title;
            this.sources = sources;
            this.files = files;
            this.frames = frames;
        }

        long bytes() {
            long sum = 0;
            for (File f : files) sum += f.length();
            return sum;
        }
    }

    static File root(Context c) { return new File(c.getFilesDir(), "faixas"); }

    static File dir(Context c, String id) { return new File(root(c), id.replaceAll("[^A-Za-z0-9_-]", "_")); }

    static File partialDir(Context c, String id) { return new File(root(c), id.replaceAll("[^A-Za-z0-9_-]", "_") + ".parte"); }

    static boolean has(Context c, String id) { return !id.isEmpty() && new File(dir(c, id), "info.json").exists(); }

    static StemSet load(Context c, String id) { return load(c, id, true); }

    /** touch = marcar como usada agora (para a ordem da lista). */
    static StemSet load(Context c, String id, boolean touch) {
        File d = dir(c, id);
        File info = new File(d, "info.json");
        if (id.isEmpty() || !info.exists()) return null;
        try {
            Map<String, Object> json = MiniJson.obj(MiniJson.parse(new String(Files.readAllBytes(info.toPath()), StandardCharsets.UTF_8)));
            List<Object> names = MiniJson.arr(json.get("sources"));
            String[] sources = new String[names.size()];
            File[] files = new File[names.size()];
            long frames = (long) MiniJson.dbl(json, "frames", 0);
            for (int i = 0; i < sources.length; i++) {
                sources[i] = String.valueOf(names.get(i));
                files[i] = new File(d, i + ".pcm");
                if (files[i].length() < frames * 4) return null; // incompleto
            }
            //noinspection ResultOfMethodCallIgnored
            if (touch) d.setLastModified(System.currentTimeMillis());
            return new StemSet(id, MiniJson.str(json, "title", ""), sources, files, frames);
        } catch (Exception e) {
            return null;
        }
    }

    /** Fecha uma separação: grava a descrição e troca a pasta parcial pela definitiva. */
    static void commit(Context c, String id, String title, String[] sources, long frames) throws IOException {
        File part = partialDir(c, id);
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("title", title);
        json.put("frames", (double) frames);
        json.put("sources", new ArrayList<Object>(Arrays.asList((Object[]) sources)));
        try (OutputStream out = new FileOutputStream(new File(part, "info.json"))) {
            out.write(MiniJson.write(json).getBytes(StandardCharsets.UTF_8));
        }
        File d = dir(c, id);
        ModelStore.deleteTree(d);
        if (!part.renameTo(d)) throw new IOException("não consegui guardar as faixas");
        cleanPartials(c, id);
    }

    static void delete(Context c, String id) {
        ModelStore.deleteTree(dir(c, id));
    }

    /** Apaga sobras de separações interrompidas (app fechado no meio). */
    static void cleanPartials(Context c, String current) {
        File[] all = root(c).listFiles();
        if (all == null) return;
        for (File f : all)
            if (f.getName().endsWith(".parte") && !f.equals(partialDir(c, current))) ModelStore.deleteTree(f);
    }

    /** Todas as músicas separadas, a usada por último primeiro. */
    static List<StemSet> list(Context c) {
        List<StemSet> out = new ArrayList<>();
        final Map<String, Long> used = new java.util.HashMap<>();
        File[] all = root(c).listFiles();
        if (all == null) return out;
        for (File f : all) {
            if (!f.isDirectory() || f.getName().endsWith(".parte")) continue;
            long when = f.lastModified();
            StemSet set = load(c, f.getName(), false);
            if (set == null) continue;
            used.put(set.id, when);
            out.add(set);
        }
        out.sort(new Comparator<StemSet>() {
            @Override
            public int compare(StemSet a, StemSet b) { return Long.compare(used.get(b.id), used.get(a.id)); }
        });
        return out;
    }

    static long totalBytes(List<StemSet> sets) {
        long sum = 0;
        for (StemSet s : sets) sum += s.bytes();
        return sum;
    }

    /** Tamanho que as faixas de uma música desta duração ocupam. */
    static long estimateBytes(long durationMs, int sources) {
        return durationMs * SAMPLE_RATE / 1000 * 4L * sources;
    }
}
