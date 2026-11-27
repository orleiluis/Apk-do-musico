package com.orleiluis.timbrechord.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Mapa de acordes de uma música: qual acorde toca em cada trecho, na posição da própria música
 * (segundos desde o começo do vídeo ou do arquivo), e não no relógio de quem está ouvindo.
 *
 * É ele que permite mostrar o PRÓXIMO acorde e a grade adiantada: um trecho já ouvido (ou analisado
 * antes de tocar, no caso de arquivo) fica guardado e vale para a próxima vez que a música tocar.
 * Um trecho ainda não ouvido é "desconhecido"; silêncio é um trecho conhecido com acorde vazio.
 */
public final class ChordChart {
    /** Trechos mais curtos que isto são notas de passagem: não contam como acorde anterior/próximo. */
    public static final double MIN_LEN = 0.35;
    private static final double EPS = 1e-6;

    public static final class Seg {
        public final double start, end;
        /** "" = silêncio. */
        public final String chord;

        Seg(double start, double end, String chord) {
            this.start = start;
            this.end = end;
            this.chord = chord;
        }
    }

    /** Uma página da grade de tempos: cada célula é um tempo da música. */
    public static final class Grid {
        /** Acorde que entra em cada célula ("" = nenhum acorde novo ali). */
        public final String[] labels;
        /** Falso onde a música ainda não foi ouvida. */
        public final boolean[] known;
        /** Célula onde a música está agora. */
        public final int cursor;
        /** Acorde que já vinha tocando quando a página começa ("" se nenhum). */
        public final String carried;

        Grid(String[] labels, boolean[] known, int cursor, String carried) {
            this.labels = labels;
            this.known = known;
            this.cursor = cursor;
            this.carried = carried;
        }
    }

    private final ArrayList<Seg> segs = new ArrayList<>();
    private float bpm;
    private float tempoScale = 1f;
    private String key = "";
    private double duration;
    private boolean complete, dirty;
    private int version;

    // grade: duração do tempo e começo da contagem, ajustados às trocas de acorde
    // (recalculados quando o mapa muda)
    private int beatVersion = -1;
    private float beatBpm = -1;
    private double beat = 0.5, phase;

    // ------------------------------------------------------------------ escrita

    /** Grava o acorde do trecho [from, to), por cima do que havia ali. chord "" = silêncio. */
    public synchronized void mark(double from, double to, String chord) {
        if (chord == null) return;
        if (from < 0) from = 0;
        if (!(to > from + EPS)) return;
        ArrayList<Seg> out = new ArrayList<>(segs.size() + 2);
        boolean placed = false;
        for (Seg s : segs) {
            if (s.end <= from + EPS) { out.add(s); continue; }
            if (s.start >= to - EPS) {
                if (!placed) { out.add(new Seg(from, to, chord)); placed = true; }
                out.add(s);
                continue;
            }
            if (s.start < from - EPS) out.add(new Seg(s.start, from, s.chord));
            if (!placed) { out.add(new Seg(from, to, chord)); placed = true; }
            if (s.end > to + EPS) out.add(new Seg(to, s.end, s.chord));
        }
        if (!placed) out.add(new Seg(from, to, chord));
        // junta vizinhos com o mesmo acorde
        segs.clear();
        for (Seg s : out) {
            int n = segs.size();
            if (n > 0) {
                Seg last = segs.get(n - 1);
                if (last.chord.equals(s.chord) && s.start - last.end < 0.05) {
                    segs.set(n - 1, new Seg(last.start, s.end, s.chord));
                    continue;
                }
            }
            segs.add(s);
        }
        version++;
        dirty = true;
    }

    public synchronized void clear() {
        segs.clear();
        bpm = 0;
        tempoScale = 1f;
        key = "";
        duration = 0;
        complete = false;
        version++;
        dirty = true;
    }

    public synchronized void setBpm(float value) {
        if (value < 30 || value > 300 || Math.abs(value - bpm) < 0.05f) return;
        bpm = value;
        dirty = true;
    }

