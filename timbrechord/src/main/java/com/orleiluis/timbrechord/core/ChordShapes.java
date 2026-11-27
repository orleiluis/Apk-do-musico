package com.orleiluis.timbrechord.core;

import java.util.HashMap;
import java.util.Map;

/**
 * Desenho de cada acorde no braço do violão/guitarra (afinação padrão).
 * Os acordes mais comuns usam a forma aberta; os outros usam a pestana móvel com a fundamental
 * na 6ª corda (forma de Mi) ou na 5ª corda (forma de Lá), a que cair mais perto da pestana do braço.
 */
public final class ChordShapes {
    private ChordShapes() {}

    /** Notas das cordas soltas, da 6ª (Mi grave) à 1ª (Mi agudo), de 0 = Dó a 11 = Si. */
    public static final int[] OPEN = {4, 9, 2, 7, 11, 4};

    public static final class Shape {
        /** Casa de cada corda, da 6ª à 1ª: -1 = não toca, 0 = solta. */
        public final int[] frets;
        /** Primeira casa desenhada (1 = junto da pestana do braço). */
        public final int baseFret;
        /** Casa da pestana (0 = sem pestana) e as cordas que ela cobre (índices de 0 a 5). */
        public final int barreFret, barreFrom, barreTo;

        Shape(int[] frets) {
            this.frets = frets;
            int lo = 99, hi = 0;
            for (int f : frets) if (f > 0) { lo = Math.min(lo, f); hi = Math.max(hi, f); }
            if (lo == 99) lo = 1;
            baseFret = hi <= 4 ? 1 : lo;
            // pestana: a casa mais baixa, quando não há corda solta e ela aparece em duas cordas ou mais
            boolean open = false;
            int first = -1, last = -1;
            for (int i = 0; i < 6; i++) {
                if (frets[i] == 0) open = true;
                if (frets[i] == lo) { if (first < 0) first = i; last = i; }
            }
            boolean barre = !open && first >= 0 && last > first;
            // a pestana não pode passar por cima de uma corda que não é tocada
            for (int i = first + 1; barre && i < last; i++) if (frets[i] < 0) barre = false;
            barreFret = barre ? lo : 0;
            barreFrom = barre ? first : 0;
            barreTo = barre ? last : 0;
        }
    }

    private static final String[] SUFFIXES = {"", "m", "dim", "5", "7", "m7", "maj7", "sus4"};
    private static final int[][] INTERVALS = {{0, 4, 7}, {0, 3, 7}, {0, 3, 6}, {0, 7}, {0, 4, 7, 10}, {0, 3, 7, 10}, {0, 4, 7, 11}, {0, 5, 7}};
    private static final int X = -1;
    // formas móveis, relativas à casa da pestana: fundamental na 6ª corda e na 5ª corda
    private static final int[][] E_FORM = {
            {0, 2, 2, 1, 0, 0}, {0, 2, 2, 0, 0, 0}, {0, 1, 2, 0, X, X}, {0, 2, 2, X, X, X},
            {0, 2, 0, 1, 0, 0}, {0, 2, 0, 0, 0, 0}, {0, 2, 1, 1, 0, 0}, {0, 2, 2, 2, 0, 0}};
    private static final int[][] A_FORM = {
            {X, 0, 2, 2, 2, 0}, {X, 0, 2, 2, 1, 0}, {X, 0, 1, 2, 1, X}, {X, 0, 2, 2, X, X},
            {X, 0, 2, 0, 2, 0}, {X, 0, 2, 0, 1, 0}, {X, 0, 2, 1, 2, 0}, {X, 0, 2, 2, 3, 0}};

    private static final Map<String, int[]> OPEN_SHAPES = new HashMap<>();
    static {
        put("C", X, 3, 2, 0, 1, 0);
        put("D", X, X, 0, 2, 3, 2);
        put("G", 3, 2, 0, 0, 0, 3);
        put("Dm", X, X, 0, 2, 3, 1);
        put("C7", X, 3, 2, 3, 1, 0);
        put("D7", X, X, 0, 2, 1, 2);
        put("G7", 3, 2, 0, 0, 0, 1);
        put("B7", X, 2, 1, 2, 0, 2);
        put("Dm7", X, X, 0, 2, 1, 1);
        put("Cmaj7", X, 3, 2, 0, 0, 0);
        put("Dmaj7", X, X, 0, 2, 2, 2);
        put("Fmaj7", X, X, 3, 2, 1, 0);
        put("Gmaj7", 3, 2, 0, 0, 0, 2);
        put("Dsus4", X, X, 0, 2, 3, 3);
        put("D5", X, X, 0, 2, 3, X);
    }

    private static void put(String name, int... frets) { OPEN_SHAPES.put(name, frets); }

    private static final Map<String, Shape> CACHE = new HashMap<>();

    /** Nota fundamental (0 = Dó … 11 = Si) no começo do nome do acorde, ou -1. */
    public static int root(String chord) {
        if (chord == null || chord.isEmpty()) return -1;
        int base;
        switch (chord.charAt(0)) {
            case 'C': base = 0; break;
            case 'D': base = 2; break;
            case 'E': base = 4; break;
            case 'F': base = 5; break;
            case 'G': base = 7; break;
            case 'A': base = 9; break;
            case 'B': base = 11; break;
            default: return -1;
        }
        if (chord.length() > 1 && chord.charAt(1) == '#') base++;
        // "b" só é bemol colado na letra da nota (Bb, Eb); o "b" de "Bb" não se confunde com sufixo
        if (chord.length() > 1 && chord.charAt(1) == 'b') base--;
        return (base + 12) % 12;
    }

    private static String suffix(String chord) {
        int skip = chord.length() > 1 && (chord.charAt(1) == '#' || chord.charAt(1) == 'b') ? 2 : 1;
        return chord.substring(skip);
    }

    private static int quality(String chord) {
        String s = suffix(chord);
        for (int i = 0; i < SUFFIXES.length; i++) if (SUFFIXES[i].equals(s)) return i;
        // sufixo desconhecido: cai na tríade maior ou menor
        return s.startsWith("m") && !s.startsWith("maj") ? 1 : 0;
    }

    /** Notas do acorde (0 = Dó … 11 = Si), com a fundamental primeiro. Vazio se o nome não é um acorde. */
    public static int[] notes(String chord) {
        int root = root(chord);
        if (root < 0) return new int[0];
        int[] iv = INTERVALS[quality(chord)];
        int[] out = new int[iv.length];
        for (int i = 0; i < iv.length; i++) out[i] = (root + iv[i]) % 12;
        return out;
    }

    /** Forma do acorde no braço, ou null se o nome não é um acorde. */
    public static synchronized Shape of(String chord) {
        int root = root(chord);
        if (root < 0) return null;
        Shape cached = CACHE.get(chord);
        if (cached != null) return cached;
        int q = quality(chord);
        String canonical = ChordDetector.NOTES[root] + SUFFIXES[q];
        int[] frets = OPEN_SHAPES.get(canonical);
        if (frets == null) {
            int onE = (root - OPEN[0] + 12) % 12, onA = (root - OPEN[1] + 12) % 12;
            boolean useE = onE <= onA;
            int[] form = useE ? E_FORM[q] : A_FORM[q];
            int at = useE ? onE : onA;
            frets = new int[6];
            for (int i = 0; i < 6; i++) frets[i] = form[i] < 0 ? X : form[i] + at;
        }
        Shape shape = new Shape(frets.clone());
        CACHE.put(chord, shape);
        return shape;
    }
}
