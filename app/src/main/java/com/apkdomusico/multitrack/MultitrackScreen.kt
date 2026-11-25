package com.apkdomusico.multitrack

import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import java.io.File
import kotlin.math.roundToInt

@Composable
fun MultitrackScreen(vm: MultitrackViewModel, modifier: Modifier = Modifier) {
    if (vm.project == null) ProjectList(vm, modifier) else Editor(vm, modifier)
}

// ---------- Lista de projetos ----------

@Composable
private fun ProjectList(vm: MultitrackViewModel, modifier: Modifier) {
    var creating by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<Project?>(null) }
    Column(
        modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Estúdio multipista", style = MaterialTheme.typography.titleLarge)
        Button(onClick = { creating = true }, modifier = Modifier.fillMaxWidth().height(52.dp)) {
            Text("Novo projeto", fontSize = 18.sp)
        }
        if (vm.projects.isEmpty()) {
            Text(
                "Crie um projeto para gravar várias pistas, importar uma base e exportar a mixagem.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(vm.projects, key = { it.id }) { p ->
                Card(Modifier.fillMaxWidth().clickable { vm.open(p.id) }) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(p.name, fontWeight = FontWeight.SemiBold)
                            Text(
                                "${p.tracks.size} pistas · ${p.bpm} BPM · ${formatClock(p.endFrame)}",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        TextButton(onClick = { deleting = p }) { Text("Apagar") }
                    }
                }
            }
        }
    }

    if (creating) {
        var name by remember { mutableStateOf("") }
        var bpm by remember { mutableStateOf("100") }
        AlertDialog(
            onDismissRequest = { creating = false },
            title = { Text("Novo projeto") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(name, { name = it }, label = { Text("Nome") }, singleLine = true)
                    OutlinedTextField(
                        bpm, { bpm = it.filter(Char::isDigit).take(3) }, label = { Text("BPM") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.createProject(name, bpm.toIntOrNull() ?: 100)
                    creating = false
                }) { Text("Criar") }
            },
            dismissButton = { TextButton(onClick = { creating = false }) { Text("Cancelar") } },
        )
    }

    deleting?.let { p ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Apagar \"${p.name}\"?") },
            text = { Text("As pistas e o áudio gravado neste projeto serão apagados.") },
            confirmButton = {
                TextButton(onClick = {
                    vm.deleteProject(p.id)
                    deleting = null
                }) { Text("Apagar") }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancelar") } },
        )
    }
}

// ---------- Editor ----------

private val HeaderWidth = 118.dp
private val RulerHeight = 28.dp
private val TrackHeight = 80.dp

