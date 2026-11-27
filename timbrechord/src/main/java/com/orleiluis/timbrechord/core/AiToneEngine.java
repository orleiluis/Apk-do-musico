package com.orleiluis.timbrechord.core;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Motor de timbre com IA online (opcional). Envia as medidas do áudio, os acordes e o título do vídeo;
 * a IA devolve a cadeia usando somente efeitos do catálogo. Usa a chave de API do próprio usuário.
 */
public final class AiToneEngine {
    private AiToneEngine() {}

    public static final String DEFAULT_ENDPOINT = "https://api.openai.com/v1/responses";
    public static final String DEFAULT_MODEL = "gpt-5.6-luna";

    public static ToneChain generate(EffectCatalog catalog, String endpoint, String model, String apiKey,
                                     ToneAnalyzer.Features f, int style, String songTitle, String key,
                                     List<String> progression) throws Exception {
        if (apiKey == null || apiKey.trim().isEmpty()) throw new Exception("Chave da API não configurada.");
        String url = endpoint == null || endpoint.trim().isEmpty() ? DEFAULT_ENDPOINT : endpoint.trim();
        String mdl = model == null || model.trim().isEmpty() ? DEFAULT_MODEL : model.trim();
        if (!url.toLowerCase(Locale.ROOT).startsWith("https://")) throw new Exception("O endereço da API precisa começar com https://");
        String prompt = buildPrompt(catalog, f, style, songTitle, key, progression);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", mdl);
        if (url.contains("/chat/completions")) {
            Map<String, Object> msg = new LinkedHashMap<>();
            msg.put("role", "user");
            msg.put("content", prompt);
            List<Object> messages = new ArrayList<>();
            messages.add(msg);
            body.put("messages", messages);
        } else {
            body.put("input", prompt);
            body.put("max_output_tokens", 4200.0);
        }

        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(20000);
        conn.setReadTimeout(90000);
        conn.setDoOutput(true);
        conn.setRequestProperty("Authorization", "Bearer " + apiKey.trim());
        conn.setRequestProperty("Content-Type", "application/json");
        try (OutputStream os = conn.getOutputStream()) {
            os.write(MiniJson.writeCompact(body).getBytes(StandardCharsets.UTF_8));
        }
        int code = conn.getResponseCode();
        String raw = readAll(code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream());
        if (code < 200 || code >= 300) throw new Exception("API: " + extractApiError(raw) + " (HTTP " + code + ")");

        ToneChain chain = parseResponse(raw);
        chain.engine = "IA online (" + mdl + ")";
        if (chain.name == null || chain.name.trim().isEmpty() || "Timbre Chord".equals(chain.name))
            chain.name = LocalToneEngine.shortName(songTitle, "Timbre IA");
        chain.notes.addAll(chain.normalize(catalog));
        ToneChain.Block amp = chain.block("AMP"), ns = chain.block("NS");
        if ((amp == null || amp.isNone()) && (ns == null || ns.isNone()))
            throw new Exception("A IA não devolveu um amplificador válido. Tente de novo ou use a análise local.");
        return chain;
    }

    /** Aceita tanto o formato "responses" quanto "chat/completions". */
    public static ToneChain parseResponse(String raw) throws Exception {
        Map<String, Object> resp;
        try { resp = MiniJson.obj(MiniJson.parse(raw)); } catch (RuntimeException e) { resp = null; }
        if (resp == null) throw new Exception("Resposta da API em formato inesperado.");
        String text = extractText(resp);
        if (text.isEmpty()) throw new Exception("A IA não retornou parâmetros utilizáveis.");
        Map<String, Object> j;
        try { j = MiniJson.obj(MiniJson.parse(stripToJson(text))); } catch (RuntimeException e) { j = null; }
        if (j == null) throw new Exception("A IA respondeu fora do formato combinado. Tente de novo.");
        return ToneChain.fromJson(j);
    }

