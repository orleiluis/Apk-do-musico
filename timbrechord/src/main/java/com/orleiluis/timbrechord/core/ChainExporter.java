package com.orleiluis.timbrechord.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Saídas genéricas da cadeia (texto e JSON), que servem para qualquer pedaleira. */
public final class ChainExporter {
    private ChainExporter() {}

    private static final Map<String, String> MODULE_LABEL = new LinkedHashMap<>();
    static {
        MODULE_LABEL.put("PRE", "Pré (compressor / boost)");
        MODULE_LABEL.put("WAH", "Wah");
        MODULE_LABEL.put("DST", "Overdrive / distorção");
        MODULE_LABEL.put("NS", "Captura SnapTone");
        MODULE_LABEL.put("AMP", "Amplificador");
        MODULE_LABEL.put("NR", "Noise gate");
        MODULE_LABEL.put("CAB", "Gabinete");
        MODULE_LABEL.put("EQ", "Equalizador");
        MODULE_LABEL.put("MOD", "Modulação");
        MODULE_LABEL.put("DLY", "Delay");
        MODULE_LABEL.put("RVB", "Reverb");
        MODULE_LABEL.put("VOL", "Volume");
    }

    public static String moduleLabel(String module) {
        String l = MODULE_LABEL.get(module);
        return l == null ? module : l;
    }

    /** Sequência de acordes sem repetições seguidas e sem trocas-relâmpago (menos de 0,4 s). */
    public static List<String> progression(List<ChordDetector.Event> timeline, double endSec) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < timeline.size(); i++) {
            ChordDetector.Event e = timeline.get(i);
            double next = i + 1 < timeline.size() ? timeline.get(i + 1).timeSec : endSec;
            if (next - e.timeSec < 0.4) continue;
            if (out.isEmpty() || !out.get(out.size() - 1).equals(e.chord)) out.add(e.chord);
        }
        return out;
    }

    public static String toText(ToneChain c, EffectCatalog catalog) {
        StringBuilder sb = new StringBuilder();
        sb.append("TIMBRE CHORD — CADEIA DE TIMBRE\n");
        sb.append("================================\n");
        if (!c.songTitle.isEmpty()) sb.append("Música: ").append(c.songTitle).append('\n');
        sb.append("Timbre: ").append(c.name).append('\n');
        sb.append("Gerado por: ").append(c.engine).append('\n');
        if (!c.key.isEmpty()) sb.append("Tom estimado: ").append(c.key).append('\n');
        if (c.bpm >= 50) sb.append(String.format(Locale.US, "Andamento estimado: %.0f BPM\n", c.bpm));
        if (!c.summary.isEmpty()) sb.append('\n').append(c.summary).append('\n');

        sb.append("\nCADEIA (na ordem do sinal)\n--------------------------\n");
        int n = 0;
        List<String> optional = new ArrayList<>();
        for (ToneChain.Block b : c.blocks.values()) {
            if (b.isNone() || "VOL".equals(b.module)) continue;
            String line = blockText(b, catalog);
            if (!b.enabled) { optional.add(line); continue; }
            sb.append(++n).append(". ").append(line).append('\n');
        }
        if (n == 0) sb.append("(nenhum bloco ligado)\n");
        if (!optional.isEmpty()) {
            sb.append("\nOPCIONAIS (na cadeia, mas desligados)\n-------------------------------------\n");
            for (String l : optional) sb.append("- ").append(l).append('\n');
        }
        if (!c.progression.isEmpty()) {
            sb.append("\nACORDES DETECTADOS\n------------------\n");
            int col = 0;
            for (String ch : c.progression) {
                if (col + ch.length() + 3 > 64) { sb.append('\n'); col = 0; }
                sb.append(col == 0 ? "" : " | ").append(ch);
                col += ch.length() + 3;
            }
            sb.append('\n');
        }
        if (!c.notes.isEmpty()) {
            sb.append("\nOBSERVAÇÕES\n-----------\n");
            for (String note : c.notes) sb.append("- ").append(note).append('\n');
        }
        sb.append("\nCOMO USAR EM OUTRA PEDALEIRA\n----------------------------\n");
        sb.append("Os nomes dos efeitos são os da Valeton GP-150; entre parênteses está o equipamento real de referência. ")
                .append("Na sua pedaleira, escolha o modelo baseado no mesmo equipamento e copie os valores (escala de 0 a 100, salvo indicação). ")
                .append("Tempo de delay em ms e cortes do gabinete em Hz.\n");
        return sb.toString();
    }

    private static String blockText(ToneChain.Block b, EffectCatalog catalog) {
        EffectCatalog.Effect fx = catalog.effect(b.module, b.effectName);
        StringBuilder sb = new StringBuilder();
        sb.append(moduleLabel(b.module)).append(": ").append(b.effectName);
        if (fx != null) {
            String gear = fx.gear();
            if (!gear.isEmpty()) sb.append(" (").append(gear).append(')');
            else if (!fx.type.isEmpty() && !fx.type.equalsIgnoreCase(b.effectName)) sb.append(" (").append(fx.type).append(')');
        }
        if (!b.params.isEmpty()) {
            sb.append("\n     ");
            int i = 0;
            for (Map.Entry<String, Float> e : b.params.entrySet()) {
                if (i++ > 0) sb.append(" · ");
                sb.append(e.getKey()).append(' ').append(display(fx, e.getKey(), e.getValue()));
            }
        }
        return sb.toString();
    }

    private static String display(EffectCatalog.Effect fx, String param, float value) {
        if (fx != null) for (EffectCatalog.Param p : fx.params) if (p.name.equals(param)) return p.display(value);
        return MiniJson.num(value);
    }

    public static String toJson(ToneChain c, EffectCatalog catalog) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("app", "Timbre Chord");
        root.put("song", c.songTitle);
        root.put("name", c.name);
        root.put("engine", c.engine);
        root.put("summary", c.summary);
        root.put("key", c.key);
        root.put("bpm", c.bpm >= 50 ? (double) Math.round(c.bpm) : null);
        root.put("chords", new ArrayList<Object>(c.progression));
        List<Object> blocks = new ArrayList<>();
        for (ToneChain.Block b : c.blocks.values()) {
            if (b.isNone() || "VOL".equals(b.module)) continue;
            EffectCatalog.Effect fx = catalog.effect(b.module, b.effectName);
            Map<String, Object> o = new LinkedHashMap<>();
            o.put("module", b.module);
            o.put("moduleLabel", moduleLabel(b.module));
            o.put("enabled", b.enabled);
            o.put("effect", b.effectName);
            o.put("type", fx == null ? "" : fx.type);
            o.put("basedOn", fx == null ? "" : fx.gear());
            Map<String, Object> params = new LinkedHashMap<>();
            for (Map.Entry<String, Float> e : b.params.entrySet()) params.put(e.getKey(), (double) e.getValue());
            o.put("params", params);
            blocks.add(o);
        }
        root.put("blocks", blocks);
        root.put("notes", new ArrayList<Object>(c.notes));
        return MiniJson.write(root);
    }
}
