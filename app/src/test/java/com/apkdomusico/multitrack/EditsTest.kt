package com.apkdomusico.multitrack

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EditsTest {
    private fun projectWithClip(): Pair<Project, Clip> {
        var p = Edits.addTrack(Project(name = "Teste"))
        val clip = Clip(file = "a.wav", start = 1000, offset = 0, length = 4000, fadeIn = 100, fadeOut = 200)
        p = Edits.addClip(p, p.tracks[0].id, clip)
        return p to clip
    }

    @Test
    fun dividirGeraDoisClipesContinuos() {
        val (p, clip) = projectWithClip()
        val out = Edits.splitClip(p, clip.id, 2500)
        val clips = out.tracks[0].clips
        assertEquals(2, clips.size)
        assertEquals(1000L, clips[0].start)
        assertEquals(1500L, clips[0].length)
        assertEquals(0L, clips[0].fadeOut)
        assertEquals(2500L, clips[1].start)
        assertEquals(1500L, clips[1].offset)
        assertEquals(2500L, clips[1].length)
        assertEquals(0L, clips[1].fadeIn)
        assertEquals(200L, clips[1].fadeOut)
    }

    @Test
    fun dividirForaDoClipeNaoMuda() {
        val (p, clip) = projectWithClip()
        assertEquals(p, Edits.splitClip(p, clip.id, 500))
        assertEquals(p, Edits.splitClip(p, clip.id, 5000))
    }

    @Test
    fun cortarRespeitaOAudioDeOrigem() {
        val (p, clip) = projectWithClip()
        val out = Edits.trimClip(p, clip.id, newStart = 0, newEnd = 99_999, sourceFrames = 4000)
        val c = out.tracks[0].clips[0]
        assertEquals(1000L, c.start) // não dá para começar antes do áudio existir
        assertEquals(5000L, c.end)
        val out2 = Edits.trimClip(p, clip.id, newStart = 2000, newEnd = 3000, sourceFrames = 4000)
        val c2 = out2.tracks[0].clips[0]
        assertEquals(1000L, c2.offset)
        assertEquals(1000L, c2.length)
    }

    @Test
    fun moverNaoDeixaPosicaoNegativa() {
        val (p, clip) = projectWithClip()
        assertEquals(0L, Edits.moveClip(p, clip.id, -500).tracks[0].clips[0].start)
    }

    @Test
    fun moverParaOutraPista() {
        val (p0, clip) = projectWithClip()
        val p = Edits.addTrack(p0)
        val out = Edits.moveClipToTrack(p, clip.id, p.tracks[1].id)
        assertTrue(out.tracks[0].clips.isEmpty())
        assertEquals(clip, out.tracks[1].clips[0])
    }

    @Test
    fun limiteDePistas() {
        var p = Project(name = "x")
        repeat(10) { p = Edits.addTrack(p) }
        assertEquals(MAX_TRACKS, p.tracks.size)
    }

    @Test
    fun gradeDeTempo() {
        val p = Project(name = "x", bpm = 120) // 24000 quadros por tempo
        assertEquals(24_000L, Edits.snapToBeat(p, 20_000))
        assertEquals(48_000L, Edits.snapToBeat(p, 40_000))
    }

    @Test
    fun desfazerERefazer() {
        val h = History()
        val a = Project(name = "a")
        val b = a.copy(name = "b")
        h.push(a)
        assertEquals(a, h.undo(b))
        assertTrue(h.canRedo)
        assertEquals(b, h.redo(a))
        h.push(b)
        assertFalse(h.canRedo)
    }

    @Test
    fun duplicarPistaCopiaClipesComIdsNovos() {
        val (p, clip) = projectWithClip()
        val out = Edits.duplicateTrack(p, p.tracks[0].id)
        assertEquals(2, out.tracks.size)
        val copy = out.tracks[1].clips[0]
        assertEquals(clip.start, copy.start)
        assertTrue(copy.id != clip.id)
    }

    @Test
    fun moverClipeParaPistaDeBaixoEDeCima() {
        val (p0, clip) = projectWithClip()
        val p = Edits.addTrack(p0)
        val down = Edits.moveClipByTracks(p, clip.id, +1)
        assertEquals(clip, down.tracks[1].clips[0])
        assertEquals(down, Edits.moveClipByTracks(down, clip.id, +1)) // não há pista abaixo
        val up = Edits.moveClipByTracks(down, clip.id, -1)
        assertEquals(clip, up.tracks[0].clips[0])
    }

    @Test
    fun limparPista() {
        val (p, _) = projectWithClip()
        assertTrue(Edits.clearTrack(p, p.tracks[0].id).tracks[0].clips.isEmpty())
    }
}
