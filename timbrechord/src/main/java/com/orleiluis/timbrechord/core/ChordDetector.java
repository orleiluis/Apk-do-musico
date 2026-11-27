package com.orleiluis.timbrechord.core;

import java.util.ArrayList;
import java.util.List;

/**
 * Detector de acordes em tempo real.
 * Entrada: PCM mono 44,1 kHz. Reduz para 11 025 Hz e mede, a cada ~93 ms, dois cromagramas
 * (energia por nota, de Dó a Si): o do baixo e o da região dos acordes.
 *
 * O acorde não é escolhido quadro a quadro. A voz cantada é o som mais forte da mixagem e, sozinha,
 * cada nota cantada parece um acorde (os harmônicos dela formam uma tríade maior). Por isso:
 *  - harmônicos de notas mais graves são descontados antes de somar a energia de cada nota;
 *  - o baixo ajuda a decidir a fundamental;
 *  - a troca só acontece quando a evidência ACUMULADA do acorde novo supera a do atual por uma
 *    margem (caminho mais provável, como em um Viterbi), então notas de passagem da melodia não
 *    derrubam o acorde;
 *  - sétimas, sus4 e power chords são um "enfeite" decidido pela média do trecho inteiro do acorde.
 */
public final class ChordDetector {
    public static final int IN_RATE = 44100;
    private static final int DECIM = 4;
    public static final int RATE = IN_RATE / DECIM;
    private static final int FRAME = 4096;
    private static final int HOP = 1024;
    private static final int TAPS = 48;
    private static final float SILENCE_RMS = 0.0035f;

    /** Notas MIDI: baixo de Mi1 a Mi3; acordes de Sol2 a Dó6. */
    private static final int BASS_LO = 28, BASS_FULL = 47, BASS_HI = 52;
    private static final int MID_LO = 43, MID_HI = 84;
    private static final double MID_CENTER = 57, MID_WIDTH = 11;
    private static final float F_MIN = 38f, F_MAX = 1080f;

    public static final String[] NOTES = {"C", "C#", "D", "Eb", "E", "F", "F#", "G", "Ab", "A", "Bb", "B"};
    private static final String[] NOTES_PT = {"Dó", "Dó#", "Ré", "Mi♭", "Mi", "Fá", "Fá#", "Sol", "Lá♭", "Lá", "Si♭", "Si"};

    // estados do caminho: 12 maiores, 12 menores, 12 diminutos
    private static final int MAJ = 0, MIN = 1, DIM = 2;
    private static final int STATES = 36;
    private static final int[][] TRIAD = {{0, 4, 7}, {0, 3, 7}, {0, 3, 6}};
    private static final float[] TRIAD_BIAS = {1.00f, 1.00f, 0.82f};
    private static final String[] TRIAD_NAME = {"", "m", "dim"};

    /** Peso do baixo na escolha da fundamental. */
    private static final float BASS_WEIGHT = 0.30f;
    /** Vantagem acumulada que um acorde novo precisa juntar para tomar o lugar do atual. */
    private static final float SWITCH_COST = 0.75f;
    /** Folga que o caminho novo precisa abrir sobre o do acorde mostrado. */
    private static final float SWITCH_MARGIN = 0.30f;
    /** Empurrão para os acordes do tom, depois que o tom é conhecido. */
    private static final float KEY_BONUS = 0.035f;

    private static final double[] KS_MAJOR = {6.35, 2.23, 3.48, 2.33, 4.38, 4.09, 2.52, 5.19, 2.39, 3.66, 2.29, 2.88};
    private static final double[] KS_MINOR = {6.33, 2.68, 3.52, 5.38, 2.60, 3.53, 2.54, 4.75, 3.98, 2.69, 3.34, 3.17};

