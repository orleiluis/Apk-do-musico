package com.orleiluis.timbrechord.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * JSON mínimo, sem dependências, para o motor rodar igual no Android e nos testes de JVM.
 * parse() devolve Map (LinkedHashMap), List, String, Double, Boolean ou null.
 */
public final class MiniJson {
    private MiniJson() {}

    public static Object parse(String text) {
        if (text == null) throw new IllegalArgumentException("JSON vazio");
        Parser p = new Parser(text);
        p.ws();
        Object v = p.value();
        p.ws();
        if (p.i != p.s.length()) throw p.err("conteúdo sobrando");
        return v;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> obj(Object o) {
        return o instanceof Map ? (Map<String, Object>) o : null;
    }

    @SuppressWarnings("unchecked")
    public static List<Object> arr(Object o) {
        return o instanceof List ? (List<Object>) o : null;
    }

    public static String str(Map<String, Object> m, String key, String fallback) {
        if (m == null) return fallback;
        Object v = m.get(key);
        if (v == null) return fallback;
        if (v instanceof Double) return num(((Double) v));
        return String.valueOf(v);
    }

    public static double dbl(Map<String, Object> m, String key, double fallback) {
        if (m == null) return fallback;
        return toDouble(m.get(key), fallback);
    }

    public static double toDouble(Object v, double fallback) {
        if (v instanceof Double) return (Double) v;
        if (v instanceof Boolean) return ((Boolean) v) ? 1 : 0;
        if (v instanceof String) {
            String s = ((String) v).trim();
            if (s.equalsIgnoreCase("on") || s.equalsIgnoreCase("true")) return 1;
            if (s.equalsIgnoreCase("off") || s.equalsIgnoreCase("false")) return 0;
            try { return Double.parseDouble(s.replace(',', '.')); } catch (NumberFormatException e) { return fallback; }
        }
        return fallback;
    }

    public static boolean bool(Map<String, Object> m, String key, boolean fallback) {
        if (m == null) return fallback;
        Object v = m.get(key);
        if (v instanceof Boolean) return (Boolean) v;
        if (v instanceof Double) return ((Double) v) != 0;
        if (v instanceof String) {
            String s = ((String) v).trim().toLowerCase(Locale.ROOT);
            if (s.equals("true") || s.equals("on") || s.equals("1")) return true;
            if (s.equals("false") || s.equals("off") || s.equals("0")) return false;
        }
        return fallback;
    }

    // ---------- escrita ----------

    public static String write(Object v) {
        StringBuilder sb = new StringBuilder();
        write(sb, v, 0, true);
        return sb.toString();
    }

    public static String writeCompact(Object v) {
        StringBuilder sb = new StringBuilder();
        write(sb, v, 0, false);
        return sb.toString();
    }

    private static void write(StringBuilder sb, Object v, int depth, boolean pretty) {
        if (v == null) { sb.append("null"); return; }
        if (v instanceof String) { quote(sb, (String) v); return; }
        if (v instanceof Boolean) { sb.append(v); return; }
        if (v instanceof Number) { sb.append(num(((Number) v).doubleValue())); return; }
        if (v instanceof Map) {
            Map<?, ?> m = (Map<?, ?>) v;
            if (m.isEmpty()) { sb.append("{}"); return; }
            sb.append('{');
            int k = 0;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (k++ > 0) sb.append(',');
                nl(sb, depth + 1, pretty);
                quote(sb, String.valueOf(e.getKey()));
                sb.append(pretty ? ": " : ":");
                write(sb, e.getValue(), depth + 1, pretty);
            }
            nl(sb, depth, pretty);
            sb.append('}');
            return;
        }
        if (v instanceof List) {
            List<?> l = (List<?>) v;
            if (l.isEmpty()) { sb.append("[]"); return; }
            sb.append('[');
            for (int k = 0; k < l.size(); k++) {
                if (k > 0) sb.append(',');
                nl(sb, depth + 1, pretty);
                write(sb, l.get(k), depth + 1, pretty);
            }
            nl(sb, depth, pretty);
            sb.append(']');
            return;
        }
        quote(sb, String.valueOf(v));
    }

