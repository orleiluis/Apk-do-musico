package com.apkdomusico.multitrack

import android.app.Application
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.apkdomusico.audio.NativeAudio
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class Tool(val label: String, val hint: String) {
    Select("↖ Selecionar", "Toque num clipe para selecionar. Arraste o clipe selecionado para mover, ou as bordas para cortar."),
    Split("✂ Tesoura", "Toque num clipe no ponto onde quer dividir."),
    Erase("⌫ Borracha", "Toque num clipe para apagar."),
}

@OptIn(ExperimentalCoroutinesApi::class)
class MultitrackViewModel(app: Application) : AndroidViewModel(app) {
    private val store = ProjectStore(app)
    private val prefs = app.getSharedPreferences("estudio", Context.MODE_PRIVATE)

    /** Uma fila só para falar com o motor, para as mudanças chegarem em ordem. */
    private val engineDispatcher = Dispatchers.IO.limitedParallelism(1)

    var projects by mutableStateOf<List<Project>>(emptyList())
        private set
    var project by mutableStateOf<Project?>(null)
        private set
    var selectedClip by mutableStateOf<String?>(null)
    var armedTrack by mutableStateOf<String?>(null)
    var playing by mutableStateOf(false)
        private set
    var recording by mutableStateOf(false)
        private set
    var position by mutableLongStateOf(0L)
        private set
    var recordStartedAt by mutableLongStateOf(0L)
        private set
    var inputLevel by mutableFloatStateOf(0f)
        private set
    var tool by mutableStateOf(Tool.Select)
    var clipboard by mutableStateOf<Clip?>(null)
        private set
    var clickOn by mutableStateOf(true)
    var clickSubdivision by mutableStateOf(1)
    var snapOn by mutableStateOf(true)
    var pixelsPerSecond by mutableFloatStateOf(80f)

    /** Looper: repete o trecho entre A e B (quadros; -1 = não marcado). */
    var loopOn by mutableStateOf(false)
        private set
    var loopA by mutableLongStateOf(-1L)
        private set
    var loopB by mutableLongStateOf(-1L)
        private set

    /** Velocidade da reprodução sem mudar o tom (1 = normal). */
    var speed by mutableFloatStateOf(1f)
        private set
    var busy by mutableStateOf<String?>(null)
        private set
    var message by mutableStateOf<String?>(null)
    var canUndo by mutableStateOf(false)
        private set
    var canRedo by mutableStateOf(false)
        private set

    /** Ajuste manual de latência em milissegundos, por aparelho. */
    var latencyAdjustMs by mutableFloatStateOf(prefs.getFloat("latenciaMs", 0f))
        private set

    val peaks = mutableStateMapOf<String, FloatArray>()
    val sourceFrames = mutableMapOf<String, Long>()

    private val history = History()
    private var pollJob: Job? = null
    private var recordingFile: String? = null

    init {
        refreshProjects()
    }

    fun refreshProjects() {
        projects = store.list()
    }

    fun createProject(name: String, bpm: Int) {
        val p = store.create(name.ifBlank { "Projeto ${projects.size + 1}" }, bpm.coerceIn(30, 300))
        refreshProjects()
        open(p.id)
    }

    fun deleteProject(id: String) {
        if (project?.id == id) close()
        store.delete(id)
        refreshProjects()
    }

    fun open(id: String) {
        val p = store.load(id) ?: return
        history.clear()
        updateUndo()
        project = p
        selectedClip = null
        armedTrack = p.tracks.firstOrNull()?.id
        position = 0
        peaks.clear()
        sourceFrames.clear()
        syncEngine(p)
        clearLoop()
        changeSpeed(1f)
    }

    fun close() {
        stop()
        project = null
        refreshProjects()
    }

    fun audioPath(file: String): String = store.audioFile(project!!.id, file).absolutePath

    // ---------- Edição ----------

    /** Aplica uma edição com histórico de desfazer, salva e manda para o motor. */
    fun edit(f: (Project) -> Project) {
        val before = project ?: return
        val after = f(before)
        if (after == before) return
        history.push(before)
        apply(after)
    }

    private fun apply(p: Project) {
        project = p
        updateUndo()
        viewModelScope.launch(Dispatchers.IO) { store.save(p) }
        syncEngine(p)
    }

    fun undo() {
        val p = project ?: return
        history.undo(p)?.let { apply(it) }
    }

    fun redo() {
        val p = project ?: return
        history.redo(p)?.let { apply(it) }
    }

