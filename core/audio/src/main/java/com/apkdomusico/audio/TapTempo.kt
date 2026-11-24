package com.apkdomusico.audio

import kotlin.math.roundToInt

/**
 * Calcula o BPM a partir de toques na tela. Usa a média dos últimos intervalos
 * e recomeça se o músico ficar mais de [resetAfterMs] sem tocar.
 */
class TapTempo(
    private val maxTaps: Int = 6,
    private val resetAfterMs: Long = 2000,
    private val minBpm: Int = 30,
    private val maxBpm: Int = 300,
) {
    private val taps = ArrayDeque<Long>()

    /** Registra um toque no instante [nowMs]; devolve o BPM quando já há dois toques ou mais. */
    fun tap(nowMs: Long): Int? {
        val last = taps.lastOrNull()
        if (last != null && nowMs - last > resetAfterMs) taps.clear()
        taps.addLast(nowMs)
        while (taps.size > maxTaps) taps.removeFirst()
        if (taps.size < 2) return null
        val avgInterval = (taps.last() - taps.first()).toDouble() / (taps.size - 1)
        if (avgInterval <= 0) return null
        return (60_000.0 / avgInterval).roundToInt().coerceIn(minBpm, maxBpm)
    }

    fun reset() = taps.clear()
}
