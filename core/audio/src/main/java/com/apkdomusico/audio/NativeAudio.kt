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

    /** Começa a gravar em WAV mono 16 bits no caminho dado. */
    external fun recordStart(path: String): Boolean

    /** Para a gravação e devolve o número de quadros gravados. */
    external fun recordStop(): Long
    external fun recordIsActive(): Boolean
    external fun recordSeconds(): Double
    external fun recordSampleRate(): Int

    /** Pico do microfone desde a última chamada, de 0 a 1. */
    external fun recordTakePeak(): Float
}