    private fun updateUndo() {
        canUndo = history.canUndo
        canRedo = history.canRedo
    }

    private fun syncEngine(p: Project) {
        val id = p.id
        viewModelScope.launch(engineDispatcher) {
            NativeAudio.projectBegin()
            p.tracks.forEachIndexed { index, t ->
                NativeAudio.projectAddTrack(t.volume, t.pan, t.mute, t.solo)
                t.clips.forEach { c ->
                    val path = store.audioFile(id, c.file).absolutePath
                    NativeAudio.projectAddClip(index, path, c.start, c.offset, c.length, c.fadeIn, c.fadeOut, c.gain)
                }
            }
            NativeAudio.projectCommit()
            // Formas de onda dos arquivos novos.
            val files = p.tracks.flatMap { t -> t.clips.map { it.file } }.toSet()
            for (file in files) {
                if (peaks.containsKey(file)) continue
                val path = store.audioFile(id, file).absolutePath
                val frames = NativeAudio.sourceLoad(path)
                if (frames <= 0) continue
                val buckets = (frames / (SAMPLE_RATE / 200)).toInt().coerceIn(1, 200_000) // 200 por segundo
                val pk = NativeAudio.sourcePeaks(path, buckets)
                withContext(Dispatchers.Main) {
                    sourceFrames[file] = frames
                    peaks[file] = pk
                }
            }
        }
    }

    fun setLatencyAdjust(ms: Float) {
        latencyAdjustMs = ms
        prefs.edit().putFloat("latenciaMs", ms).apply()
    }

    fun changeBpm(bpm: Int) = edit { it.copy(bpm = bpm.coerceIn(30, 300)) }
    fun changeBeats(beats: Int) = edit { it.copy(beatsPerBar = beats.coerceIn(1, 12)) }
    fun rename(name: String) = edit { it.copy(name = name.ifBlank { it.name }) }
    fun addTrack() = edit { Edits.addTrack(it) }

    fun splitSelectedAtPlayhead() {
        val id = selectedClip ?: return
        edit { Edits.splitClip(it, id, position) }
    }

    fun deleteSelected() {
        val id = selectedClip ?: return
        edit { Edits.removeClip(it, id) }
        selectedClip = null
    }

    fun copySelected() {
        val id = selectedClip ?: return
        clipboard = project?.clip(id)?.second
    }

    /** Cola o clipe copiado na posição do cursor, na pista armada (ou na do clipe selecionado). */
    fun paste() {
        val p = project ?: return
        val c = clipboard ?: return
        val trackId = armedTrack?.takeIf { id -> p.tracks.any { it.id == id } }
            ?: selectedClip?.let { p.clip(it)?.first?.id }
            ?: p.tracks.firstOrNull()?.id
            ?: return
        val clip = c.copy(id = newId(), start = snap(position))
        edit { Edits.addClip(it, trackId, clip) }
        selectedClip = clip.id
    }

    fun moveSelectedTrack(delta: Int) {
        val id = selectedClip ?: return
        edit { Edits.moveClipByTracks(it, id, delta) }
    }

    /** Ação da ferramenta ativa ao tocar num clipe. */
    fun applyTool(clipId: String, frame: Long) {
        when (tool) {
            Tool.Select -> selectedClip = clipId
            Tool.Split -> {
                edit { Edits.splitClip(it, clipId, snap(frame)) }
                selectedClip = clipId
            }
            Tool.Erase -> {
                edit { Edits.removeClip(it, clipId) }
                if (selectedClip == clipId) selectedClip = null
            }
        }
    }

    fun duplicateSelected() {
        val id = selectedClip ?: return
        edit { Edits.duplicateClip(it, id) }
    }

    fun snap(frame: Long): Long = project?.let { if (snapOn) Edits.snapToBeat(it, frame) else frame } ?: frame

    // ---------- Looper, A/B e velocidade ----------

    private fun barFrames(): Long = project?.let { (it.framesPerBeat() * it.beatsPerBar).toLong() } ?: SAMPLE_RATE.toLong()

    private fun syncLoop() {
        val valid = loopOn && loopA >= 0 && loopB > loopA
        NativeAudio.transportSetLoop(valid, loopA.coerceAtLeast(0), loopB.coerceAtLeast(0))
    }

    /** Liga ou desliga o looper. Sem A/B marcados, repete 2 compassos a partir do compasso do cursor. */
    fun toggleLoop() {
        if (!loopOn && (loopA < 0 || loopB <= loopA)) {
            val bar = barFrames()
            loopA = (position / bar) * bar
            loopB = loopA + 2 * bar
        }
        loopOn = !loopOn
        syncLoop()
    }