@Composable
private fun Editor(vm: MultitrackViewModel, modifier: Modifier) {
    val project = vm.project ?: return
    val context = LocalContext.current
    val density = LocalDensity.current
    var settingsOpen by remember { mutableStateOf(false) }
    var trackDialog by remember { mutableStateOf<String?>(null) }
    var fadeDialog by remember { mutableStateOf(false) }
    var importDialog by remember { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importAudio(uri)
    }
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) vm.startRecording() else vm.message = "Sem permissão de microfone não dá para gravar."
    }

    val selected = vm.selectedClip?.let { project.clip(it) }
    val hScroll = rememberScrollState()
    var viewportPx by remember { mutableIntStateOf(0) }
    val pps = vm.pixelsPerSecond
    val totalSeconds = maxOf(60.0, (maxOf(project.endFrame, vm.position) / SAMPLE_RATE.toDouble()) + 30.0)
    val contentPx = (totalSeconds * pps).toFloat()
    val contentDp = with(density) { contentPx.toDp() }

    // Segue o cursor durante a reprodução.
    LaunchedEffect(vm.position, vm.playing) {
        if (!vm.playing || viewportPx == 0) return@LaunchedEffect
        val x = (vm.position / SAMPLE_RATE.toFloat() * pps).toInt()
        if (x > hScroll.value + viewportPx * 0.85f || x < hScroll.value) {
            hScroll.scrollTo((x - viewportPx * 0.2f).toInt().coerceAtLeast(0))
        }
    }

    Column(modifier.fillMaxSize()) {
        // Barra superior
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = vm::close) { Text("‹ Projetos") }
            Text(
                project.name,
                modifier = Modifier.weight(1f).clickable { settingsOpen = true },
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            TextButton(onClick = vm::undo, enabled = vm.canUndo) { Text("Desfazer") }
            TextButton(onClick = vm::redo, enabled = vm.canRedo) { Text("Refazer") }
        }

        // Transporte
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            OutlinedButton(onClick = { vm.seek(0) }, contentPadding = SmallPadding) { Text("⏮") }
            Button(onClick = vm::togglePlay, contentPadding = SmallPadding, enabled = !vm.recording) {
                Text(if (vm.playing && !vm.recording) "■ Parar" else "▶ Tocar")
            }
            Button(
                onClick = {
                    if (vm.recording) vm.stopRecording() else micPermission.launch(android.Manifest.permission.RECORD_AUDIO)
                },
                contentPadding = SmallPadding,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
            ) { Text(if (vm.recording) "■ Parar" else "● Gravar") }
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.End) {
                Text(formatBars(project, vm.position), fontWeight = FontWeight.Bold)
                Text(formatClock(vm.position), style = MaterialTheme.typography.bodySmall)
            }
        }
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilterChip(selected = vm.clickOn, onClick = { vm.clickOn = !vm.clickOn }, label = { Text("Clique ${project.bpm}") })
            OutlinedButton(
                onClick = { vm.clickSubdivision = listOf(1, 2, 3, 4, 6).let { l -> l[(l.indexOf(vm.clickSubdivision) + 1) % l.size] } },
                contentPadding = SmallPadding,
            ) { Text("Subdiv. ${subdivisionName(vm.clickSubdivision)}") }
            FilterChip(selected = vm.snapOn, onClick = { vm.snapOn = !vm.snapOn }, label = { Text("Grade") })
            OutlinedButton(onClick = { vm.pixelsPerSecond = (pps / 1.5f).coerceAtLeast(10f) }, contentPadding = SmallPadding) { Text("−") }
            Text("Zoom")
            OutlinedButton(onClick = { vm.pixelsPerSecond = (pps * 1.5f).coerceAtMost(800f) }, contentPadding = SmallPadding) { Text("+") }
            OutlinedButton(onClick = { importDialog = true }, contentPadding = SmallPadding) { Text("Importar") }
            OutlinedButton(onClick = vm::addTrack, contentPadding = SmallPadding, enabled = project.tracks.size < MAX_TRACKS) {
                Text("+ Pista")
            }
            OutlinedButton(onClick = { vm.exportMix { share(context, it, "Compartilhar mixagem") } }, contentPadding = SmallPadding) {
                Text("Exportar WAV")
            }
        }

        if (vm.recording) {
            LinearProgressIndicator(
                progress = { vm.inputLevel.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp).height(6.dp),
                color = MaterialTheme.colorScheme.error,
            )
        }
        vm.busy?.let { Text(it, Modifier.padding(horizontal = 12.dp), style = MaterialTheme.typography.bodySmall) }
        vm.message?.let {
            Text(
                it,
                Modifier.padding(horizontal = 12.dp).clickable { vm.message = null },
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }

        // Pistas e linha do tempo
        Row(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
        ) {
            Column(Modifier.width(HeaderWidth)) {
                Spacer(Modifier.height(RulerHeight))
                project.tracks.forEach { t ->
                    TrackHeader(
                        track = t,
                        armed = vm.armedTrack == t.id,
                        onOpen = { trackDialog = t.id },
                        onMute = { vm.edit { p -> Edits.updateTrack(p, t.id) { it.copy(mute = !it.mute) } } },
                        onSolo = { vm.edit { p -> Edits.updateTrack(p, t.id) { it.copy(solo = !it.solo) } } },
                        onArm = { vm.armedTrack = t.id },
                    )
                }
            }
            Box(
                Modifier
                    .weight(1f)
                    .onSizeChangedPx { viewportPx = it }
                    .horizontalScroll(hScroll),
            ) {
                Column(Modifier.width(contentDp)) {
                    Ruler(
                        project = project,
                        pps = pps,
                        position = vm.position,
                        scrollPx = hScroll.value,
                        viewportPx = viewportPx,
                        onSeek = { vm.seek(vm.snap(it)) },
                    )
                    project.tracks.forEach { t ->
                        TrackLane(
                            vm = vm,
                            project = project,
                            track = t,
                            pps = pps,
                            scrollPx = hScroll.value,
                            viewportPx = viewportPx,
                        )
                    }
                }
            }
        }

        // Ferramentas de edição
        val hasSel = selected != null
        Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant)) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Tool.entries.forEach { t ->
                    FilterChip(selected = vm.tool == t, onClick = { vm.tool = t }, label = { Text(t.label) })
                }
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = vm::splitSelectedAtPlayhead, enabled = hasSel) { Text("Dividir no cursor") }
                TextButton(onClick = vm::copySelected, enabled = hasSel) { Text("Copiar") }
                TextButton(onClick = vm::paste, enabled = vm.clipboard != null) { Text("Colar no cursor") }
                TextButton(onClick = vm::duplicateSelected, enabled = hasSel) { Text("Duplicar") }
                TextButton(onClick = { fadeDialog = true }, enabled = hasSel) { Text("Fades e ganho") }
                TextButton(onClick = { vm.moveSelectedTrack(-1) }, enabled = hasSel) { Text("↑ Pista") }
                TextButton(onClick = { vm.moveSelectedTrack(+1) }, enabled = hasSel) { Text("↓ Pista") }
                TextButton(onClick = vm::deleteSelected, enabled = hasSel) { Text("Apagar") }
            }
            Text(
                vm.tool.hint,
                Modifier.padding(horizontal = 10.dp, vertical = 2.dp),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }

    if (settingsOpen) ProjectSettingsDialog(vm, project) { settingsOpen = false }
    trackDialog?.let { id ->
        project.tracks.firstOrNull { it.id == id }?.let { TrackDialog(vm, it) { trackDialog = null } }
            ?: run { trackDialog = null }
    }
    if (fadeDialog && selected != null) FadeDialog(vm, selected.second) { fadeDialog = false }
    if (importDialog) {
        ImportDialog(
            vm = vm,
            onPickFile = {
                importDialog = false
                picker.launch(arrayOf("audio/*"))
            },
            onDismiss = { importDialog = false },
        )
    }
}

