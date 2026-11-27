package com.orleiluis.timbrechord.core;

/** Junta os três analisadores: acordes, andamento e timbre. Recebe PCM mono 44,1 kHz. */
public final class LiveAnalyzer {
    public static final int SAMPLE_RATE = 44100;

    public final ChordDetector chords = new ChordDetector();
    public final TempoEstimator tempo = new TempoEstimator();
    public final ToneAnalyzer tone = new ToneAnalyzer();

    private volatile float level;
    private volatile long samples;

    public void feed(short[] pcm, int n) {
        if (n <= 0) return;
        int peak = 0;
        for (int i = 0; i < n; i++) {
            int a = Math.abs(pcm[i]);
            if (a > peak) peak = a;
        }
        float p = peak / 32768f;
        level = p > level ? p : level * 0.85f + p * 0.15f;
        samples += n;
        chords.feed(pcm, n);
        tempo.feed(pcm, n);
        tone.feed(pcm, n);
    }

    /** Pico recente, 0..1. */
    public float level() { return level; }

    /** Segundos de áudio recebidos desde o último reset. */
    public double seconds() { return samples / (double) SAMPLE_RATE; }

    public ToneAnalyzer.Features features() { return tone.snapshot(tempo.bpm()); }

    public void reset() {
        chords.reset();
        tempo.reset();
        tone.reset();
        level = 0;
        samples = 0;
    }
}
