package com.apkdomusico.multitrack

import java.util.UUID

const val SAMPLE_RATE = 48_000
const val MAX_TRACKS = 8

fun newId(): String = UUID.randomUUID().toString().take(8)

/** Trecho de áudio numa pista. Tempos em quadros a 48 kHz. */
data class Clip(
    val id: String = newId(),
    val file: String,          // nome do arquivo dentro da pasta audio/ do projeto
    val start: Long,           // posição na linha do tempo
    val offset: Long = 0,      // início dentro do arquivo
    val length: Long,
    val fadeIn: Long = 0,
    val fadeOut: Long = 0,
    val gain: Float = 1f,
) {
    val end: Long get() = start + length
}

data class Track(
    val id: String = newId(),
    val name: String,
    val volume: Float = 1f,
    val pan: Float = 0f,
    val mute: Boolean = false,
    val solo: Boolean = false,
    val clips: List<Clip> = emptyList(),
)

data class Project(
    val id: String = newId(),
    val name: String,
    val bpm: Int = 100,
    val beatsPerBar: Int = 4,
    val tracks: List<Track> = emptyList(),
) {
    val endFrame: Long get() = tracks.flatMap { it.clips }.maxOfOrNull { it.end } ?: 0L

    fun framesPerBeat(): Double = SAMPLE_RATE * 60.0 / bpm

    fun clip(id: String): Pair<Track, Clip>? {
        for (t in tracks) t.clips.firstOrNull { it.id == id }?.let { return t to it }
        return null
    }
}

/** Operações de edição. Todas devolvem um projeto novo; nada é alterado no lugar. */
object Edits {
    fun addTrack(p: Project, name: String? = null): Project {
        if (p.tracks.size >= MAX_TRACKS) return p
        val n = name ?: "Pista ${p.tracks.size + 1}"
        return p.copy(tracks = p.tracks + Track(name = n))
    }

    fun updateTrack(p: Project, trackId: String, f: (Track) -> Track): Project =
        p.copy(tracks = p.tracks.map { if (it.id == trackId) f(it) else it })

    fun removeTrack(p: Project, trackId: String): Project =
        p.copy(tracks = p.tracks.filterNot { it.id == trackId })

    /** Cria uma cópia da pista logo abaixo dela, com cópias dos clipes. */
    fun duplicateTrack(p: Project, trackId: String): Project {
        if (p.tracks.size >= MAX_TRACKS) return p
        val index = p.tracks.indexOfFirst { it.id == trackId }
        if (index < 0) return p
        val src = p.tracks[index]
        val copy = src.copy(
            id = newId(),
            name = (src.name + " (cópia)").take(30),
            solo = false,
            clips = src.clips.map { it.copy(id = newId()) },
        )
        return p.copy(tracks = p.tracks.toMutableList().apply { add(index + 1, copy) })
    }

    fun clearTrack(p: Project, trackId: String): Project = updateTrack(p, trackId) { it.copy(clips = emptyList()) }

    /** Move o clipe para a pista vizinha ([delta] = -1 acima, +1 abaixo). */
    fun moveClipByTracks(p: Project, clipId: String, delta: Int): Project {
        val (track, _) = p.clip(clipId) ?: return p
        val target = p.tracks.indexOfFirst { it.id == track.id } + delta
        if (target !in p.tracks.indices) return p
        return moveClipToTrack(p, clipId, p.tracks[target].id)
    }

    fun addClip(p: Project, trackId: String, clip: Clip): Project =
        updateTrack(p, trackId) { it.copy(clips = it.clips + clip) }

    fun updateClip(p: Project, clipId: String, f: (Clip) -> Clip): Project =
        p.copy(tracks = p.tracks.map { t -> t.copy(clips = t.clips.map { if (it.id == clipId) f(it) else it }) })

    fun removeClip(p: Project, clipId: String): Project =
        p.copy(tracks = p.tracks.map { t -> t.copy(clips = t.clips.filterNot { it.id == clipId }) })

    fun moveClip(p: Project, clipId: String, newStart: Long): Project =
        updateClip(p, clipId) { it.copy(start = newStart.coerceAtLeast(0)) }

    /** Move o clipe para outra pista, mantendo a posição. */
    fun moveClipToTrack(p: Project, clipId: String, trackId: String): Project {
        val (from, clip) = p.clip(clipId) ?: return p
        if (from.id == trackId) return p
        return addClip(removeClip(p, clipId), trackId, clip)
    }

    /** Divide o clipe em [at] (posição na linha do tempo). Devolve o projeto sem mudança se [at] estiver fora. */
    fun splitClip(p: Project, clipId: String, at: Long): Project {
        val (track, clip) = p.clip(clipId) ?: return p
        if (at <= clip.start || at >= clip.end) return p
        val leftLen = at - clip.start
        val left = clip.copy(length = leftLen, fadeOut = 0)
        val right = clip.copy(
            id = newId(),
            start = at,
            offset = clip.offset + leftLen,
            length = clip.length - leftLen,
            fadeIn = 0,
        )
        return updateTrack(p, track.id) { t ->
            t.copy(clips = t.clips.flatMap { if (it.id == clipId) listOf(left, right) else listOf(it) })
        }
    }

    /** Corta o início ou o fim do clipe (posições na linha do tempo), sem passar dos limites do áudio. */
    fun trimClip(p: Project, clipId: String, newStart: Long, newEnd: Long, sourceFrames: Long): Project =
        updateClip(p, clipId) { c ->
            val minStart = c.start - c.offset
            val maxEnd = minStart + sourceFrames
            val s = newStart.coerceIn(maxOf(0, minStart), c.end - 1)
            val e = newEnd.coerceIn(s + 1, maxEnd)
            c.copy(start = s, offset = c.offset + (s - c.start), length = e - s,
                fadeIn = minOf(c.fadeIn, e - s), fadeOut = minOf(c.fadeOut, e - s))
        }

    fun duplicateClip(p: Project, clipId: String): Project {
        val (track, clip) = p.clip(clipId) ?: return p
        return addClip(p, track.id, clip.copy(id = newId(), start = clip.end))
    }

    fun setFades(p: Project, clipId: String, fadeIn: Long, fadeOut: Long): Project =
        updateClip(p, clipId) {
            it.copy(fadeIn = fadeIn.coerceIn(0, it.length), fadeOut = fadeOut.coerceIn(0, it.length))
        }

    /** Ajusta [frame] à batida mais próxima. */
    fun snapToBeat(p: Project, frame: Long): Long {
        val fpb = p.framesPerBeat()
        return (Math.round(frame / fpb) * fpb).toLong().coerceAtLeast(0)
    }
}

/** Histórico simples para desfazer e refazer. */
class History(private val limit: Int = 50) {
    private val undo = ArrayDeque<Project>()
    private val redo = ArrayDeque<Project>()

    val canUndo get() = undo.isNotEmpty()
    val canRedo get() = redo.isNotEmpty()

    fun push(before: Project) {
        undo.addLast(before)
        while (undo.size > limit) undo.removeFirst()
        redo.clear()
    }

    fun undo(current: Project): Project? {
        val prev = undo.removeLastOrNull() ?: return null
        redo.addLast(current)
        return prev
    }

    fun redo(current: Project): Project? {
        val next = redo.removeLastOrNull() ?: return null
        undo.addLast(current)
        return next
    }

    fun clear() {
        undo.clear()
        redo.clear()
    }
}