private val SmallPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 4.dp)

@Composable
private fun TrackHeader(
    track: Track,
    armed: Boolean,
    onOpen: () -> Unit,
    onMute: () -> Unit,
    onSolo: () -> Unit,
    onArm: () -> Unit,
) {
    Column(
        Modifier
            .width(HeaderWidth)
            .height(TrackHeight)
            .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant)
            .padding(4.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                track.name,
                modifier = Modifier.weight(1f).clickable(onClick = onOpen),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontWeight = FontWeight.SemiBold,
                fontSize = 13.sp,
            )
            Text(
                "⋮",
                Modifier.clickable(onClick = onOpen).padding(horizontal = 6.dp),
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Toggle("M", track.mute, MaterialTheme.colorScheme.tertiary, onMute)
            Toggle("S", track.solo, MaterialTheme.colorScheme.primary, onSolo)
            Toggle("●", armed, MaterialTheme.colorScheme.error, onArm)
        }
        Text(
            "Vol ${(track.volume * 100).roundToInt()}%",
            fontSize = 11.sp,
            modifier = Modifier.clickable(onClick = onOpen),
        )
    }
}

@Composable
private fun Toggle(label: String, on: Boolean, color: androidx.compose.ui.graphics.Color, onClick: () -> Unit) {
    Box(
        Modifier
            .size(30.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(if (on) color else MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            color = if (on) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ---------- Diálogos ----------

@Composable
private fun ProjectSettingsDialog(vm: MultitrackViewModel, project: Project, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(project.name) }
    var bpm by remember { mutableFloatStateOf(project.bpm.toFloat()) }
    var beats by remember { mutableFloatStateOf(project.beatsPerBar.toFloat()) }
    var latency by remember { mutableFloatStateOf(vm.latencyAdjustMs) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Projeto") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Nome") }, singleLine = true)
                Text("BPM: ${bpm.roundToInt()}")
                Slider(bpm, { bpm = it }, valueRange = 30f..300f)
                Text("Tempos por compasso: ${beats.roundToInt()}")
                Slider(beats, { beats = it }, valueRange = 1f..12f, steps = 10)
                Text("Ajuste de latência da gravação: ${latency.roundToInt()} ms")
                Slider(latency, { latency = it }, valueRange = -100f..300f)
                Text(
                    "Se as gravações ficarem atrasadas em relação à base, aumente. Se ficarem adiantadas, diminua.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                vm.edit { it.copy(name = name.ifBlank { it.name }, bpm = bpm.roundToInt(), beatsPerBar = beats.roundToInt()) }
                vm.setLatencyAdjust(latency.roundToInt().toFloat())
                onDismiss()
            }) { Text("Salvar") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}

@Composable
private fun TrackDialog(vm: MultitrackViewModel, track: Track, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(track.name) }
    var volume by remember { mutableFloatStateOf(track.volume) }
    var pan by remember { mutableFloatStateOf(track.pan) }
    var confirmDelete by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Pista") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Nome") }, singleLine = true)
                Text("Volume: ${(volume * 100).roundToInt()}%")
                Slider(volume, { volume = it }, valueRange = 0f..2f)
                Text("Pan: " + when {
                    pan < -0.02f -> "${(-pan * 100).roundToInt()}% esquerda"
                    pan > 0.02f -> "${(pan * 100).roundToInt()}% direita"
                    else -> "centro"
                })
                Slider(pan, { pan = it }, valueRange = -1f..1f)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(onClick = {
                        vm.edit { Edits.duplicateTrack(it, track.id) }
                        onDismiss()
                    }) { Text("Duplicar pista") }
                    OutlinedButton(onClick = {
                        vm.edit { Edits.clearTrack(it, track.id) }
                        onDismiss()
                    }) { Text("Limpar") }
                }
                TextButton(onClick = { confirmDelete = true }) {
                    Text(if (confirmDelete) "Toque de novo para apagar a pista" else "Apagar pista", color = MaterialTheme.colorScheme.error)
                }
                if (confirmDelete) {
                    Button(
                        onClick = {
                            vm.edit { Edits.removeTrack(it, track.id) }
                            onDismiss()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    ) { Text("Apagar \"${track.name}\"") }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                vm.edit { p -> Edits.updateTrack(p, track.id) { it.copy(name = name.ifBlank { it.name }, volume = volume, pan = pan) } }
                onDismiss()
            }) { Text("Salvar") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}