    // função de cada acorde dentro do tom: [intervalo da tônica][maior, menor, diminuto]
    private static final float OUT = -0.25f;
    private static final float[][] FN_MAJOR = new float[12][];
    private static final float[][] FN_MINOR = new float[12][];
    static {
        for (int i = 0; i < 12; i++) {
            FN_MAJOR[i] = new float[]{OUT, OUT, OUT};
            FN_MINOR[i] = new float[]{OUT, OUT, OUT};
        }
        FN_MAJOR[0][MAJ] = 1.00f;   // I
        FN_MAJOR[7][MAJ] = 0.80f;   // V
        FN_MAJOR[5][MAJ] = 0.75f;   // IV
        FN_MAJOR[9][MIN] = 0.60f;   // vi
        FN_MAJOR[2][MIN] = 0.50f;   // ii
        FN_MAJOR[4][MIN] = 0.40f;   // iii
        FN_MAJOR[11][DIM] = 0.30f;  // vii°
        FN_MAJOR[10][MAJ] = 0.30f;  // bVII
        FN_MAJOR[2][MAJ] = 0.10f;   // V/V
        FN_MAJOR[4][MAJ] = 0.05f;   // V/vi
        FN_MAJOR[5][MIN] = 0.05f;   // iv emprestado
        FN_MAJOR[9][MAJ] = 0.00f;   // V/ii

        FN_MINOR[0][MIN] = 1.00f;   // i
        FN_MINOR[7][MAJ] = 0.80f;   // V (menor harmônica)
        FN_MINOR[5][MIN] = 0.70f;   // iv
        FN_MINOR[8][MAJ] = 0.70f;   // bVI
        FN_MINOR[10][MAJ] = 0.70f;  // bVII
        FN_MINOR[7][MIN] = 0.55f;   // v
        FN_MINOR[3][MAJ] = 0.50f;   // bIII (o relativo maior)
        FN_MINOR[2][DIM] = 0.30f;   // ii°
        FN_MINOR[5][MAJ] = 0.25f;   // IV dórico
        FN_MINOR[11][DIM] = 0.20f;  // vii°
        FN_MINOR[0][MAJ] = 0.10f;   // terça de picardia
    }

    public static final class Event {
        public final double timeSec;
        public final String chord;
        Event(double t, String c) { timeSec = t; chord = c; }
    }

    // decimação
    private final float[] fir = new float[TAPS];
    private final float[] hist = new float[TAPS];
    private int histPos, decimPhase;

    // quadro
    private final float[] ring = new float[FRAME];
    private int ringPos, sinceHop, filled;
    private final Fft fft = new Fft(FRAME);
    private final float[] window = Fft.hann(FRAME);
    private final float[] re = new float[FRAME], im = new float[FRAME], mag = new float[FRAME / 2];

    // picos do quadro
    private static final int MAX_PEAKS = 96;
    private final double[] peakPitch = new double[MAX_PEAKS];
    private final float[] peakMag = new float[MAX_PEAKS];
    private final float[] peakOut = new float[MAX_PEAKS];

    // cromagramas
    private final float[] midNow = new float[12], bassNow = new float[12];
    private final float[] mid = new float[12], bass = new float[12];
    private final float[] seg = new float[12];
    private float bassPresence;
    private double tuneCos, tuneSin, tuning;

    // caminho mais provável
    private final float[] path = new float[STATES];
    private final float[] obs = new float[STATES];
    private int state = -1, segHops;
    private int deco, pendingDeco, pendingDecoCount;
    private boolean started;

    // tom
    private final double[] chordTime = new double[STATES];
    private final double[] powerTime = new double[12];
    private final double[] chromaSum = new double[12];
    private double chordTimeTotal;
    private int keyRoot = -1;
    private boolean keyMinor;
    /**
     * Primeiro acorde que se firma no começo da escuta (-1 = ainda não). Am–F–C–G e C–G–Am–F têm os mesmos
     * acordes; o que costuma dizer qual é a casa é o acorde que abre a sequência.
     */
    private int firstState = -1;
    private static final int FIRST_HOPS = 15;          // ~1,4 s no mesmo acorde
    private static final double FIRST_WINDOW = 280;    // nos primeiros ~25 s
    private static final double FIRST_BONUS = 0.12;
    private int keyAge;

