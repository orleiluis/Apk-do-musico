package com.orleiluis.timbrechord.core;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OnnxTensorLike;
import ai.onnxruntime.OnnxValue;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;

/**
 * A rede de separação rodando no ONNX Runtime, no próprio aparelho, dividida em partes que rodam uma
 * depois da outra. O ONNX Runtime só devolve a memória de trabalho quando uma execução termina: a rede
 * inteira de uma vez passava de 2 GB por trecho e o Android fechava o app. Em partes, cada uma devolve
 * a sua memória, e só os tensores que passam para as partes seguintes ficam guardados (até a última
 * que os usa).
 *
 * Entradas e saídas finais ficam em buffers fixos fora do heap do Java (a saída tem dezenas de MB).
 */
public final class OrtStemModel implements StemModel {
    public static final String MIX = "mix", SPEC = "mag", FREQ = "freq", TIME = "time";

    /** Uma parte da rede: arquivo e os nomes dos tensores que entram e saem. */
    public static final class Part {
        public final String file;
        public final String[] inputs, outputs;

        public Part(String file, String[] inputs, String[] outputs) {
            this.file = file;
            this.inputs = inputs;
            this.outputs = outputs;
        }
    }

    private final OrtSession[] sessions;
    private final Part[] parts;
    private final int[] lastUse;           // por parte: até onde cada saída é usada
    private final Map<String, Integer> lastUseOf = new HashMap<>();
    private final FloatBuffer mix, spec, freq, time;
    private final OnnxTensor mixTensor, specTensor, freqTensor, timeTensor;

    /** Rede inteira num arquivo só (modelos antigos). */
    public OrtStemModel(String modelPath, int sources, int segment, int bins, int frames, int threads) throws OrtException {
        this(modelPath, sources, segment, bins, frames, threads, false);
    }

    public OrtStemModel(String modelPath, int sources, int segment, int bins, int frames, int threads, boolean lowMemory) throws OrtException {
        this(new File(modelPath).getParentFile(), new Part[]{new Part(new File(modelPath).getName(),
                new String[]{MIX, SPEC}, new String[]{FREQ, TIME})}, sources, segment, bins, frames, threads, lowMemory);
    }

    /** lowMemory: sem a reserva de memória do ONNX Runtime (devolve a memória ao fim de cada execução). */
    public OrtStemModel(File dir, Part[] parts, int sources, int segment, int bins, int frames, int threads, boolean lowMemory) throws OrtException {
        OrtEnvironment env = OrtEnvironment.getEnvironment();
        this.parts = parts;
        sessions = new OrtSession[parts.length];
        try {
            for (int k = 0; k < parts.length; k++) {
                OrtSession.SessionOptions options = new OrtSession.SessionOptions();
                options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
                if (threads > 0) options.setIntraOpNumThreads(threads);
                if (lowMemory) {
                    options.setCPUArenaAllocator(false);
                    options.setMemoryPatternOptimization(false);
                }
                sessions[k] = env.createSession(new File(dir, parts[k].file).getPath(), options);
            }
        } catch (OrtException | RuntimeException e) {
            closeSessions();
            throw e;
        }
        for (int k = 0; k < parts.length; k++)
            for (String in : parts[k].inputs) lastUseOf.put(in, k);
        lastUse = new int[parts.length];
        for (int k = 0; k < parts.length; k++) {
            int max = k;
            for (String out : parts[k].outputs) {
                Integer u = lastUseOf.get(out);
                if (u != null) max = Math.max(max, u);
            }
            lastUse[k] = max;
        }
        mix = direct(2L * segment);
        spec = direct(4L * bins * frames);
        freq = direct((long) sources * 4 * bins * frames);
        time = direct((long) sources * 2 * segment);
        mixTensor = OnnxTensor.createTensor(env, mix, new long[]{1, 2, segment});
        specTensor = OnnxTensor.createTensor(env, spec, new long[]{1, 4, bins, frames});
        freqTensor = OnnxTensor.createTensor(env, freq, new long[]{1, sources, 4, bins, frames});
        timeTensor = OnnxTensor.createTensor(env, time, new long[]{1, sources, 2, segment});
    }

