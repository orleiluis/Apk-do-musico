package com.orleiluis.timbrechord.core;

/**
 * Passa para o mapa da música ({@link ChordChart}) o que o detector está ouvindo.
 *
 * O detector trabalha no relógio do áudio que recebe; o mapa, na posição da música. Enquanto a
 * música toca sem saltos, as duas contas andam juntas (na proporção da velocidade do player).
 * Pausa, salto ou troca de velocidade quebram essa relação: o gravador percebe e recomeça dali,
 * sem escrever nada no lugar errado.
 *
 * O detector mostra cada troca com atraso (ele precisa de evidência acumulada). Esse atraso é
 * descontado na hora de gravar, então o mapa fica alinhado com a música, e não com a tela.
 */
public final class ChartRecorder {
    /** Atraso médio entre a troca real do acorde e o detector mostrar o acorde novo, em segundos. */
    public static final double LAG = 0.52;
    /** Depois de um salto, o detector ainda mostra o acorde de antes por este tempo. */
    private static final double SETTLE = 0.6;
    private static final double MAX_DRIFT = 0.5;

    private final double[] times = new double[2];
    private boolean running;
    private double anchorMedia, anchorDet, runStartMedia, runStartDet, lastDet, lastTo;
    private double lastRate;

    /** Esquece a relação entre o relógio do áudio e a posição da música (pausa, troca de música). */
    public void pause() { running = false; }

    private void begin(double mediaSec, double detNow, double rate) {
        running = true;
        anchorMedia = runStartMedia = mediaSec;
        anchorDet = runStartDet = lastDet = detNow;
        lastTo = Double.NaN;
        lastRate = rate;
    }

    /**
     * Chamar várias vezes por segundo enquanto o áudio chega ao detector.
     *
     * @param mediaSec posição da música agora, em segundos
     * @param rate     velocidade com que a posição anda em relação ao áudio analisado (1 = normal)
     */
    public void update(ChordChart chart, ChordDetector detector, double mediaSec, double rate) {
        String chord = detector.currentWithTimes(times);
        double detNow = times[0], since = times[1];
        if (!running || rate != lastRate) { begin(mediaSec, detNow, rate); return; }
        if (detNow < lastDet - 1e-9) { begin(mediaSec, detNow, rate); return; } // o detector foi zerado
        if (detNow <= lastDet) return; // nenhum áudio novo desde a última chamada
        if (detNow - lastDet > 3) { begin(mediaSec, detNow, rate); return; }
        double expected = anchorMedia + (detNow - anchorDet) * rate;
        double drift = mediaSec - expected;
        if (Math.abs(drift) > MAX_DRIFT) { begin(mediaSec, detNow, rate); return; }
        // os dois relógios nunca batem exatamente: a referência acompanha a diferença aos poucos
        anchorMedia += 0.1 * drift;
        lastDet = detNow;
        if (detNow - runStartDet < SETTLE) return;

        double to = mediaSec - LAG * rate;
        double floor = runStartMedia;
        double from;
        if (chord.isEmpty()) {
            from = Double.isNaN(lastTo) ? to : lastTo;
        } else {
            // regrava o acorde desde o começo do trecho: o nome dele ainda pode ser corrigido (sétima, sus4)
            from = to - (detNow - since) * rate;
            if (!Double.isNaN(lastTo) && from > lastTo && from - lastTo < 0.5) from = lastTo;
        }
        if (from < floor) from = floor;
        if (to > from) chart.mark(from, to, chord);
        lastTo = to;
    }
}