    /**
     * Correção da pessoa para o andamento: 0,5 (contava em dobro), 1 ou 2 (contava pela metade).
     * Vale para o BPM mostrado e para a grade; a medida em si fica guardada como foi feita.
     */
    public synchronized void setTempoScale(float value) {
        float v = value <= 0.75f ? 0.5f : value >= 1.5f ? 2f : 1f;
        if (v == tempoScale) return;
        tempoScale = v;
        dirty = true;
    }

    public synchronized float tempoScale() { return tempoScale; }

    /** Andamento já com a correção da pessoa (0 se ainda não medido). */
    public synchronized float shownBpm() { return bpm * tempoScale; }

    public synchronized void setKey(String value) {
        if (value == null || value.equals(key)) return;
        key = value;
        dirty = true;
    }

    public synchronized void setDuration(double seconds) {
        if (seconds <= 0 || Math.abs(seconds - duration) < 0.5) return;
        duration = seconds;
        dirty = true;
    }

    /** A música inteira já foi analisada. */
    public synchronized void setComplete(boolean value) {
        if (complete == value) return;
        complete = value;
        dirty = true;
    }

    // ------------------------------------------------------------------ leitura

    public synchronized float bpm() { return bpm; }

    public synchronized String key() { return key; }

    public synchronized double duration() { return duration; }

    public synchronized boolean isComplete() { return complete; }

    public synchronized boolean isEmpty() { return segs.isEmpty(); }

    /** Há mudanças ainda não salvas. */
    public synchronized boolean isDirty() { return dirty; }

    public synchronized void markSaved() { dirty = false; }

    public synchronized List<Seg> segments() { return new ArrayList<>(segs); }

    /** Segundos de música já conhecidos. */
    public synchronized double coveredSeconds() {
        double sum = 0;
        for (Seg s : segs) sum += s.end - s.start;
        return sum;
    }

    /** Fim do último trecho conhecido. */
    public synchronized double end() { return segs.isEmpty() ? 0 : segs.get(segs.size() - 1).end; }

