package com.apkdomusico.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TapTempoTest {
    @Test
    fun primeiroToqueNaoDaBpm() {
        assertNull(TapTempo().tap(1000))
    }

    @Test
    fun toquesACada500msDao120() {
        val t = TapTempo()
        var bpm: Int? = null
        for (i in 0 until 5) bpm = t.tap(1000L + i * 500)
        assertEquals(120, bpm)
    }

    @Test
    fun pausaLongaRecomeca() {
        val t = TapTempo()
        t.tap(0)
        t.tap(1000) // 60 BPM
        assertNull(t.tap(10_000))
        assertEquals(100, t.tap(10_600))
    }

    @Test
    fun limitaAoIntervaloDoMetronomo() {
        val t = TapTempo()
        t.tap(0)
        assertEquals(300, t.tap(50))
    }
}
