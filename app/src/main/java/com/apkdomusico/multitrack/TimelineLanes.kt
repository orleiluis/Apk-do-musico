package com.apkdomusico.multitrack

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

fun Modifier.onSizeChangedPx(block: (Int) -> Unit): Modifier = onSizeChanged { block(it.width) }

private fun framesToX(frames: Long, pps: Float) = frames / SAMPLE_RATE.toFloat() * pps
private fun xToFrames(x: Float, pps: Float) = (x / pps * SAMPLE_RATE).toLong()

/** Desenha a grade de compassos e tempos só na parte visível. */
private fun DrawScope.beatGrid(p: Project, pps: Float, from: Float, to: Float, bar: Color, beat: Color) {
    val beatPx = (p.framesPerBeat() / SAMPLE_RATE * pps).toFloat()
    if (beatPx <= 0f) return
    val showBeats = beatPx >= 8f
    var i = max(0, (from / beatPx).toInt() - 1)
    while (i * beatPx <= to) {
        val x = i * beatPx
        val isBar = i % p.beatsPerBar == 0
        if (isBar || showBeats) {
            drawLine(if (isBar) bar else beat, Offset(x, 0f), Offset(x, size.height), strokeWidth = if (isBar) 1.5f else 1f)
        }
        i++
    }
}

@Composable
fun Ruler(
    project: Project,
    pps: Float,
    position: Long,
    scrollPx: Int,
    viewportPx: Int,
    onSeek: (Long) -> Unit,
) {
    val density = LocalDensity.current
    val textColor = MaterialTheme.colorScheme.onSurfaceVariant.toArgb()
    val bar = MaterialTheme.colorScheme.outline
    val bg = MaterialTheme.colorScheme.surfaceVariant
    val playhead = MaterialTheme.colorScheme.primary
    val textPx = with(density) { 11.sp.toPx() }
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(28.dp)
            .pointerInput(pps) { detectTapGestures { onSeek(xToFrames(it.x, pps)) } },
    ) {
        drawRect(bg)
        val from = scrollPx.toFloat()
        val to = from + max(viewportPx, 1)
        val beatPx = (project.framesPerBeat() / SAMPLE_RATE * pps).toFloat()
        val barPx = beatPx * project.beatsPerBar
        // Mostra o número de 1 em 1, 2 em 2, 4 em 4… compassos conforme o zoom.
        var every = 1
        while (barPx * every < textPx * 4) every *= 2
        val paint = android.graphics.Paint().apply {
            color = textColor
            textSize = textPx
            isAntiAlias = true
        }
        var b = max(0, (from / barPx).toInt() - 1)
        while (b * barPx <= to) {
            if (b % every == 0) {
                val x = b * barPx
                drawLine(bar, Offset(x, size.height * 0.4f), Offset(x, size.height), strokeWidth = 1.5f)
                drawContext.canvas.nativeCanvas.drawText("${b + 1}", x + 4f, textPx + 2f, paint)
            }
            b++
        }
        val px = framesToX(position, pps)
        drawLine(playhead, Offset(px, 0f), Offset(px, size.height), strokeWidth = 3f)
    }
}

private enum class DragMode { Move, TrimStart, TrimEnd }

