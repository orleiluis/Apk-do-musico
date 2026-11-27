package com.orleiluis.timbrechord;

import android.content.Context;

import com.orleiluis.timbrechord.core.ChartRecorder;
import com.orleiluis.timbrechord.core.ChordChart;
import com.orleiluis.timbrechord.core.EffectCatalog;
import com.orleiluis.timbrechord.core.LiveAnalyzer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** Estado compartilhado entre a tela e o serviço de captura. */
final class AppState {
    private AppState() {}

    static final LiveAnalyzer analyzer = new LiveAnalyzer();

    /**
     * Mapa de acordes da música aberta. chartId diz de quem ele é: "yt_<id do vídeo>", "f_<arquivo>"
     * ou "" quando o som vem de outro app (aí o mapa vale só para esta escuta e não é guardado).
     */
    static volatile ChordChart chart = new ChordChart();
    static volatile String chartId = "";
    static final ChartRecorder recorder = new ChartRecorder();

    private static EffectCatalog catalog;
    private static byte[] template;

    static synchronized EffectCatalog catalog(Context c) throws IOException {
        if (catalog == null)
            catalog = EffectCatalog.parse(new String(readAsset(c, "gp150_effect_catalog.json"), StandardCharsets.UTF_8));
        return catalog;
    }

    static synchronized byte[] prstTemplate(Context c) throws IOException {
        if (template == null) template = readAsset(c, "gp150_template.prst");
        return template;
    }

    private static byte[] readAsset(Context c, String name) throws IOException {
        try (InputStream in = c.getApplicationContext().getAssets().open(name);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] b = new byte[8192];
            int n;
            while ((n = in.read(b)) >= 0) out.write(b, 0, n);
            return out.toByteArray();
        }
    }
}