    /** Marca A no cursor. */
    fun markA() {
        loopA = snap(position)
        if (loopB in 0..loopA) loopB = loopA + barFrames()
        if (loopB < 0) loopB = loopA + 2 * barFrames()
        syncLoop()
    }

    /** Marca B no cursor e liga o looper. */
    fun markB() {
        var b = snap(position)
        if (b <= 0) b = barFrames()
        loopB = b
        if (loopA < 0 || loopA >= loopB) loopA = (loopB - barFrames()).coerceAtLeast(0)
        loopOn = true
        syncLoop()
    }

    fun clearLoop() {
        loopOn = false
        loopA = -1
        loopB = -1
        syncLoop()
    }

    fun changeSpeed(value: Float) {
        speed = (Math.round(value * 100f) / 100f).coerceIn(0.25f, 1.5f)
        NativeAudio.transportSetSpeed(speed.toDouble())
    }

    // ---------- Transporte ----------

    fun seek(frame: Long) {
        val f = frame.coerceAtLeast(0)
        position = f
        if (playing && !recording) {
            stop()
            play()
        }
    }

    fun togglePlay() = if (playing) stop() else play()

    fun play() {
        val p = project ?: return
        if (playing) return
        prepareClick(p)
        viewModelScope.launch(engineDispatcher) {
            val ok = NativeAudio.transportPlay(position, clickOn)
            withContext(Dispatchers.Main) {
                if (!ok) {
                    message = "Não foi possível abrir a saída de áudio."
                } else {
                    playing = true
                    startPolling()
                }
            }
        }
    }

    fun stop() {
        if (recording) {
            stopRecording()
            return
        }
        if (!playing) return
        pollJob?.cancel()
        playing = false
        viewModelScope.launch(engineDispatcher) {
            NativeAudio.transportStop()
        }
    }

    private fun prepareClick(p: Project) {
        NativeAudio.metronomeSetBpm(p.bpm.toDouble())
        NativeAudio.metronomeSetBeatsPerBar(p.beatsPerBar)
        NativeAudio.metronomeSetSubdivision(clickSubdivision)
        NativeAudio.metronomeSetAccent(true)
    }

    private fun startPolling() {
        pollJob?.cancel()
        pollJob = viewModelScope.launch {
            while (isActive) {
                position = NativeAudio.transportPosition()
                if (recording) inputLevel = maxOf(NativeAudio.recordTakePeak(), inputLevel * 0.85f)
                delay(30)
            }
        }
    }

    // ---------- Gravação ----------

    fun startRecording() {
        val p0 = project ?: return
        if (recording) return
        var p = p0
        if (p.tracks.isEmpty()) {
            edit { Edits.addTrack(it) }
            p = project!!
        }
        val trackId = armedTrack?.takeIf { id -> p.tracks.any { it.id == id } } ?: p.tracks.first().id
        armedTrack = trackId
        if (playing) {
            pollJob?.cancel()
            playing = false
            NativeAudio.transportStop()
        }
        val name = "grav_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".wav"
        val path = store.audioFile(p.id, name).absolutePath
        prepareClick(p)
        recordStartedAt = position
        viewModelScope.launch(engineDispatcher) {
            val played = NativeAudio.transportPlay(position, clickOn)
            val rec = played && NativeAudio.recordStart(path)
            withContext(Dispatchers.Main) {
                if (!rec) {
                    if (played) NativeAudio.transportStop()
                    message = "Não foi possível abrir o microfone."
                    return@withContext
                }
                recordingFile = name
                recording = true
                playing = true
                startPolling()
            }
        }
    }

    fun stopRecording() {
        if (!recording) return
        val file = recordingFile ?: return
        val trackId = armedTrack
        recording = false
        playing = false
        pollJob?.cancel()
        inputLevel = 0f
        viewModelScope.launch(engineDispatcher) {
            NativeAudio.recordStop()
            val startPos = NativeAudio.recordStartPosition()
            val latency = NativeAudio.recordRoundTripLatency()
            NativeAudio.transportStop()
            val path = store.audioFile(project!!.id, file).absolutePath
            NativeAudio.sourceForget(path)
            val frames = NativeAudio.sourceLoad(path)
            withContext(Dispatchers.Main) {
                if (frames <= 0 || trackId == null) {
                    message = "A gravação ficou vazia."
                    return@withContext
                }
                val manual = (latencyAdjustMs * SAMPLE_RATE / 1000f).toLong()
                var start = (if (startPos >= 0) startPos else recordStartedAt) - latency - manual
                var offset = 0L
                if (start < 0) {
                    offset = -start
                    start = 0
                }
                val length = frames - offset
                if (length <= 0) return@withContext
                val clip = Clip(file = file, start = start, offset = offset, length = length)
                edit { Edits.addClip(it, trackId, clip) }
                selectedClip = clip.id
                position = recordStartedAt
            }
        }
    }