    private long decimatedSamples;
    private int silentHops;
    private volatile String current = "";
    private volatile float confidence;
    private volatile boolean simpleMode;
    private final List<Event> timeline = new ArrayList<>();

    // enfeites do acorde
    private static final int PLAIN = 0, POWER = 1, SEVENTH = 2, MAJ7 = 3, SUS4 = 4;
    private static final String[] DECO_MAJ = {"", "5", "7", "maj7", "sus4"};
    private static final String[] DECO_MIN = {"m", "5", "m7", "m", "sus4"};

    public ChordDetector() {
        // passa-baixa (sinc com janela de Hamming), corte ~4,6 kHz, antes de reduzir a taxa
        double fc = 4600.0 / IN_RATE, sum = 0;
        for (int i = 0; i < TAPS; i++) {
            double x = i - (TAPS - 1) / 2.0;
            double s = Math.abs(x) < 1e-9 ? 2 * fc : Math.sin(2 * Math.PI * fc * x) / (Math.PI * x);
            double w = 0.54 - 0.46 * Math.cos(2 * Math.PI * i / (TAPS - 1));
            fir[i] = (float) (s * w);
            sum += fir[i];
        }
        for (int i = 0; i < TAPS; i++) fir[i] /= (float) sum;
    }

    public void setSimpleMode(boolean simple) { simpleMode = simple; }

    /** Acorde atual ("" = silêncio / sem acorde). */
    public String current() { return current; }

    /** 0..1: o quanto o áudio se parece com o acorde mostrado. */
    public float confidence() { return confidence; }

    public synchronized List<Event> timeline() { return new ArrayList<>(timeline); }

    /**
     * Acorde atual junto com os relógios do detector, lidos no mesmo instante:
     * out[0] = segundos de áudio analisados desde o último reset;
     * out[1] = quando o acorde atual apareceu (igual a out[0] se não há acorde).
     */
    public synchronized String currentWithTimes(double[] out) {
        double now = decimatedSamples / (double) RATE;
        out[0] = now;
        out[1] = current.isEmpty() || timeline.isEmpty() ? now : timeline.get(timeline.size() - 1).timeSec;
        return current;
    }

    public synchronized void reset() {
        java.util.Arrays.fill(hist, 0);
        java.util.Arrays.fill(ring, 0);
        java.util.Arrays.fill(mid, 0);
        java.util.Arrays.fill(bass, 0);
        java.util.Arrays.fill(seg, 0);
        java.util.Arrays.fill(path, 0);
        java.util.Arrays.fill(chordTime, 0);
        java.util.Arrays.fill(powerTime, 0);
        java.util.Arrays.fill(chromaSum, 0);
        histPos = decimPhase = ringPos = sinceHop = filled = 0;
        decimatedSamples = 0;
        silentHops = 0;
        bassPresence = 0;
        tuneCos = tuneSin = tuning = 0;
        state = -1;
        segHops = 0;
        deco = pendingDeco = PLAIN;
        pendingDecoCount = 0;
        started = false;
        chordTimeTotal = 0;
        firstState = -1;
        keyRoot = -1;
        keyMinor = false;
        keyAge = 0;
        current = "";
        confidence = 0;
        timeline.clear();
    }

    public synchronized void feed(short[] pcm, int count) {
        for (int n = 0; n < count; n++) {
            hist[histPos] = pcm[n] / 32768f;
            histPos = (histPos + 1) % TAPS;
            if (++decimPhase < DECIM) continue;
            decimPhase = 0;
            float acc = 0;
            int p = histPos;
            for (int t = 0; t < TAPS; t++) {
                acc += fir[t] * hist[p];
                if (++p == TAPS) p = 0;
            }
            ring[ringPos] = acc;
            ringPos = (ringPos + 1) % FRAME;
            decimatedSamples++;
            if (filled < FRAME) filled++;
            if (++sinceHop >= HOP && filled == FRAME) {
                sinceHop = 0;
                analyze();
            }
        }
    }