    public int partCount() { return parts.length; }

    /** As partes descritas no JSON do modelo; sem "parts", a rede inteira num arquivo (campo "graph"). */
    public static Part[] partsFrom(Map<String, Object> info) {
        List<Object> list = info.get("parts") == null ? null : MiniJson.arr(info.get("parts"));
        if (list == null || list.isEmpty())
            return new Part[]{new Part(MiniJson.str(info, "graph", "htdemucs_6s.onnx"), new String[]{MIX, SPEC}, new String[]{FREQ, TIME})};
        Part[] out = new Part[list.size()];
        for (int i = 0; i < out.length; i++) {
            Map<String, Object> p = MiniJson.obj(list.get(i));
            out[i] = new Part(MiniJson.str(p, "file", ""), strings(p.get("inputs")), strings(p.get("outputs")));
        }
        return out;
    }

    private static String[] strings(Object o) {
        List<Object> l = o == null ? new ArrayList<Object>() : MiniJson.arr(o);
        String[] s = new String[l.size()];
        for (int i = 0; i < s.length; i++) s[i] = String.valueOf(l.get(i));
        return s;
    }

    private static FloatBuffer direct(long count) {
        return ByteBuffer.allocateDirect((int) (count * 4)).order(ByteOrder.nativeOrder()).asFloatBuffer();
    }

    @Override public FloatBuffer mixInput() { return mix; }

    @Override public FloatBuffer specInput() { return spec; }

    @Override public FloatBuffer freqOutput() { return freq; }

    @Override public FloatBuffer timeOutput() { return time; }

    @Override
    public void run() throws OrtException {
        Map<String, OnnxValue> values = new HashMap<>();
        values.put(MIX, mixTensor);
        values.put(SPEC, specTensor);
        // resultado de cada parte e até qual parte ele precisa ficar aberto
        List<OrtSession.Result> open = new ArrayList<>();
        List<Integer> openUntil = new ArrayList<>();
        try {
            for (int k = 0; k < parts.length; k++) {
                Part p = parts[k];
                Map<String, OnnxTensorLike> in = new HashMap<>();
                for (String name : p.inputs) {
                    OnnxValue v = values.get(name);
                    if (!(v instanceof OnnxTensorLike)) throw new IllegalStateException("a parte " + k + " precisa de " + name);
                    in.put(name, (OnnxTensorLike) v);
                }
                // as saídas finais vão direto para os nossos buffers; as outras ficam com o ONNX Runtime
                Map<String, OnnxValue> pinned = new HashMap<>();
                Set<String> requested = new HashSet<>();
                for (String name : p.outputs) {
                    if (FREQ.equals(name)) pinned.put(name, freqTensor);
                    else if (TIME.equals(name)) pinned.put(name, timeTensor);
                    else requested.add(name);
                }
                OrtSession.Result r = sessions[k].run(in, requested, pinned);
                for (String name : requested) values.put(name, r.get(name).get());
                for (Map.Entry<String, OnnxValue> e : pinned.entrySet()) values.put(e.getKey(), e.getValue());
                open.add(r);
                openUntil.add(lastUse[k]);
                // libera o que nenhuma parte seguinte usa
                for (int i = 0; i < open.size(); i++) {
                    if (open.get(i) != null && openUntil.get(i) <= k) {
                        OrtSession.Result done = open.get(i);
                        for (Map.Entry<String, OnnxValue> e : done) values.remove(e.getKey());
                        done.close();
                        open.set(i, null);
                    }
                }
            }
        } finally {
            for (OrtSession.Result r : open) if (r != null) r.close();
        }
    }

    private void closeSessions() {
        for (OrtSession s : sessions) {
            if (s == null) continue;
            try { s.close(); } catch (OrtException ignored) { }
        }
    }

    @Override
    public void close() {
        mixTensor.close();
        specTensor.close();
        freqTensor.close();
        timeTensor.close();
        closeSessions();
    }
}