    // ---------- Importar e exportar ----------

    fun importAudio(uri: Uri) {
        val p = project ?: return
        val context = getApplication<Application>()
        val display = displayName(context, uri) ?: "audio"
        val name = "imp_" + System.currentTimeMillis() + ".wav"
        val out = store.audioFile(p.id, name)
        busy = "Importando $display…"
        viewModelScope.launch(Dispatchers.IO) {
            val result = runCatching { AudioImporter.decodeToWav(context, uri, out) }
            val frames = if (result.isSuccess) NativeAudio.sourceLoad(out.absolutePath) else -1
            withContext(Dispatchers.Main) {
                busy = null
                if (frames <= 0) {
                    out.delete()
                    message = "Não foi possível importar $display. " + (result.exceptionOrNull()?.message ?: "")
                    return@withContext
                }
                addImportedClip(name, frames, display.substringBeforeLast('.'))
            }
        }
    }

    /** Gravações feitas na aba Gravador. */
    fun recorderFiles(): List<File> {
        val dir = getApplication<Application>().getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: return emptyList()
        return dir.listFiles { f -> f.extension.equals("wav", true) }?.sortedByDescending { it.lastModified() }
            ?: emptyList()
    }

    fun importRecording(file: File) {
        val p = project ?: return
        val name = "imp_" + System.currentTimeMillis() + ".wav"
        val out = store.audioFile(p.id, name)
        viewModelScope.launch(Dispatchers.IO) {
            file.copyTo(out, overwrite = true)
            val frames = NativeAudio.sourceLoad(out.absolutePath)
            withContext(Dispatchers.Main) {
                if (frames > 0) addImportedClip(name, frames, file.nameWithoutExtension)
                else message = "Não foi possível abrir ${file.name}."
            }
        }
    }

    private fun addImportedClip(file: String, frames: Long, label: String) {
        val p = project ?: return
        val target = p.tracks.firstOrNull { it.id == armedTrack && it.clips.isEmpty() }
            ?: p.tracks.firstOrNull { it.clips.isEmpty() }
        val clip = Clip(file = file, start = position, length = frames)
        if (target != null) {
            edit { Edits.addClip(Edits.updateTrack(it, target.id) { t -> t.copy(name = label.take(24)) }, target.id, clip) }
        } else if (p.tracks.size < MAX_TRACKS) {
            edit {
                val withTrack = Edits.addTrack(it, label.take(24))
                Edits.addClip(withTrack, withTrack.tracks.last().id, clip)
            }
        } else {
            val track = p.tracks.firstOrNull { it.id == armedTrack } ?: p.tracks.first()
            edit { Edits.addClip(it, track.id, clip) }
        }
        selectedClip = clip.id
    }

    /** Exporta a mixagem em WAV na pasta Música/Exportados do app. Chama [onDone] com o arquivo. */
    fun exportMix(onDone: (File) -> Unit) {
        val p = project ?: return
        if (p.endFrame <= 0) {
            message = "O projeto ainda não tem áudio."
            return
        }
        stop()
        val dir = File(
            getApplication<Application>().getExternalFilesDir(Environment.DIRECTORY_MUSIC),
            "Exportados",
        ).also { it.mkdirs() }
        val safe = p.name.replace(Regex("[^\\p{L}\\p{N} _-]"), "_").trim().ifBlank { "mixagem" }
        val out = File(dir, "$safe.wav")
        busy = "Exportando…"
        viewModelScope.launch(engineDispatcher) {
            val ok = NativeAudio.exportMix(out.absolutePath, p.endFrame + SAMPLE_RATE / 2)
            withContext(Dispatchers.Main) {
                busy = null
                if (ok) onDone(out) else message = "Não foi possível exportar."
            }
        }
    }

    private fun displayName(context: Context, uri: Uri): String? =
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }

    override fun onCleared() {
        if (recording) NativeAudio.recordStop()
        NativeAudio.transportStop()
    }
}