    private void analyze() {
        double energy = 0;
        int p = ringPos;
        for (int i = 0; i < FRAME; i++) {
            float v = ring[p];
            if (++p == FRAME) p = 0;
            energy += v * v;
            re[i] = v * window[i];
            im[i] = 0;
        }
        float rms = (float) Math.sqrt(energy / FRAME);
        if (rms < SILENCE_RMS) {
            for (int i = 0; i < 12; i++) { mid[i] *= 0.6f; bass[i] *= 0.6f; }
            if (++silentHops >= 5) silence();
            return;
        }
        silentHops = 0;

        fft.transform(re, im);
        if (!chroma()) return;

        float a = started ? 0.40f : 1f;
        float midNorm = 0, bassSum = 0, bassNowSum = 0, midNowSum = 0;
        for (int i = 0; i < 12; i++) { bassNowSum += bassNow[i]; midNowSum += midNow[i]; }
        for (int i = 0; i < 12; i++) {
            mid[i] += a * (midNow[i] - mid[i]);
            bass[i] += a * (bassNow[i] - bass[i]);
            midNorm += mid[i] * mid[i];
            bassSum += bass[i];
        }
        started = true;
        midNorm = (float) Math.sqrt(midNorm);
        if (midNorm < 1e-9f) return;
        // o baixo só opina quando existe (no microfone do celular ele quase some)
        float presence = bassNowSum / (bassNowSum + midNowSum + 1e-9f);
        bassPresence += 0.2f * (presence - bassPresence);
        float bassTrust = clamp01((bassPresence - 0.04f) / 0.12f);

        int states = simpleMode ? 24 : STATES;
        updateKey();
        int best = -1;
        float bestObs = -9;
        for (int s = 0; s < states; s++) {
            int q = s / 12, root = s % 12;
            int[] pcs = TRIAD[q];
            float dot = 0;
            for (int pc : pcs) dot += mid[(root + pc) % 12];
            float score = dot / (midNorm * 1.7320508f) * TRIAD_BIAS[q];
            if (bassSum > 1e-9f)
                score += BASS_WEIGHT * bassTrust * (bass[root] / bassSum - 1f / 12);
            if (keyRoot >= 0) {
                float fn = (keyMinor ? FN_MINOR : FN_MAJOR)[(root - keyRoot + 12) % 12][q];
                if (fn >= 0.30f) score += KEY_BONUS;
            }
            obs[s] = score;
            if (score > bestObs) { bestObs = score; best = s; }
        }

        // caminho mais provável: ficar no acorde é de graça, trocar custa SWITCH_COST
        float top = -1e9f;
        for (int s = 0; s < states; s++) if (path[s] > top) top = path[s];
        float newTop = -1e9f;
        int winner = -1;
        for (int s = 0; s < states; s++) {
            float stay = path[s], jump = top - SWITCH_COST;
            path[s] = obs[s] + (stay > jump ? stay : jump);
            if (path[s] > newTop || (path[s] == newTop && s == state)) { newTop = path[s]; winner = s; }
        }
        for (int s = 0; s < states; s++) path[s] -= newTop;
        for (int s = states; s < STATES; s++) path[s] = -SWITCH_COST;
        if (state >= states) state = -1; // modo simples ligado no meio de um diminuto

        // o acorde mostrado só cede quando o caminho novo abre uma folga: sem isso, dois caminhos
        // quase empatados ficam se revezando na tela
        if (state >= 0 && winner != state && path[state] > -SWITCH_MARGIN) winner = state;
        if (winner != state) {
            state = winner;
            segHops = 0;
            deco = pendingDeco = PLAIN;
            pendingDecoCount = 0;
            System.arraycopy(midNow, 0, seg, 0, 12);
        } else {
            float sa = 1f / Math.min(segHops + 1, 28); // média do trecho, com memória de ~2,5 s
            for (int i = 0; i < 12; i++) seg[i] += sa * (midNow[i] - seg[i]);
        }
        segHops++;
        if (!simpleMode) decorate();
        else deco = PLAIN;

        chordTime[state] += 1;
        if (firstState < 0 && segHops >= FIRST_HOPS && chordTimeTotal < FIRST_WINDOW) firstState = state;
        if (deco == POWER) powerTime[state % 12] += 1;
        chordTimeTotal += 1;
        for (int i = 0; i < 12; i++) chromaSum[i] += mid[i] / midNorm + 0.5 * (bassSum > 1e-9f ? bass[i] / bassSum : 0);

        confidence = clamp01(obs[state]);
        publish(name(state, deco));
    }

