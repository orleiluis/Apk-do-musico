package com.orleiluis.timbrechord.core;

import java.util.Locale;

/**
 * Motor de timbre local (sem internet). A saturação estimada escolhe a família (limpo, crunch, drive,
 * high gain); dentro da família, o caráter medido (brilhante/escuro, médios à frente/recuados, graves)
 * escolhe o amplificador e o gabinete; equalização, ganho, gate e reverb saem das mesmas medidas e do andamento.
 */
public final class LocalToneEngine {
    private LocalToneEngine() {}

    public static final int AUTO = 0, CLEAN = 1, CRUNCH = 2, DRIVE = 3, HIGH_GAIN = 4, LEAD = 5;
    public static final String[] STYLE_LABELS = {"Automático (pelo áudio)", "Limpo", "Crunch", "Drive / rock", "High gain / metal", "Solo (lead)"};

    /** Limites da saturação estimada entre limpo, crunch, drive e high gain. */
    static final float CRUNCH_FROM = 0.50f, DRIVE_FROM = 0.68f, HIGH_GAIN_FROM = 0.85f;

    public static ToneChain generate(EffectCatalog catalog, ToneAnalyzer.Features f, int style, String songTitle) {
        ToneChain c = new ToneChain();
        boolean measured = f.enough();
        int resolved = style;
        if (style == AUTO) {
            int hinted = styleFromTitle(songTitle);
            if (hinted != AUTO) {
                resolved = hinted;
                c.notes.add("Estilo escolhido pelo título do vídeo.");
            } else if (!measured) {
                resolved = CRUNCH;
                c.notes.add("Pouco áudio analisado (menos de 8 s): usei um crunch neutro como ponto de partida. Deixe a música tocar mais e gere de novo.");
            } else {
                resolved = f.drive < CRUNCH_FROM ? CLEAN : f.drive < DRIVE_FROM ? CRUNCH : f.drive < HIGH_GAIN_FROM ? DRIVE : HIGH_GAIN;
                for (float edge : new float[]{CRUNCH_FROM, DRIVE_FROM, HIGH_GAIN_FROM}) {
                    if (Math.abs(f.drive - edge) < 0.05f) {
                        c.notes.add("A saturação estimada ficou no limite entre dois estilos. Se o resultado soar limpo ou sujo demais, escolha o estilo na lista e gere de novo.");
                        break;
                    }
                }
            }
        }

        // caráter do som: sem áudio, tudo no meio
        float bright = measured ? f.brightness : 0.5f, bassIx = measured ? f.bass : 0.5f, midIx = measured ? f.mids : 0.5f;
        float pres = measured ? f.presence : 0.5f, drive = measured ? f.drive : 0.5f;
        boolean brightSound = bright > 0.62f, darkSound = bright < 0.38f;
        boolean midForward = midIx > 0.62f, scooped = midIx < 0.38f, heavyLow = bassIx > 0.65f;

        // equalização do amplificador a partir do equilíbrio medido
        float bass = 38 + 30 * bassIx, mid = 38 + 30 * midIx, treble = 40 + 28 * bright, presence = 32 + 36 * pres;
        float highCut = 6000 + 5000 * bright;
        float quarterMs = f.bpm >= 50 ? 60000f / f.bpm : 0;
        // ambiência: música lenta pede cauda mais longa; quanto mais ganho, menos reverb
        float slow = f.bpm >= 50 ? clamp01((130 - f.bpm) / 70f) : 0.5f;

        String label, why;
        switch (resolved) {
            case CLEAN: {
                label = "Limpo";
                float gain = 34 + 14 * clamp01(drive / CRUNCH_FROM);
                c.set("PRE", "COMP", true).p("Sustain", 30 + 15 * (1 - clamp01(drive / CRUNCH_FROM))).p("Volume", 55);
                if (brightSound) {
                    why = "Fender Twin, porque o som é brilhante";
                    c.set("AMP", "Dark Twin", true).p("Gain", gain).p("Volume", 60).p("Bass", bass).p("Middle", mid).p("Treble", treble).p("Bright", 1);
                    c.set("CAB", "Twin 2x12", true).p("Volume", 60).p("Low Cut", 80).p("High Cut", highCut + 1500);
                } else if (midForward && !darkSound) {
                    why = "Vox AC30, porque os médios estão à frente";
                    c.set("AMP", "Foxy 30N", true).p("Gain", gain + 4).p("Tone Cut", 60 - 30 * bright).p("Volume", 60).p("Bright", 0);
                    c.set("CAB", "Foxy 2x12", true).p("Volume", 60).p("Low Cut", 80).p("High Cut", highCut + 1000);
                } else if (darkSound) {
                    why = "Fender Tweed, porque o som é escuro e encorpado";
                    c.set("AMP", "Tweedy", true).p("Gain", gain + 2).p("Tone", 40 + 30 * bright).p("Volume", 60);
                    c.set("CAB", "TWD LUX 1x12", true).p("Volume", 60).p("Low Cut", 70).p("High Cut", highCut + 1000);
                } else {
                    why = "Roland JC-120, limpo e equilibrado";
                    c.set("AMP", "J-120 CL", true).p("Gain", gain + 6).p("Bass", bass).p("Middle", mid).p("Treble", treble).p("Bright", 0);
                    c.set("CAB", "J-120 2x12", true).p("Volume", 60).p("Low Cut", 80).p("High Cut", highCut + 1500);
                }
                c.set("MOD", "G-Chorus", false).p("Depth", 35).p("Rate", 0.8).p("Volume", 50).p("Sync", 0);
                if (slow > 0.6f) c.set("RVB", "Hall", true).p("Mix", 18 + 12 * slow).p("Pre Delay", 20).p("Decay", 40 + 20 * slow).p("Trail", 1);
                else c.set("RVB", "Plate", true).p("Mix", 14 + 12 * slow).p("Decay", 32 + 20 * slow).p("High Damp", 60 - 25 * bright).p("Trail", 1);
                c.notes.add("O chorus está na cadeia, mas desligado: ligue se a música tiver esse efeito.");
                break;
            }
            case CRUNCH: {
                label = "Crunch";
                float pos = clamp01((drive - CRUNCH_FROM) / (DRIVE_FROM - CRUNCH_FROM));
                float gain = 46 + 18 * pos;
                if (midForward && !darkSound) {
                    why = "Vox AC30 Top Boost, porque os médios estão à frente";
                    c.set("AMP", "Foxy 30TB", true).p("Gain", gain).p("Tone Cut", 55 - 25 * bright).p("Volume", 55).p("Bass", bass).p("Treble", treble).p("Char", 0);
                    c.set("CAB", "Foxy 2x12", true).p("Volume", 60).p("Low Cut", 90).p("High Cut", highCut + 500);
                } else if (brightSound) {
                    why = "Matchless, porque o som é brilhante e aberto";
                    c.set("AMP", "Match OD", true).p("Gain", gain).p("Presence", presence).p("Volume", 55).p("Bass", bass).p("Middle", mid).p("Treble", treble);
                    c.set("CAB", "MATT 2x12", true).p("Volume", 60).p("Low Cut", 90).p("High Cut", highCut + 500);
                } else if (darkSound && heavyLow) {
                    why = "Fender Bassman, porque o som é grave e escuro";
                    c.set("AMP", "Bellman 59B", true).p("Gain", gain).p("Presence", presence).p("Volume", 55).p("Bass", bass - 6).p("Middle", mid).p("Treble", treble);
                    c.set("CAB", "Bellman 4x10", true).p("Volume", 60).p("Low Cut", 95).p("High Cut", highCut + 500);
                } else {
                    why = "Marshall JMP 50, crunch clássico";
                    c.set("AMP", "UK 50", true).p("Gain", gain + 2).p("Presence", presence).p("Volume", 55).p("Bass", bass).p("Middle", mid + 4).p("Treble", treble);
                    c.set("CAB", "UK Vintage 4x12", true).p("Volume", 60).p("Low Cut", 90).p("High Cut", highCut + 500);
                }
                c.set("NR", "Gate 1", true).p("Threshold", 18 + 8 * pos);
                c.set("RVB", "Room", true).p("Mix", 10 + 10 * slow).p("Pre Delay", 10).p("Decay", 28 + 16 * slow).p("Trail", 1);
                break;
            }
            case DRIVE: {
                label = "Drive / rock";
                float pos = clamp01((drive - DRIVE_FROM) / (HIGH_GAIN_FROM - DRIVE_FROM));
                float gain = 54 + 16 * pos;
                if (heavyLow && !brightSound) c.set("DST", "Force", true).p("Gain", 22).p("Tone", 48 + 14 * bright).p("Volume", 62).p("Mode", 0);
                else if (brightSound) c.set("DST", "Super OD", true).p("Gain", 22).p("Tone", 44 + 12 * bright).p("Volume", 65);
                else c.set("DST", "Green OD", true).p("Gain", 25).p("Tone", 50 + 12 * bright).p("Volume", 65);
                if (brightSound && !midForward) {
                    why = "Friedman BE, porque o som é brilhante e moderno";
                    c.set("AMP", "Flagman 1", true).p("Gain", gain - 4).p("Presence", presence).p("Volume", 55).p("Bass", bass).p("Middle", mid).p("Treble", treble);
                    c.set("CAB", "Flagman 4x12", true).p("Volume", 60).p("Low Cut", 95).p("High Cut", highCut);
                } else if (midForward) {
                    why = "Marshall Plexi, porque os médios estão à frente";
                    c.set("AMP", "UK SLP", true).p("Gain", gain + 6).p("Presence", presence).p("Volume", 55).p("Bass", bass).p("Middle", mid + 4).p("Treble", treble);
                    c.set("CAB", "UK Vintage 4x12", true).p("Volume", 60).p("Low Cut", 95).p("High Cut", highCut);
                } else if (darkSound) {
                    why = "Soldano SLO, porque o som é escuro e encorpado";
                    c.set("AMP", "Solo100 OD", true).p("Gain", gain).p("Presence", presence).p("Volume", 55).p("Bass", bass - 4).p("Middle", mid + 4).p("Treble", treble);
                    c.set("CAB", "Solo 4x12", true).p("Volume", 60).p("Low Cut", 95).p("High Cut", highCut);
                } else {
                    why = "Marshall JCM800, rock clássico";
                    c.set("AMP", "UK 800", true).p("Gain", gain + 2).p("Presence", presence).p("Volume", 55).p("Bass", bass).p("Middle", mid + 4).p("Treble", treble);
                    c.set("CAB", "UK Basket 4x12", true).p("Volume", 60).p("Low Cut", 95).p("High Cut", highCut);
                }
                c.set("NR", "Gate 1", true).p("Threshold", 26 + 10 * pos);
                c.set("RVB", "Room", true).p("Mix", 7 + 9 * slow).p("Pre Delay", 10).p("Decay", 24 + 14 * slow).p("Trail", 1);
                break;
            }
            case HIGH_GAIN: {
                label = "High gain / metal";
                float pos = clamp01((drive - HIGH_GAIN_FROM) / (1 - HIGH_GAIN_FROM));
                float gain = 58 + 12 * pos;
                c.set("DST", "Green OD", true).p("Gain", 8).p("Tone", 50 + 10 * bright).p("Volume", 75);
                if (brightSound) {
                    why = "Peavey 5150, porque o som é brilhante e agressivo";
                    c.set("AMP", "EV 51", true).p("Gain", gain).p("Volume", 55).p("Bass", bass).p("Middle", mid - 4).p("Treble", treble).p("Presence", presence);
                    c.set("CAB", "UK 30 4x12", true).p("Volume", 60).p("Low Cut", 100).p("High Cut", highCut);
                } else if (midForward) {
                    why = "Diezel VH4, porque os médios estão à frente";
                    c.set("AMP", "Dizz VH", true).p("Gain", gain).p("Presence", presence).p("Volume", 55).p("Bass", bass).p("Middle", mid).p("Treble", treble);
                    c.set("CAB", "Dizz 4x12", true).p("Volume", 60).p("Low Cut", 100).p("High Cut", highCut);
                } else if (darkSound && heavyLow) {
                    why = "Orange Rockerverb, porque o som é grave e escuro";
                    c.set("AMP", "Juice R100", true).p("Gain", gain).p("Volume", 55).p("Bass", bass - 6).p("Middle", mid).p("Treble", treble + 4);
                    c.set("CAB", "Juice 4x12", true).p("Volume", 60).p("Low Cut", 105).p("High Cut", highCut);
                } else {
                    why = scooped ? "Mesa Dual Rectifier, com os médios recuados" : "Mesa Dual Rectifier, peso moderno";
                    c.set("AMP", "Mess DualM", true).p("Gain", gain).p("Presence", presence).p("Volume", 55).p("Bass", bass).p("Middle", mid - 6).p("Treble", treble);
                    c.set("CAB", "Mess 4x12", true).p("Volume", 60).p("Low Cut", 100).p("High Cut", highCut);
                }
                c.set("NR", "Gate 1", true).p("Threshold", 40 + 10 * pos);
                c.set("RVB", "Room", true).p("Mix", 5 + 6 * slow).p("Pre Delay", 5).p("Decay", 20 + 10 * slow).p("Trail", 1);
                c.notes.add("O Green OD entra como reforço (ganho baixo, volume alto) para deixar o ataque mais definido antes do amplificador.");
                break;
            }
            default: {
                label = "Solo (lead)";
                c.set("DST", "Green OD", true).p("Gain", 20).p("Tone", 55 + 10 * bright).p("Volume", 68);
                if (darkSound) {
                    why = "Mesa Mark IV, solo cheio e escuro";
                    c.set("AMP", "Mess4 LD", true).p("Gain", 58 + 10 * drive).p("Presence", presence).p("Volume", 58).p("Bass", bass - 3).p("Middle", mid + 8).p("Treble", treble);
                    c.set("CAB", "Mess 4x12", true).p("Volume", 60).p("Low Cut", 95).p("High Cut", highCut);
                } else {
                    why = "Soldano SLO, solo cantado";
                    c.set("AMP", "Solo100 LD", true).p("Gain", 58 + 10 * drive).p("Presence", presence).p("Volume", 58).p("Bass", bass - 3).p("Middle", mid + 8).p("Treble", treble);
                    c.set("CAB", "Solo 4x12", true).p("Volume", 60).p("Low Cut", 95).p("High Cut", highCut);
                }
                c.set("NR", "Gate 1", true).p("Threshold", 35);
                float time = quarterMs > 0 ? quarterMs * 0.75f : 380;
                while (time > 900) time /= 2;
                c.set("DLY", "Pure", true).p("Mix", 22).p("Time", time).p("Feedback", 28).p("Sync", 0).p("Trail", 1);
                c.set("RVB", "Hall", true).p("Mix", 14 + 10 * slow).p("Pre Delay", 20).p("Decay", 40 + 16 * slow).p("Trail", 1);
                if (quarterMs > 0) c.notes.add(String.format(Locale.US, "Delay em colcheia pontuada para %.0f BPM (%.0f ms).", f.bpm, time));
                break;
            }
        }

        c.engine = "Análise do áudio";
        c.name = shortName(songTitle, label);
        c.summary = label + " — " + why + (measured
                ? String.format(Locale.US, ". Medido na música: brilho %.0f%%, graves %.0f%%, médios %.0f%%, presença %.0f%%, saturação estimada %.0f%%.",
                bright * 100, bassIx * 100, midIx * 100, pres * 100, drive * 100)
                : ". Ponto de partida neutro (sem áudio suficiente).");
        c.notes.add("A análise ouve a mixagem inteira (voz, bateria, baixo e guitarra juntos). Brilho, graves e médios são medidas diretas; "
                + "a saturação é uma estimativa, porque não dá para isolar a guitarra. Se o estilo não bater, escolha na lista: a equalização medida continua valendo.");
        c.normalize(catalog);
        return c;
    }

    private static float clamp01(float v) { return v < 0 ? 0 : (v > 1 ? 1 : v); }

    /** Pistas simples de gênero no título. */
    static int styleFromTitle(String title) {
        if (title == null) return AUTO;
        String t = " " + title.toLowerCase(Locale.ROOT) + " ";
        if (has(t, "metal", "thrash", "djent", "hardcore", "metalcore")) return HIGH_GAIN;
        if (has(t, " solo ", "lead guitar", "guitar solo")) return LEAD;
        if (has(t, "blues", "classic rock")) return CRUNCH;
        if (has(t, "jazz", "bossa", "mpb", "reggae", "gospel", "worship", "louvor")) return CLEAN;
        return AUTO;
    }

    private static boolean has(String text, String... words) {
        for (String w : words) if (text.contains(w)) return true;
        return false;
    }

    static String shortName(String songTitle, String fallback) {
        String t = songTitle == null ? "" : songTitle.trim();
        return t.isEmpty() ? fallback : t;
    }
}
