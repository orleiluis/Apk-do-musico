package com.orleiluis.timbrechord;

import android.content.Context;

import com.orleiluis.timbrechord.core.HalfFloats;
import com.orleiluis.timbrechord.core.MiniJson;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * O modelo de separação (htdemucs_6s) vem dentro do APK com os pesos em float16. Na primeira separação
 * ele é copiado para a memória do app e os pesos são expandidos para float32, que é o que o ONNX Runtime lê.
 */
final class ModelStore {
    private ModelStore() {}

    static final String ASSETS = "separation";
    static final String INFO = "htdemucs_6s.json";

    static final class Info {
        String path = "";
        /** Pasta do modelo no aparelho e as partes da rede, na ordem em que rodam. */
        File dir;
        com.orleiluis.timbrechord.core.OrtStemModel.Part[] parts = new com.orleiluis.timbrechord.core.OrtStemModel.Part[0];
        String[] sources = new String[0];
        int sampleRate, segment, nfft, hop, bins, frames;
    }

    /** O APK foi compilado com o modelo? (compilações locais sem o Actions não têm.) */
    static boolean available(Context c) {
        try (InputStream in = c.getAssets().open(ASSETS + "/" + INFO)) {
            return in.read() >= 0;
        } catch (IOException e) {
            return false;
        }
    }

    static Map<String, Object> readInfo(Context c) throws IOException {
        return MiniJson.obj(MiniJson.parse(new String(readAll(c.getAssets().open(ASSETS + "/" + INFO)), StandardCharsets.UTF_8)));
    }

    /** Deixa o modelo pronto para uso e devolve onde ele está. Demora na primeira vez (cópia de ~100 MB). */
    static synchronized Info prepare(Context c) throws IOException {
        Map<String, Object> json = readInfo(c);
        Info info = new Info();
        List<Object> names = MiniJson.arr(json.get("sources"));
        info.sources = new String[names.size()];
        for (int i = 0; i < names.size(); i++) info.sources[i] = String.valueOf(names.get(i));
        info.sampleRate = (int) MiniJson.dbl(json, "sampleRate", 44100);
        info.segment = (int) MiniJson.dbl(json, "segment", 0);
        info.nfft = (int) MiniJson.dbl(json, "nfft", 4096);
        info.hop = (int) MiniJson.dbl(json, "hop", 1024);
        info.bins = (int) MiniJson.dbl(json, "bins", 2048);
        info.frames = (int) MiniJson.dbl(json, "frames", 0);
        String version = MiniJson.str(json, "version", "1").replaceAll("[^A-Za-z0-9_-]", "");
        String graph = MiniJson.str(json, "graph", "htdemucs_6s.onnx");
        String data = MiniJson.str(json, "data", "htdemucs_6s.onnx.data");
        String weights = MiniJson.str(json, "weights", "htdemucs_6s.half");

        File root = new File(c.getFilesDir(), "separation");
        File dir = new File(root, version);
        File ready = new File(dir, "pronto");
        if (!ready.exists()) {
            // versão antiga do modelo (APK atualizado): libera o espaço
            File[] old = root.listFiles();
            if (old != null) for (File f : old) if (!f.getName().equals(version)) deleteTree(f);
            deleteTree(dir);
            if (!dir.mkdirs()) throw new IOException("não consegui criar a pasta do modelo");
            copy(c.getAssets().open(ASSETS + "/" + graph), new File(dir, graph));
            for (com.orleiluis.timbrechord.core.OrtStemModel.Part p : com.orleiluis.timbrechord.core.OrtStemModel.partsFrom(json))
                if (!p.file.equals(graph)) copy(c.getAssets().open(ASSETS + "/" + p.file), new File(dir, p.file));
            File part = new File(dir, data + ".parte");
            try (InputStream in = c.getAssets().open(ASSETS + "/" + weights);
                 OutputStream out = new BufferedOutputStream(new FileOutputStream(part), 1 << 20)) {
                HalfFloats.expand(in, out);
            }
            if (!part.renameTo(new File(dir, data))) throw new IOException("não consegui gravar os pesos do modelo");
            if (!ready.createNewFile()) throw new IOException("não consegui marcar o modelo como pronto");
        }
        info.path = new File(dir, graph).getAbsolutePath();
        info.dir = dir;
        info.parts = com.orleiluis.timbrechord.core.OrtStemModel.partsFrom(json);
        return info;
    }

    private static void copy(InputStream in, File to) throws IOException {
        try (InputStream src = in; OutputStream out = new BufferedOutputStream(new FileOutputStream(to), 1 << 16)) {
            byte[] b = new byte[1 << 16];
            int n;
            while ((n = src.read(b)) > 0) out.write(b, 0, n);
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        try (InputStream src = in; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] b = new byte[8192];
            int n;
            while ((n = src.read(b)) > 0) out.write(b, 0, n);
            return out.toByteArray();
        }
    }

    static void deleteTree(File f) {
        if (f == null || !f.exists()) return;
        File[] children = f.listFiles();
        if (children != null) for (File child : children) deleteTree(child);
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }
}