    /** Preenche midNow e bassNow a partir dos picos do espectro. Falso se o quadro não tem nota nenhuma. */
    private boolean chroma() {
        int kMin = Math.max(2, (int) (F_MIN * FRAME / RATE));
        int kMax = Math.min(FRAME / 2 - 2, (int) (F_MAX * FRAME / RATE));
        float floor = 0;
        for (int k = kMin - 1; k <= kMax + 1; k++) {
            mag[k] = (float) Math.sqrt(re[k] * re[k] + im[k] * im[k]);
            if (mag[k] > floor) floor = mag[k];
        }
        floor *= 0.02f; // picos 34 dB abaixo do maior são ruído
        int n = 0;
        for (int k = kMin; k <= kMax && n < MAX_PEAKS; k++) {
            float m = mag[k];
            if (m < floor || m <= mag[k - 1] || m < mag[k + 1]) continue;
            float lo = mag[k - 1], hi = mag[k + 1];
            float denom = lo - 2 * m + hi;
            float delta = Math.abs(denom) < 1e-12f ? 0 : 0.5f * (lo - hi) / denom;
            double freq = (k + delta) * (double) RATE / FRAME;
            peakPitch[n] = 69 + 12 * (Math.log(freq / 440.0) / Math.log(2));
            peakMag[n] = m;
            n++;
        }
        if (n == 0) return false;

        // afinação da gravação: desvio médio dos picos em relação ao Lá 440
        double c = 0, s = 0;
        for (int i = 0; i < n; i++) {
            double d = peakPitch[i] - Math.rint(peakPitch[i]);
            c += peakMag[i] * Math.cos(2 * Math.PI * d);
            s += peakMag[i] * Math.sin(2 * Math.PI * d);
        }
        double norm = Math.sqrt(c * c + s * s);
        if (norm > 0) {
            tuneCos = 0.995 * tuneCos + c / norm;
            tuneSin = 0.995 * tuneSin + s / norm;
            if (Math.hypot(tuneCos, tuneSin) > 12) tuning = Math.atan2(tuneSin, tuneCos) / (2 * Math.PI);
        }

        // desconta de cada pico o que uma nota mais grave produz ali como harmônico (3º, 5º, 7º),
        // na proporção de uma corda (1/n): é o que faz uma nota sozinha parecer uma tríade maior.
        // Mais que isso apagaria a terça e a quinta de verdade, que caem nas mesmas frequências.
        for (int i = 0; i < n; i++) {
            float m = peakMag[i];
            float take = 0;
            for (int j = 0; j < i; j++) {
                double gap = peakPitch[i] - peakPitch[j];
                float share;
                if (Math.abs(gap - 19.02) < 0.35) share = 0.30f;
                else if (Math.abs(gap - 27.86) < 0.35) share = 0.18f;
                else if (Math.abs(gap - 33.69) < 0.35) share = 0.12f;
                else continue;
                take = Math.max(take, share * peakMag[j]);
            }
            peakOut[i] = Math.max(0, m - take);
        }

        java.util.Arrays.fill(midNow, 0);
        java.util.Arrays.fill(bassNow, 0);
        for (int i = 0; i < n; i++) {
            if (peakOut[i] <= 0) continue;
            double pitch = peakPitch[i] - tuning;
            long nearest = Math.round(pitch);
            double dev = Math.abs(pitch - nearest);
            if (dev > 0.45) continue;
            double tune = Math.cos(dev / 0.45 * Math.PI / 2);
            int pc = (int) (((nearest % 12) + 12) % 12);
            double v = Math.pow(peakOut[i], 0.6) * tune * tune;
            if (pitch >= MID_LO - 0.5 && pitch <= MID_HI + 0.5)
                midNow[pc] += (float) (v * Math.exp(-0.5 * Math.pow((pitch - MID_CENTER) / MID_WIDTH, 2)));
            if (pitch >= BASS_LO - 0.5 && pitch <= BASS_HI + 0.5) {
                double w = pitch <= BASS_FULL ? 1 : Math.max(0, (BASS_HI + 0.5 - pitch) / (BASS_HI + 0.5 - BASS_FULL));
                bassNow[pc] += (float) (v * w);
            }
        }
        float max = 0;
        for (float v : midNow) max = Math.max(max, v);
        if (max <= 0) return false;
        // os dois na mesma escala, para dar para comparar baixo com acordes
        for (int i = 0; i < 12; i++) { midNow[i] /= max; bassNow[i] /= max; }
        return true;
    }

