package com.orleiluis.timbrechord.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Cadeia de timbre: um bloco por módulo, na ordem do sinal, com efeito e parâmetros. */
public final class ToneChain {
    public String name = "Timbre Chord";
    public String summary = "";
    /** "Análise do áudio" ou "IA online". */
    public String engine = "Análise do áudio";
    public String songTitle = "";
    public String key = "";
    public float bpm;
    public final List<String> progression = new ArrayList<>();
    public final List<String> notes = new ArrayList<>();
    public final LinkedHashMap<String, Block> blocks = new LinkedHashMap<>();

    public static final class Block {
        public final String module;
        public boolean enabled;
        public String effectName;
        public final LinkedHashMap<String, Float> params = new LinkedHashMap<>();

        Block(String module, boolean enabled, String effectName) {
            this.module = module; this.enabled = enabled; this.effectName = effectName;
        }

        public Block p(String key, double value) { params.put(key, (float) value); return this; }

        public boolean isNone() { return effectName == null || effectName.trim().isEmpty() || "none".equalsIgnoreCase(effectName.trim()); }
    }

    public ToneChain() {
        for (String m : EffectCatalog.BLOCK_ORDER) blocks.put(m, new Block(m, false, "None"));
        blocks.put("VOL", new Block("VOL", true, "Volume").p("Volume", 100));
    }

    public Block block(String module) { return blocks.get(EffectCatalog.normalizeModule(module)); }

    /** Define o efeito de um módulo (substitui o que havia). */
    public Block set(String module, String effectName, boolean enabled) {
        String m = EffectCatalog.normalizeModule(module);
        Block b = new Block(m, enabled, effectName);
        blocks.put(m, b);
        return b;
    }

    /**
     * Confere a cadeia contra o catálogo: troca efeitos inexistentes por "None", completa os parâmetros
     * que faltam com o valor padrão e prende cada valor dentro do limite real do efeito.
     * Devolve avisos sobre o que precisou ser corrigido.
     */
    public List<String> normalize(EffectCatalog catalog) {
        List<String> warnings = new ArrayList<>();
        LinkedHashMap<String, Block> ordered = new LinkedHashMap<>();
        for (String m : EffectCatalog.BLOCK_ORDER) {
            Block b = blocks.get(m);
            if (b == null) b = new Block(m, false, "None");
            if ("VOL".equals(m)) {
                b.enabled = true;
                if (b.isNone()) b.effectName = "Volume";
            }
            EffectCatalog.Effect fx = b.isNone() ? null : catalog.effect(m, b.effectName);
            if (fx == null || fx.isNone()) {
                if (!b.isNone()) warnings.add(m + ": efeito \"" + b.effectName + "\" não existe na GP-150 e foi removido.");
                ordered.put(m, new Block(m, false, "None"));
                continue;
            }
            Block clean = new Block(m, b.enabled, fx.name);
            for (EffectCatalog.Param pm : fx.params) {
                Float given = find(b.params, pm.name);
                float v = given == null || given.isNaN() ? pm.defaultValue : given;
                v = Math.max(pm.min, Math.min(pm.max, v));
                if (pm.step >= 1) v = Math.round(v);
                clean.params.put(pm.name, v);
            }
            ordered.put(m, clean);
        }
        // SnapTone (NS) substitui amplificador e gabinete na GP-150
        Block ns = ordered.get("NS");
        if (ns != null && ns.enabled && !ns.isNone()) {
            ordered.get("AMP").enabled = false;
            ordered.get("CAB").enabled = false;
        }
        blocks.clear();
        blocks.putAll(ordered);
        if (name == null || name.trim().isEmpty()) name = "Timbre Chord";
        if (summary == null) summary = "";
        return warnings;
    }

    private static Float find(Map<String, Float> map, String name) {
        Float v = findIgnoreCase(map, name);
        if (v != null) return v;
        String n = name.toLowerCase(Locale.ROOT), alias = null;
        if (n.equals("vol")) alias = "volume"; else if (n.equals("volume")) alias = "vol";
        else if (n.equals("f.back")) alias = "feedback"; else if (n.equals("feedback")) alias = "f.back";
        else if (n.equals("mid")) alias = "middle"; else if (n.equals("middle")) alias = "mid";
        else if (n.equals("presence")) alias = "pres"; else if (n.equals("pres")) alias = "presence";
        else if (n.equals("thre")) alias = "threshold"; else if (n.equals("threshold")) alias = "thre";
        return alias == null ? null : findIgnoreCase(map, alias);
    }

    private static Float findIgnoreCase(Map<String, Float> map, String key) {
        for (Map.Entry<String, Float> e : map.entrySet()) if (e.getKey().trim().equalsIgnoreCase(key)) return e.getValue();
        return null;
    }

    /** Lê a resposta da IA: {"name","summary","blocks":[{"module","effect","enabled","params":{}}]}. */
    public static ToneChain fromJson(Map<String, Object> j) {
        ToneChain c = new ToneChain();
        c.name = MiniJson.str(j, "name", c.name);
        c.summary = MiniJson.str(j, "summary", "");
        List<Object> arr = MiniJson.arr(j.get("blocks"));
        if (arr != null) for (Object o : arr) {
            Map<String, Object> b = MiniJson.obj(o);
            if (b == null) continue;
            String module = EffectCatalog.normalizeModule(MiniJson.str(b, "module", MiniJson.str(b, "moduleType", "")));
            if (!c.blocks.containsKey(module)) continue;
            String effect = MiniJson.str(b, "effect", MiniJson.str(b, "effectName", "None"));
            Block block = c.set(module, effect, MiniJson.bool(b, "enabled", true));
            Map<String, Object> params = MiniJson.obj(b.get("params"));
            if (params != null) for (Map.Entry<String, Object> e : params.entrySet()) {
                double v = MiniJson.toDouble(e.getValue(), Double.NaN);
                if (!Double.isNaN(v)) block.p(e.getKey(), v);
            }
        }
        return c;
    }
}
