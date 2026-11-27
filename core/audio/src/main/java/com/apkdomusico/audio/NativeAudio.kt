package com.apkdomusico.audio

/** Ponte para o motor de áudio em C++ (Oboe). Todas as funções podem ser chamadas da thread principal. */
object NativeAudio {
    init {
        System.loadLibrary("musico_audio")
    }

    external fun metronomeStart(): Boolean
    external fun metronomeStop()
    external fun metronomeIsRunning(): Boolean
    external fun metronomeSetBpm(bpm: Double)
    external fun metronomeSetBeatsPerBar(beats: Int)
    external fun metronomeSetSubdivision(subdivision: Int)
    external fun metronomeSetAccent(enabled: Boolean)
    external fun metronomeSetVolume(volume: Float)

    /** Tempo atual do compasso (0 = primeiro), ou -1 parado. */
    external fun metronomeCurrentBeat(): Int

    /** Clique atual dentro do compasso contando subdivisões, ou -1 parado. */
    external fun metronomeCurrentTick(): Int

    /** Volume das subdivisões em relação aos tempos (0..1). */
    external fun metronomeSetSubdivisionVolume(volume: Float)

    /** Começa a gravar em WAV mono 16 bits no caminho dado. */
    external fun recordStart(path: String): Boolean

    /** Para a gravação e devolve o número de quadros gravados. */
    external fun recordStop(): Long
    external fun recordIsActive(): Boolean
    external fun recordSeconds(): Double
    external fun recordSampleRate(): Int

    /** Pico do microfone desde a última chamada, de 0 a 1. */
    external fun recordTakePeak(): Float

    // ---------- Multipista ----------

    external fun engineSampleRate(): Int

    /** Carrega um WAV no motor (fica em cache). Devolve o tamanho em quadros a 48 kHz, ou -1. */
    external fun sourceLoad(path: String): Long
    external fun sourceForget(path: String)

    /** Picos (0..1) para desenhar a forma de onda. */
    external fun sourcePeaks(path: String, buckets: Int): FloatArray

    external fun projectBegin()
    external fun projectAddTrack(volume: Float, pan: Float, mute: Boolean, solo: Boolean)
    external fun projectAddClip(
        track: Int, path: String, start: Long, offset: Long, length: Long,
        fadeIn: Long, fadeOut: Long, gain: Float,
    ): Boolean
    external fun projectCommit()

    external fun transportPlay(fromFrame: Long, withClick: Boolean): Boolean
    external fun transportStop()
    external fun transportIsPlaying(): Boolean
    external fun transportPosition(): Long

    /** Repete o trecho entre A e B (quadros a 48 kHz). */
    external fun transportSetLoop(enabled: Boolean, a: Long, b: Long)

    /** Velocidade da reprodução sem mudar o tom: 0,25 a 1,5. Gravando, toca sempre em 1. */
    external fun transportSetSpeed(speed: Double)

    /** Renderiza o projeto publicado num WAV estéreo (sem metrônomo). Bloqueia: chame fora da thread principal. */
    external fun exportMix(path: String, frames: Long): Boolean

    /** Posição da linha do tempo quando o microfone começou a entregar áudio (-1 sem reprodução). */
    external fun recordStartPosition(): Long

    /** Latência de ida e volta estimada na última gravação, em quadros. */
    external fun recordRoundTripLatency(): Long
}