    private static void nl(StringBuilder sb, int depth, boolean pretty) {
        if (!pretty) return;
        sb.append('\n');
        for (int i = 0; i < depth; i++) sb.append("  ");
    }

    public static String num(double d) {
        if (Double.isNaN(d) || Double.isInfinite(d)) return "0";
        if (Math.abs(d - Math.rint(d)) < 1e-9 && Math.abs(d) < 1e15) return String.valueOf((long) Math.rint(d));
        String s = String.format(Locale.US, "%.3f", d);
        while (s.endsWith("0")) s = s.substring(0, s.length() - 1);
        if (s.endsWith(".")) s = s.substring(0, s.length() - 1);
        return s;
    }

    private static void quote(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) sb.append(String.format(Locale.US, "\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        sb.append('"');
    }

    // ---------- leitura ----------

    private static final class Parser {
        final String s;
        int i;

        Parser(String s) { this.s = s; }

        IllegalArgumentException err(String m) {
            return new IllegalArgumentException("JSON inválido (" + m + ") na posição " + i);
        }

        void ws() {
            while (i < s.length()) {
                char c = s.charAt(i);
                if (c == ' ' || c == '\n' || c == '\r' || c == '\t' || c == '﻿') i++;
                else break;
            }
        }

        Object value() {
            if (i >= s.length()) throw err("fim inesperado");
            char c = s.charAt(i);
            if (c == '{') return object();
            if (c == '[') return array();
            if (c == '"') return string();
            if (s.startsWith("true", i)) { i += 4; return Boolean.TRUE; }
            if (s.startsWith("false", i)) { i += 5; return Boolean.FALSE; }
            if (s.startsWith("null", i)) { i += 4; return null; }
            return number();
        }

        Map<String, Object> object() {
            Map<String, Object> m = new LinkedHashMap<>();
            i++;
            ws();
            if (i < s.length() && s.charAt(i) == '}') { i++; return m; }
            while (true) {
                ws();
                if (i >= s.length() || s.charAt(i) != '"') throw err("esperava chave");
                String k = string();
                ws();
                if (i >= s.length() || s.charAt(i) != ':') throw err("esperava ':'");
                i++;
                ws();
                m.put(k, value());
                ws();
                if (i >= s.length()) throw err("objeto não fechado");
                char c = s.charAt(i++);
                if (c == '}') return m;
                if (c != ',') throw err("esperava ',' ou '}'");
            }
        }

        List<Object> array() {
            List<Object> l = new ArrayList<>();
            i++;
            ws();
            if (i < s.length() && s.charAt(i) == ']') { i++; return l; }
            while (true) {
                ws();
                l.add(value());
                ws();
                if (i >= s.length()) throw err("lista não fechada");
                char c = s.charAt(i++);
                if (c == ']') return l;
                if (c != ',') throw err("esperava ',' ou ']'");
            }
        }

        String string() {
            StringBuilder sb = new StringBuilder();
            i++;
            while (true) {
                if (i >= s.length()) throw err("texto não fechado");
                char c = s.charAt(i++);
                if (c == '"') return sb.toString();
                if (c != '\\') { sb.append(c); continue; }
                if (i >= s.length()) throw err("escape incompleto");
                char e = s.charAt(i++);
                switch (e) {
                    case 'n': sb.append('\n'); break;
                    case 'r': sb.append('\r'); break;
                    case 't': sb.append('\t'); break;
                    case 'b': sb.append('\b'); break;
                    case 'f': sb.append('\f'); break;
                    case 'u':
                        if (i + 4 > s.length()) throw err("unicode incompleto");
                        try { sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16)); }
                        catch (NumberFormatException ex) { throw err("unicode inválido"); }
                        i += 4;
                        break;
                    default: sb.append(e);
                }
            }
        }

        Double number() {
            int start = i;
            while (i < s.length()) {
                char c = s.charAt(i);
                if ((c >= '0' && c <= '9') || c == '-' || c == '+' || c == '.' || c == 'e' || c == 'E') i++;
                else break;
            }
            if (start == i) throw err("valor inesperado");
            try { return Double.parseDouble(s.substring(start, i)); }
            catch (NumberFormatException e) { throw err("número inválido"); }
        }
    }
}