    static String buildPrompt(EffectCatalog catalog, ToneAnalyzer.Features f, int style, String songTitle,
                              String key, List<String> progression) {
        StringBuilder sb = new StringBuilder();
        sb.append("Você é especialista em timbres de guitarra. Monte a cadeia de efeitos para tocar a música abaixo, ")
                .append("usando SOMENTE efeitos do catálogo da Valeton GP-150 listado no fim (o nome entre parênteses é o equipamento real de referência). ")
                .append("Se você conhece a gravação original pelo título, use o que sabe sobre o equipamento e o timbre do guitarrista; ")
                .append("as medidas do áudio servem para ajustar equalização, ganho e ambiência. ")
                .append("Desligue ou omita blocos desnecessários. Gate (NR) moderado. Não use o módulo NS. ")
                .append("Use os nomes de efeitos e de parâmetros exatamente como no catálogo e valores dentro dos limites. ")
                .append("Retorne SOMENTE JSON válido, sem markdown, no formato: ")
                .append("{\"name\":\"nome curto\",\"summary\":\"resumo curto em português do timbre e do porquê das escolhas\",\"blocks\":[")
                .append("{\"module\":\"AMP\",\"effect\":\"UK 800\",\"enabled\":true,\"params\":{\"Gain\":60,\"Bass\":50}}]}. ")
                .append("Módulos possíveis, na ordem do sinal: PRE, WAH, DST, AMP, NR, CAB, EQ, MOD, DLY, RVB.\n\n");
        sb.append("Música: ").append(songTitle == null || songTitle.trim().isEmpty() ? "(desconhecido)" : songTitle.trim()).append('\n');
        if (style != LocalToneEngine.AUTO && style >= 0 && style < LocalToneEngine.STYLE_LABELS.length)
            sb.append("Estilo pedido pelo usuário: ").append(LocalToneEngine.STYLE_LABELS[style]).append('\n');
        if (key != null && !key.isEmpty()) sb.append("Tom estimado: ").append(key).append('\n');
        if (f != null && f.bpm >= 50) sb.append(String.format(Locale.US, "Andamento estimado: %.0f BPM (use para o tempo do delay, em ms)\n", f.bpm));
        if (progression != null && !progression.isEmpty()) {
            sb.append("Acordes detectados: ");
            for (int i = 0; i < Math.min(progression.size(), 48); i++) sb.append(i > 0 ? " " : "").append(progression.get(i));
            sb.append('\n');
        }
        if (f != null && f.enough()) {
            sb.append("Medidas do áudio (mixagem inteira, não a guitarra isolada): ").append(f.describe()).append('\n');
            sb.append("Energia por faixa em dB relativa ao total:");
            for (int b = 0; b < ToneAnalyzer.BANDS; b++)
                sb.append(String.format(Locale.US, " %.0f-%.0fHz %.1f;", ToneAnalyzer.EDGES[b], ToneAnalyzer.EDGES[b + 1], f.bandDb[b]));
            sb.append('\n');
        } else {
            sb.append("Sem medidas de áudio suficientes: baseie-se no título e no estilo.\n");
        }
        sb.append("\nCatálogo (efeito (referência){parâmetro:mín-máx}):\n").append(catalog.compactPrompt());
        return sb.toString();
    }

    private static String extractText(Map<String, Object> resp) {
        List<Object> out = MiniJson.arr(resp.get("output"));
        if (out != null) {
            StringBuilder sb = new StringBuilder();
            for (Object io : out) {
                Map<String, Object> item = MiniJson.obj(io);
                List<Object> content = item == null ? null : MiniJson.arr(item.get("content"));
                if (content == null) continue;
                for (Object co : content) {
                    Map<String, Object> c = MiniJson.obj(co);
                    if (c != null && "output_text".equals(MiniJson.str(c, "type", ""))) sb.append(MiniJson.str(c, "text", ""));
                }
            }
            if (sb.length() > 0) return sb.toString();
        }
        List<Object> choices = MiniJson.arr(resp.get("choices"));
        if (choices != null && !choices.isEmpty()) {
            Map<String, Object> first = MiniJson.obj(choices.get(0));
            Map<String, Object> msg = first == null ? null : MiniJson.obj(first.get("message"));
            if (msg != null) return MiniJson.str(msg, "content", "");
        }
        return MiniJson.str(resp, "output_text", "");
    }

    /** Remove cercas de markdown e texto em volta, ficando só o objeto JSON. */
    static String stripToJson(String t) {
        String s = t.trim();
        int a = s.indexOf('{'), b = s.lastIndexOf('}');
        return a >= 0 && b > a ? s.substring(a, b + 1) : s;
    }

    private static String extractApiError(String raw) {
        try {
            Map<String, Object> j = MiniJson.obj(MiniJson.parse(raw));
            Map<String, Object> e = j == null ? null : MiniJson.obj(j.get("error"));
            if (e != null) return MiniJson.str(e, "message", "erro desconhecido");
        } catch (RuntimeException ignored) { }
        String r = raw == null ? "" : raw.trim();
        if (r.isEmpty()) return "erro desconhecido";
        return r.length() > 300 ? r.substring(0, 300) : r;
    }

    private static String readAll(InputStream in) throws Exception {
        if (in == null) return "";
        StringBuilder s = new StringBuilder();
        try (BufferedReader b = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String l;
            while ((l = b.readLine()) != null) s.append(l).append('\n');
        }
        return s.toString();
    }
}