@Composable
private fun FadeDialog(vm: MultitrackViewModel, clip: Clip, onDismiss: () -> Unit) {
    val maxFadeSec = minOf(clip.length / 2.0 / SAMPLE_RATE, 10.0).toFloat().coerceAtLeast(0.01f)
    var fin by remember { mutableFloatStateOf((clip.fadeIn / SAMPLE_RATE.toFloat()).coerceAtMost(maxFadeSec)) }
    var fout by remember { mutableFloatStateOf((clip.fadeOut / SAMPLE_RATE.toFloat()).coerceAtMost(maxFadeSec)) }
    var gain by remember { mutableFloatStateOf(clip.gain) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Fades e ganho") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Fade de entrada: ${"%.2f".format(fin)} s")
                Slider(fin, { fin = it }, valueRange = 0f..maxFadeSec)
                Text("Fade de saída: ${"%.2f".format(fout)} s")
                Slider(fout, { fout = it }, valueRange = 0f..maxFadeSec)
                Text("Ganho do clipe: ${(gain * 100).roundToInt()}%")
                Slider(gain, { gain = it }, valueRange = 0f..2f)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                vm.edit { p ->
                    Edits.updateClip(Edits.setFades(p, clip.id, (fin * SAMPLE_RATE).toLong(), (fout * SAMPLE_RATE).toLong()), clip.id) {
                        it.copy(gain = gain)
                    }
                }
                onDismiss()
            }) { Text("Aplicar") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}

@Composable
private fun ImportDialog(vm: MultitrackViewModel, onPickFile: () -> Unit, onDismiss: () -> Unit) {
    val recordings = remember { vm.recorderFiles() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Importar áudio") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onPickFile, modifier = Modifier.fillMaxWidth()) { Text("Arquivo do celular (MP3, WAV, M4A, FLAC…)") }
                if (recordings.isNotEmpty()) {
                    Text("Ou uma gravação do Gravador:", style = MaterialTheme.typography.labelLarge)
                    LazyColumn(Modifier.height(220.dp)) {
                        items(recordings) { f ->
                            Text(
                                f.nameWithoutExtension,
                                Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        vm.importRecording(f)
                                        onDismiss()
                                    }
                                    .padding(vertical = 10.dp),
                            )
                        }
                    }
                }
                Text(
                    "O áudio entra na posição do cursor, numa pista vazia (ou numa pista nova).",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Fechar") } },
    )
}

// ---------- Utilidades ----------

fun share(context: Context, file: File, title: String) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "audio/wav"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, title))
}

fun subdivisionName(n: Int) = when (n) {
    1 -> "não"
    2 -> "colcheia"
    3 -> "tercina"
    4 -> "semicolcheia"
    6 -> "sextina"
    else -> "$n"
}

fun formatClock(frames: Long): String {
    val totalMs = frames * 1000 / SAMPLE_RATE
    val m = totalMs / 60_000
    val s = (totalMs / 1000) % 60
    val t = (totalMs / 100) % 10
    return "%d:%02d.%d".format(m, s, t)
}

fun formatBars(p: Project, frames: Long): String {
    val beat = (frames / p.framesPerBeat()).toLong()
    return "Compasso ${beat / p.beatsPerBar + 1}.${beat % p.beatsPerBar + 1}"
}
