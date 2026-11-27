package com.orleiluis.timbrechord.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Biblioteca de efeitos da Valeton GP-150 (nomes, equipamento de referência e limites de cada parâmetro). */
public final class EffectCatalog {
    public static final String[] BLOCK_ORDER = {"PRE", "WAH", "DST", "NS", "AMP", "NR", "CAB", "EQ", "MOD", "DLY", "RVB", "VOL"};

    public static final class Param {
        public final String name, showValue;
        public final int algId, widgetType;
        public final float min, max, defaultValue, step;

        Param(String name, int algId, float min, float max, float def, float step, int widgetType, String showValue) {
            this.name = name; this.algId = algId; this.min = min; this.max = max; this.defaultValue = def;
            this.step = step; this.widgetType = widgetType; this.showValue = showValue == null ? "" : showValue;
        }

        /** Valor como aparece na pedaleira: número, ou o nome da opção em chaves seletoras (Off/On, LP/HP...). */
        public String display(float v) {
            String[] options = options();
            if (options != null) {
                int i = Math.round(v - min);
                if (i >= 0 && i < options.length) return options[i];
            }
            return MiniJson.num(Math.round(v * 100) / 100.0);
        }

        private String[] options() {
            if (widgetType == 0 || showValue.isEmpty() || showValue.contains("~")) return null;
            String[] parts = showValue.contains(",") ? showValue.split(",") : showValue.split("/");
            if (parts.length < 2 || parts.length != Math.round(max - min) + 1) return null;
            for (int i = 0; i < parts.length; i++) parts[i] = parts[i].trim();
            return parts;
        }
    }

    public static final class Effect {
        public final String module, name, type, origin;
        public final long fxid;
        public final List<Param> params;

        Effect(String module, String name, String type, String origin, long fxid, List<Param> params) {
            this.module = module; this.name = name; this.type = type; this.origin = origin; this.fxid = fxid;
            this.params = Collections.unmodifiableList(params);
        }

        public boolean isNone() { return "none".equalsIgnoreCase(name); }

        /** Equipamento real em que o efeito se baseia ("" quando é um efeito original da Valeton). */
        public String gear() {
            String o = origin == null ? "" : origin.trim();
            int nl = o.indexOf('\n');
            if (nl >= 0) o = o.substring(0, nl).trim();
            if (o.isEmpty() || o.equals("/") || o.equalsIgnoreCase("Volume") || o.toLowerCase(Locale.ROOT).startsWith("original")) return "";
            for (int i = 0; i < o.length(); i++) if (o.charAt(i) > 0x2E7F) return "";
            return o;
        }
    }

    private final Map<String, Map<String, Effect>> modules = new LinkedHashMap<>();

    private EffectCatalog() {}

    public static EffectCatalog parse(String json) {
        EffectCatalog c = new EffectCatalog();
        Map<String, Object> root = MiniJson.obj(MiniJson.parse(json));
        List<Object> mods = root == null ? null : MiniJson.arr(root.get("modules"));
        if (mods == null) throw new IllegalArgumentException("catálogo sem módulos");
        for (Object mo : mods) {
            Map<String, Object> m = MiniJson.obj(mo);
            if (m == null) continue;
            String module = normalizeModule(MiniJson.str(m, "name", ""));
            Map<String, Effect> effects = new LinkedHashMap<>();
            List<Object> arr = MiniJson.arr(m.get("effects"));
            if (arr != null) for (Object eo : arr) {
                Map<String, Object> e = MiniJson.obj(eo);
                if (e == null) continue;
                List<Param> params = new ArrayList<>();
                List<Object> alg = MiniJson.arr(e.get("alg"));
                if (alg != null) for (int k = 0; k < alg.size(); k++) {
                    Map<String, Object> p = MiniJson.obj(alg.get(k));
                    if (p == null) continue;
                    params.add(new Param(MiniJson.str(p, "name", ""), (int) MiniJson.dbl(p, "algId", k),
                            (float) MiniJson.dbl(p, "min", 0), (float) MiniJson.dbl(p, "max", 100),
                            (float) MiniJson.dbl(p, "defaultValue", 0), (float) MiniJson.dbl(p, "step", 1),
                            (int) MiniJson.dbl(p, "widgetType", 0), MiniJson.str(p, "showValue", "")));
                }
                Effect fx = new Effect(module, MiniJson.str(e, "name", ""), MiniJson.str(e, "type", ""),
                        MiniJson.str(e, "origin", ""), (long) MiniJson.dbl(e, "fxid", 0), params);
                effects.put(key(fx.name), fx);
            }
            c.modules.put(module, effects);
        }
        return c;
    }

    public Effect effect(String module, String name) {
        Map<String, Effect> m = modules.get(normalizeModule(module));
        return m == null || name == null ? null : m.get(key(name));
    }

    public List<Effect> effects(String module) {
        Map<String, Effect> m = modules.get(normalizeModule(module));
        return m == null ? Collections.<Effect>emptyList() : new ArrayList<>(m.values());
    }

    /** Lista compacta para o prompt da IA (sem o módulo NS, que são capturas do próprio usuário). */
    public String compactPrompt() {
        StringBuilder sb = new StringBuilder();
        for (String module : BLOCK_ORDER) {
            if ("NS".equals(module) || "VOL".equals(module)) continue;
            sb.append(module).append(": ");
            int ix = 0;
            for (Effect e : effects(module)) {
                if (e.isNone()) continue;
                if (ix++ > 0) sb.append("; ");
                sb.append(e.name);
                String gear = e.gear();
                if (!gear.isEmpty()) sb.append(" (").append(gear).append(')');
                else if (!e.type.isEmpty()) sb.append(" (").append(e.type).append(')');
                if ("CAB".equals(module)) continue; // todos os gabinetes têm os mesmos parâmetros
                sb.append('{');
                for (int j = 0; j < e.params.size(); j++) {
                    Param p = e.params.get(j);
                    if (j > 0) sb.append(',');
                    sb.append(p.name).append(':').append(MiniJson.num(p.min)).append('-').append(MiniJson.num(p.max));
                }
                sb.append('}');
            }
            if ("CAB".equals(module)) sb.append(" | todos com {Volume:0-100,Low Cut:19-2000,High Cut:2000-20001}");
            sb.append('\n');
        }
        return sb.toString();
    }

    public static String normalizeModule(String s) {
        if (s == null) return "";
        return s.trim().toUpperCase(Locale.ROOT).replace("N->S", "NS").replace("N→S", "NS");
    }

    private static String key(String s) { return s == null ? "" : s.trim().toLowerCase(Locale.ROOT); }
}