    private int indexAt(double t) {
        int lo = 0, hi = segs.size() - 1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            Seg s = segs.get(mid);
            if (t < s.start) hi = mid - 1;
            else if (t >= s.end) lo = mid + 1;
            else return mid;
        }
        return -(lo + 1); // -(índice do primeiro trecho depois de t) - 1
    }

    /** Acorde na posição t: "" = silêncio, null = trecho ainda não ouvido. */
    public synchronized String at(double t) {
        int i = indexAt(t);
        return i >= 0 ? segs.get(i).chord : null;
    }

    private static boolean counts(Seg s, String other) {
        return !s.chord.isEmpty() && !s.chord.equals(other) && s.end - s.start >= MIN_LEN;
    }

    /** Primeiro acorde diferente de {@code current} que começa depois de t, ou null se ainda não se sabe. */
    public synchronized Seg next(double t, String current) {
        int i = indexAt(t);
        int from = i >= 0 ? i + 1 : -(i + 1);
        double edge = i >= 0 ? segs.get(i).end : t;
        for (int k = from; k < segs.size(); k++) {
            Seg s = segs.get(k);
            // um buraco não ouvido no caminho: o que vem depois dele não é garantidamente o próximo
            if (s.start - edge > 1.0) return null;
            edge = s.end;
            if (counts(s, current)) return s;
        }
        return null;
    }

    /** Último acorde diferente de {@code current} tocado antes de t, ou null. */
    public synchronized Seg previous(double t, String current) {
        int i = indexAt(t);
        int from = i >= 0 ? i - 1 : -(i + 1) - 1;
        // o acorde atual pode estar em vários trechos seguidos (com passagens curtas no meio)
        for (int k = from; k >= 0; k--) {
            Seg s = segs.get(k);
            if (counts(s, current)) return s;
        }
        return null;
    }

    /** Sequência de acordes tocada até t, sem repetições seguidas e sem notas de passagem. */
    public synchronized List<String> progressionUntil(double t) {
        List<String> out = new ArrayList<>();
        for (Seg s : segs) {
            if (s.start >= t) break;
            if (s.chord.isEmpty() || Math.min(s.end, t) - s.start < MIN_LEN) continue;
            if (out.isEmpty() || !out.get(out.size() - 1).equals(s.chord)) out.add(s.chord);
        }
        return out;
    }

    /**
     * Palpite do próximo acorde quando a música ainda não foi ouvida até ali: procura a última vez
     * em que os acordes recentes apareceram na mesma ordem e devolve o que veio depois.
     * Devolve "" quando não há repetição que justifique um palpite.
     */
    public static String predictNext(List<String> progression) {
        int n = progression.size();
        for (int k = Math.min(4, n - 1); k >= 2; k--) {
            for (int start = n - k - 1; start >= 0; start--) {
                boolean same = true;
                for (int j = 0; j < k && same; j++) same = progression.get(start + j).equals(progression.get(n - k + j));
                if (same) return progression.get(start + k);
            }
        }
        return "";
    }

    // ------------------------------------------------------------------ grade de tempos

    private void updateBeat() {
        float bpm = this.bpm * tempoScale;
        if (beatVersion == version && beatBpm == bpm) return;
        beatVersion = version;
        beatBpm = bpm;
        double base = bpm >= 15 ? 60.0 / bpm : 0.5;
        List<Double> starts = new ArrayList<>();
        for (Seg s : segs) if (!s.chord.isEmpty() && s.end - s.start >= MIN_LEN) starts.add(s.start);
        double bestBeat = base, bestCos = 0, bestSin = 0;
        if (starts.size() >= 6 && bpm >= 15) {
            // o andamento medido erra por uma fração de BPM; ao longo da música isso desalinha a grade.
            // As trocas de acorde caem nos tempos, então elas mesmas afinam o tamanho do tempo.
            double span = starts.get(starts.size() - 1) - starts.get(0);
            double step = Math.max(0.0001, Math.min(0.002, 0.15 * base / Math.max(span, 1)));
            double best = -1;
            for (double r = -0.03; r <= 0.03 + 1e-9; r += step) {
                double b = base * (1 + r), c = 0, s = 0;
                for (double t : starts) {
                    double a = 2 * Math.PI * t / b;
                    c += Math.cos(a);
                    s += Math.sin(a);
                }
                // em caso de empate, fica o mais perto do andamento medido
                double score = Math.hypot(c, s) - 0.02 * starts.size() * Math.abs(r) / 0.03;
                if (score > best) { best = score; bestBeat = b; bestCos = c; bestSin = s; }
            }
        } else {
            for (double t : starts) {
                double a = 2 * Math.PI * t / base;
                bestCos += Math.cos(a);
                bestSin += Math.sin(a);
            }
        }
        beat = bestBeat;
        double p = starts.isEmpty() ? 0 : Math.atan2(bestSin, bestCos) / (2 * Math.PI) * beat;
        p = ((p % beat) + beat) % beat;
        if (beat - p < 1e-6) p = 0;
        // qual tempo é o "1" da página: aquele em que mais acordes entram (de 4 em 4 tempos)
        int[] hits = new int[8];
        for (double t : starts) hits[(int) Math.floorMod(Math.round((t - p) / beat), 8L)]++;
        int shift = 0;
        double bestHits = -1;
        for (int k = 0; k < 8; k++) {
            double score = hits[k] + 0.5 * hits[(k + 4) % 8];
            if (score > bestHits + 1e-9) { bestHits = score; shift = k; }
        }
        phase = p + shift * beat;
    }

    /** Duração de um tempo da grade, em segundos. */
    public synchronized double beatSeconds() {
        updateBeat();
        return beat;
    }

    /**
     * Página da grade onde a posição t está: {@code rows} linhas de {@code cols} tempos.
     * A linha de cima é a que está tocando; as de baixo mostram o que vem depois.
     */
    public synchronized Grid grid(double t, int cols, int rows) {
        updateBeat();
        int total = cols * rows;
        long cell = (long) Math.floor((t - phase) / beat);
        long first = Math.floorDiv(cell, (long) cols) * cols;
        String[] labels = new String[total];
        boolean[] known = new boolean[total];
        double[] weight = new double[total];
        for (int i = 0; i < total; i++) {
            labels[i] = "";
            known[i] = indexAt(phase + (first + i + 0.5) * beat) >= 0;
        }
        double pageStart = phase + first * beat;
        String carried = "";
        String running = "";
        for (Seg s : segs) {
            if (s.chord.isEmpty()) { if (s.end - s.start >= MIN_LEN) running = ""; continue; }
            if (s.end - s.start < MIN_LEN) continue;
            boolean repeat = s.chord.equals(running);
            running = s.chord;
            if (s.start < pageStart - beat / 2) { carried = s.end > pageStart ? s.chord : ""; continue; }
            // a troca vale para o tempo mais próximo: ela nunca cai exatamente em cima da batida
            long at = Math.round((s.start - phase) / beat) - first;
            if (at >= total) break;
            if (at < 0 || repeat) continue;
            double len = s.end - s.start;
            if (len > weight[(int) at]) { weight[(int) at] = len; labels[(int) at] = s.chord; }
        }
        return new Grid(labels, known, (int) (cell - first), carried);
    }

    // ------------------------------------------------------------------ arquivo

    /** Muda quando a medida do andamento muda: mapas antigos são analisados de novo ao abrir. */
    static final int TEMPO_VERSION = 2;

    /** Texto para guardar o mapa (uma linha por trecho). */
    public synchronized String serialize() {
        StringBuilder sb = new StringBuilder("TC1\n");
        sb.append("bpm=").append(String.format(Locale.US, "%.2f", bpm)).append('\n');
        sb.append("key=").append(key.replace('\n', ' ')).append('\n');
        sb.append("dur=").append(String.format(Locale.US, "%.3f", duration)).append('\n');
        sb.append("complete=").append(complete ? 1 : 0).append('\n');
        sb.append("tempo=").append(TEMPO_VERSION).append('\n');
        if (tempoScale != 1f) sb.append("escala=").append(tempoScale).append('\n');
        for (Seg s : segs)
            sb.append(String.format(Locale.US, "%.3f %.3f %s\n", s.start, s.end, s.chord.isEmpty() ? "-" : s.chord));
        return sb.toString();
    }

    /** Lê o texto de {@link #serialize()}. Linhas que não entende são ignoradas; null se não é um mapa. */
    public static ChordChart parse(String text) {
        if (text == null || !text.startsWith("TC1")) return null;
        ChordChart c = new ChordChart();
        String tempo = "";
        for (String line : text.split("\n")) {
            line = line.trim();
            try {
                if (line.startsWith("bpm=")) c.bpm = Float.parseFloat(line.substring(4));
                else if (line.startsWith("key=")) c.key = line.substring(4);
                else if (line.startsWith("dur=")) c.duration = Double.parseDouble(line.substring(4));
                else if (line.startsWith("complete=")) c.complete = line.endsWith("1");
                else if (line.startsWith("tempo=")) tempo = line.substring(6).trim();
                else if (line.startsWith("escala=")) c.setTempoScale(Float.parseFloat(line.substring(7)));
                else {
                    String[] p = line.split(" ");
                    if (p.length != 3) continue;
                    double a = Double.parseDouble(p[0]), b = Double.parseDouble(p[1]);
                    if (a < 0 || b <= a || ChordShapes.root(p[2]) < 0 && !"-".equals(p[2])) continue;
                    c.mark(a, b, "-".equals(p[2]) ? "" : p[2]);
                }
            } catch (NumberFormatException ignored) { }
        }
        if (c.bpm < 30 || c.bpm > 300) c.bpm = 0;
        // mapa de uma versão com o andamento medido só no fim da música: analisa de novo (os acordes ficam)
        if (!String.valueOf(TEMPO_VERSION).equals(tempo)) c.complete = false;
        c.dirty = false;
        return c;
    }
}