    /** Sétima, sus4 e power chord: decididos pela média do trecho, e só depois de se manterem. */
    private void decorate() {
        int q = state / 12, root = state % 12;
        int want = PLAIN;
        if (q != DIM && segHops >= 5) {
            float r = seg[root], fifth = seg[(root + 7) % 12];
            float body = (r + fifth) / 2;
            float third = seg[(root + (q == MAJ ? 4 : 3)) % 12];
            float otherThird = seg[(root + (q == MAJ ? 3 : 4)) % 12];
            float fourth = seg[(root + 5) % 12];
            float b7 = seg[(root + 10) % 12], maj7 = seg[(root + 11) % 12];
            float chordMean = (r + fifth + third) / 3;
            if (third < 0.40f * body && otherThird < 0.40f * body) {
                want = fourth > 0.60f * body ? SUS4 : POWER;
            } else if (b7 > 0.62f * chordMean && b7 > 1.3f * maj7 && b7 > seg[(root + 9) % 12]) {
                want = SEVENTH;
            } else if (q == MAJ && maj7 > 0.62f * chordMean && maj7 > 1.3f * b7) {
                want = MAJ7;
            }
        }
        if (want == deco) { pendingDecoCount = 0; return; }
        if (want == pendingDeco) pendingDecoCount++;
        else { pendingDeco = want; pendingDecoCount = 1; }
        if (pendingDecoCount >= 5) { deco = want; pendingDecoCount = 0; }
    }

    private void silence() {
        if (state < 0 && current.isEmpty()) return;
        state = -1;
        segHops = 0;
        deco = pendingDeco = PLAIN;
        java.util.Arrays.fill(path, 0);
        started = false;
        confidence = 0;
        current = "";
    }

    /** Atualiza o acorde mostrado e a linha do tempo. O enfeite corrige o nome do trecho, não cria outro. */
    private void publish(String name) {
        if (name.equals(current)) return;
        double now = decimatedSamples / (double) RATE;
        boolean sameSegment = segHops > 1 && !timeline.isEmpty() && !current.isEmpty();
        current = name;
        if (sameSegment) {
            Event last = timeline.get(timeline.size() - 1);
            timeline.set(timeline.size() - 1, new Event(last.timeSec, name));
            return;
        }
        if (timeline.size() >= 3000) timeline.remove(0);
        timeline.add(new Event(now, name));
    }

