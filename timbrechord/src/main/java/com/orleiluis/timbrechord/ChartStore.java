package com.orleiluis.timbrechord;

import android.content.Context;

import com.orleiluis.timbrechord.core.ChordChart;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Guarda no aparelho o mapa de acordes de cada música já ouvida (um arquivo de texto pequeno por
 * música, na pasta interna do app). Só os acordes e os tempos são guardados, nunca o áudio.
 */
final class ChartStore {
    private ChartStore() {}

    private static File file(Context c, String id) {
        File dir = new File(c.getApplicationContext().getFilesDir(), "charts");
        return new File(dir, id.replaceAll("[^A-Za-z0-9_-]", "_") + ".txt");
    }

    /** Mapa guardado da música, ou null se ela ainda não foi ouvida. */
    static ChordChart load(Context c, String id) {
        try {
            File f = file(c, id);
            if (!f.isFile()) return null;
            return ChordChart.parse(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
        } catch (Exception e) {
            return null;
        }
    }

    static void save(Context c, String id, ChordChart chart) {
        try {
            File f = file(c, id);
            File dir = f.getParentFile();
            if (dir != null && !dir.isDirectory() && !dir.mkdirs()) return;
            File tmp = new File(dir, f.getName() + ".tmp");
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(chart.serialize().getBytes(StandardCharsets.UTF_8));
            }
            // troca de uma vez: se o app for fechado no meio, o mapa antigo continua inteiro
            if (tmp.renameTo(f)) chart.markSaved();
        } catch (Exception ignored) { }
    }

    static void delete(Context c, String id) {
        try { file(c, id).delete(); } catch (Exception ignored) { }
    }
}
