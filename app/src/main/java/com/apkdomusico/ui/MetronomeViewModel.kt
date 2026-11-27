package com.apkdomusico.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.apkdomusico.audio.NativeAudio
import com.apkdomusico.audio.TapTempo
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class TimeSignature(val label: String, val beats: Int)

val TimeSignatures = listOf(
    TimeSignature("1/1", 1),
    TimeSignature("2/4", 2),
    TimeSignature("3/4", 3),
    TimeSignature("4/4", 4),
    TimeSignature("5/4", 5),
    TimeSignature("6/8", 6),
    TimeSignature("7/8", 7),
)

data class Subdivision(val label: String, val value: Int)

val Subdivisions = listOf(
    Subdivision("Semínima", 1),
    Subdivision("Colcheia", 2),
    Subdivision("Tercina", 3),
    Subdivision("Semicolcheia", 4),
    Subdivision("Sextina", 6),
)

class MetronomeViewModel(app: Application) : AndroidViewModel(app) {
    var bpm by mutableIntStateOf(100)
        private set
    var signature by mutableStateOf(TimeSignatures[2])
        private set
    var subdivision by mutableStateOf(Subdivisions[0])
        private set
    var accent by mutableStateOf(true)
        private set
    var volume by mutableFloatStateOf(0.8f)
        private set
    var running by mutableStateOf(false)
        private set
    var currentBeat by mutableIntStateOf(-1)
        private set
    var currentTick by mutableIntStateOf(-1)
        private set
    var subdivisionVolume by mutableFloatStateOf(1f)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    private val tapTempo = TapTempo()
    private var pollJob: Job? = null

    init {
        pushSettings()
    }

    private fun pushSettings() {
        NativeAudio.metronomeSetBpm(bpm.toDouble())
        NativeAudio.metronomeSetBeatsPerBar(signature.beats)
        NativeAudio.metronomeSetSubdivision(subdivision.value)
        NativeAudio.metronomeSetAccent(accent)
        NativeAudio.metronomeSetVolume(volume)
        NativeAudio.metronomeSetSubdivisionVolume(subdivisionVolume)
    }

    fun changeSubdivisionVolume(value: Float) {
        subdivisionVolume = value
        NativeAudio.metronomeSetSubdivisionVolume(value)
    }

    fun changeBpm(value: Int) {
        bpm = value.coerceIn(30, 300)
        NativeAudio.metronomeSetBpm(bpm.toDouble())
        if (running) MetronomeService.start(getApplication(), bpm)
    }

    fun tap() {
        tapTempo.tap(System.currentTimeMillis())?.let { changeBpm(it) }
    }

    fun changeSignature(value: TimeSignature) {
        signature = value
        NativeAudio.metronomeSetBeatsPerBar(value.beats)
    }

    fun changeSubdivision(value: Subdivision) {
        subdivision = value
        NativeAudio.metronomeSetSubdivision(value.value)
    }

    fun toggleAccent() {
        accent = !accent
        NativeAudio.metronomeSetAccent(accent)
    }

    fun changeVolume(value: Float) {
        volume = value
        NativeAudio.metronomeSetVolume(value)
    }

    fun toggle() = if (running) stop() else start()

    fun start() {
        if (running) return
        pushSettings()
        if (!NativeAudio.metronomeStart()) {
            error = "Não foi possível abrir a saída de áudio."
            return
        }
        error = null
        running = true
        MetronomeService.start(getApplication(), bpm)
        pollJob = viewModelScope.launch {
            while (isActive) {
                if (!NativeAudio.metronomeIsRunning()) {
                    // Parado pela notificação.
                    stop()
                    break
                }
                currentBeat = NativeAudio.metronomeCurrentBeat()
                currentTick = NativeAudio.metronomeCurrentTick()
                delay(15)
            }
        }
    }

    fun stop() {
        if (!running) return
        NativeAudio.metronomeStop()
        MetronomeService.stop(getApplication())
        pollJob?.cancel()
        running = false
        currentBeat = -1
        currentTick = -1
    }

    override fun onCleared() {
        stop()
    }
}