    private static String name(int state, int deco) {
        int q = state / 12;
        String root = NOTES[state % 12];
        if (q == DIM) return root + TRIAD_NAME[DIM];
        return root + (q == MAJ ? DECO_MAJ : DECO_MIN)[deco];
    }

    // ------------------------------------------------------------------ tom

    private void updateKey() {
        if (++keyAge < 10) return; // ~1 vez por segundo
        keyAge = 0;
        if (chordTimeTotal < 90) return; // ~8 s de música
        estimateKey();
    }

    /**
     * Tom pela função dos acordes tocados (quanto tempo a música passa em cada um), com um peso menor
     * para o perfil das notas. Só as notas não separam um tom do seu relativo (Dó maior e Lá menor
     * usam as mesmas sete); o que separa é qual acorde funciona como casa.
     */
    private void estimateKey() {
        double best = -1e9;
        int bestRoot = 0;
        boolean bestMinor = false;
        for (int root = 0; root < 12; root++) {
            for (int minor = 0; minor < 2; minor++) {
                float[][] fn = minor == 1 ? FN_MINOR : FN_MAJOR;
                double sum = 0;
                for (int s = 0; s < STATES; s++) {
                    if (chordTime[s] <= 0) continue;
                    int q = s / 12, r = s % 12, iv = (r - root + 12) % 12;
                    double t = chordTime[s], power = 0;
                    if (q != DIM) { power = Math.min(t, powerTime[r] * chordTime[s] / (chordTime[r] + chordTime[12 + r])); t -= power; }
                    sum += t * fn[iv][q];
                    // power chord não tem terça: vale como o que couber melhor no tom
                    sum += power * Math.max(fn[iv][MAJ], fn[iv][MIN]);
                }
                // um tom cujo acorde de tônica quase não aparece é improvável
                double tonic = chordTime[(minor == 1 ? 12 : 0) + root] + Math.min(powerTime[root], chordTime[root] + chordTime[12 + root]);
                double home = Math.min(1, tonic / chordTimeTotal / 0.12);
                double score = sum / chordTimeTotal * (0.5 + 0.5 * home) + 0.25 * corr(minor == 1 ? KS_MINOR : KS_MAJOR, root);
                // a música (ou o laço) abre na tônica: desempata o tom e o relativo
                if (firstState == (minor == 1 ? 12 : 0) + root) score += FIRST_BONUS;
                if (score > best) { best = score; bestRoot = root; bestMinor = minor == 1; }
            }
        }
        keyRoot = bestRoot;
        keyMinor = bestMinor;
    }

    /** Tom provável, ex.: "Am (Lá menor)". Vazio enquanto não há áudio suficiente. */
    public synchronized String key() {
        if (chordTimeTotal < 90) return "";
        estimateKey();
        return NOTES[keyRoot] + (keyMinor ? "m" : "") + " (" + NOTES_PT[keyRoot] + (keyMinor ? " menor" : " maior") + ")";
    }

    /** Tom relativo do tom estimado, ex.: "C" para "Am". Vazio enquanto não há tom. */
    public synchronized String relativeKey() {
        if (chordTimeTotal < 90) return "";
        estimateKey();
        return keyMinor ? NOTES[(keyRoot + 3) % 12] : NOTES[(keyRoot + 9) % 12] + "m";
    }

    private double corr(double[] profile, int root) {
        double mx = 0, my = 0;
        for (int i = 0; i < 12; i++) { mx += profile[i]; my += chromaSum[i]; }
        mx /= 12; my /= 12;
        double sxy = 0, sxx = 0, syy = 0;
        for (int i = 0; i < 12; i++) {
            double x = profile[i] - mx, y = chromaSum[(root + i) % 12] - my;
            sxy += x * y; sxx += x * x; syy += y * y;
        }
        return sxx <= 0 || syy <= 0 ? 0 : sxy / Math.sqrt(sxx * syy);
    }

    private static float clamp01(float v) { return v < 0 ? 0 : (v > 1 ? 1 : v); }
}