@Composable
fun TrackLane(
    vm: MultitrackViewModel,
    project: Project,
    track: Track,
    pps: Float,
    scrollPx: Int,
    viewportPx: Int,
) {
    val density = LocalDensity.current
    val edgePx = with(density) { 18.dp.toPx() }
    val colors = MaterialTheme.colorScheme
    val clipColor = colors.primary
    val selectedColor = colors.tertiary
    val waveColor = colors.onPrimaryContainer
    val laneBg = colors.surface
    val barColor = colors.outlineVariant
    val beatColor = colors.outlineVariant.copy(alpha = 0.4f)
    val recColor = colors.error

    // Arraste em andamento (aplicado só no fim, para gerar um único "desfazer").
    var dragClip by remember { mutableStateOf<String?>(null) }
    var dragMode by remember { mutableStateOf(DragMode.Move) }
    var dragDx by remember { mutableStateOf(0f) }

    val selectedId = vm.selectedClip

    Canvas(
        Modifier
            .fillMaxWidth()
            .height(80.dp)
            .pointerInput(track.clips, pps) {
                detectTapGestures { pos ->
                    val f = xToFrames(pos.x, pps)
                    val hit = track.clips.lastOrNull { f >= it.start && f < it.end }
                    if (hit != null) {
                        vm.applyTool(hit.id, f)
                    } else {
                        vm.selectedClip = null
                        vm.seek(vm.snap(f))
                    }
                    vm.armedTrack = track.id
                }
            }
            .pointerInput(track.clips, pps, selectedId) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    if (vm.tool != Tool.Select) return@awaitEachGesture
                    val sel = track.clips.firstOrNull { it.id == selectedId } ?: return@awaitEachGesture
                    val x0 = framesToX(sel.start, pps)
                    val x1 = framesToX(sel.end, pps)
                    val x = down.position.x
                    if (x < x0 - edgePx / 2 || x > x1 + edgePx / 2) return@awaitEachGesture
                    val mode = when {
                        abs(x - x0) < edgePx && x1 - x0 > edgePx * 3 -> DragMode.TrimStart
                        abs(x - x1) < edgePx && x1 - x0 > edgePx * 3 -> DragMode.TrimEnd
                        else -> DragMode.Move
                    }
                    var moved = false
                    dragClip = sel.id
                    dragMode = mode
                    dragDx = 0f
                    drag(down.id) { change ->
                        dragDx += change.position.x - change.previousPosition.x
                        if (abs(dragDx) > 4f) moved = true
                        change.consume()
                    }
                    if (moved) {
                        val delta = xToFrames(dragDx, pps)
                        when (mode) {
                            DragMode.Move -> vm.edit { Edits.moveClip(it, sel.id, vm.snap(sel.start + delta)) }
                            DragMode.TrimStart -> vm.edit {
                                Edits.trimClip(it, sel.id, vm.snap(sel.start + delta), sel.end, vm.sourceFrames[sel.file] ?: (sel.offset + sel.length))
                            }
                            DragMode.TrimEnd -> vm.edit {
                                Edits.trimClip(it, sel.id, sel.start, vm.snap(sel.end + delta), vm.sourceFrames[sel.file] ?: (sel.offset + sel.length))
                            }
                        }
                    }
                    dragClip = null
                    dragDx = 0f
                }
            },
    ) {
        drawRect(laneBg)
        val from = scrollPx.toFloat()
        val to = from + max(viewportPx, 1)
        beatGrid(project, pps, from, to, barColor, beatColor)
        drawLine(barColor, Offset(from, size.height - 0.5f), Offset(to, size.height - 0.5f))

        val h = size.height
        for (clip in track.clips) {
            var s = framesToX(clip.start, pps)
            var e = framesToX(clip.end, pps)
            if (clip.id == dragClip) {
                when (dragMode) {
                    DragMode.Move -> { s += dragDx; e += dragDx }
                    DragMode.TrimStart -> s = min(s + dragDx, e - 4f)
                    DragMode.TrimEnd -> e = max(e + dragDx, s + 4f)
                }
            }
            if (e < from || s > to) continue
            val selected = clip.id == selectedId
            val top = 4f
            val boxH = h - 8f
            drawRoundRect(
                color = (if (selected) selectedColor else clipColor).copy(alpha = 0.35f),
                topLeft = Offset(s, top),
                size = Size(e - s, boxH),
                cornerRadius = CornerRadius(8f, 8f),
            )
            // Forma de onda (200 picos por segundo de áudio).
            val pk = vm.peaks[clip.file]
            if (pk != null && pk.isNotEmpty()) {
                val mid = top + boxH / 2
                val x0 = max(s, from).toInt()
                val x1 = min(e, to).toInt()
                val shift = if (clip.id == dragClip && dragMode == DragMode.Move) dragDx else 0f
                val clipX = framesToX(clip.start, pps) + shift
                for (x in x0 until x1 step 2) {
                    val rel = xToFrames(x - clipX, pps)
                    val idx = ((clip.offset + rel) / (SAMPLE_RATE / 200)).toInt()
                    if (idx < 0 || idx >= pk.size) continue
                    var amp = pk[idx]
                    // Mostra o efeito dos fades e do ganho.
                    if (clip.fadeIn > 0 && rel < clip.fadeIn) amp *= rel.toFloat() / clip.fadeIn
                    val left = clip.length - rel
                    if (clip.fadeOut > 0 && left < clip.fadeOut) amp *= left.toFloat() / clip.fadeOut
                    amp = (amp * clip.gain).coerceIn(0f, 1f)
                    val half = max(1f, amp * boxH / 2)
                    drawLine(waveColor, Offset(x.toFloat(), mid - half), Offset(x.toFloat(), mid + half), strokeWidth = 1.5f)
                }
            }
            if (selected) {
                drawRoundRect(
                    color = selectedColor,
                    topLeft = Offset(s, top),
                    size = Size(e - s, boxH),
                    cornerRadius = CornerRadius(8f, 8f),
                    style = Stroke(width = 4f),
                )
                // Alças de corte
                drawRect(selectedColor, Offset(s, top + boxH / 3), Size(8f, boxH / 3))
                drawRect(selectedColor, Offset(e - 8f, top + boxH / 3), Size(8f, boxH / 3))
            }
        }

        // Gravação em andamento
        if (vm.recording && vm.armedTrack == track.id) {
            val s = framesToX(vm.recordStartedAt, pps)
            val e = framesToX(vm.position, pps)
            drawRect(recColor.copy(alpha = 0.3f), Offset(s, 4f), Size(max(2f, e - s), h - 8f))
        }

        val px = framesToX(vm.position, pps)
        drawLine(Color(0xFFE0A030), Offset(px, 0f), Offset(px, h), strokeWidth = 3f)
    }
}
