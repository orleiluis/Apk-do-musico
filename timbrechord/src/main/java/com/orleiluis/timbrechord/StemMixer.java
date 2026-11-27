package com.orleiluis.timbrechord;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Volumes das faixas separadas (as barras da tela). O player lê daqui a cada bloco de áudio. */
final class StemMixer {
    static final StemMixer shared = new StemMixer();

    private StemMixer() {}

    /** Nomes na tela, na ordem em que aparecem. */
    static final String[] ORDER = {"vocals", "guitar", "bass", "drums", "piano", "other"};

    static String label(String source) {
        switch (source) {
            case "vocals": return "Voz";
            case "guitar": return "Guitarra";
            case "bass": return "Baixo";
            case "drums": return "Bateria";
            case "piano": return "Piano";
            case "other": return "Outros";
            default: return source;
        }
    }

    private final Map<String, Float> gains = new HashMap<>();
    private final Set<String> muted = new HashSet<>();
    private String solo = "";

    /** Música nova: tudo em 100%. */
    synchronized void reset() {
        gains.clear();
        muted.clear();
        solo = "";
    }

    synchronized float gain(String source) {
        Float g = gains.get(source);
        return g == null ? 1f : g;
    }

    synchronized void setGain(String source, float value) { gains.put(source, Math.max(0f, Math.min(1.5f, value))); }

    synchronized boolean isMuted(String source) { return muted.contains(source); }

    synchronized void toggleMute(String source) {
        if (!muted.remove(source)) muted.add(source);
    }

    synchronized String solo() { return solo; }

    synchronized void toggleSolo(String source) { solo = solo.equals(source) ? "" : source; }

    /** Volume que vale agora para cada faixa (mudo e "só" já aplicados). */
    synchronized void effective(String[] sources, float[] out) {
        for (int i = 0; i < sources.length; i++) {
            String s = sources[i];
            if (!solo.isEmpty()) out[i] = solo.equals(s) ? gain(s) : 0f;
            else out[i] = muted.contains(s) ? 0f : gain(s);
        }
    }
}
