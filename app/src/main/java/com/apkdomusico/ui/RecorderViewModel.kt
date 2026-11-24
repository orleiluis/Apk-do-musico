package com.apkdomusico.ui

import android.app.Application
import android.media.MediaPlayer
import android.os.Environment
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.apkdomusico.audio.NativeAudio
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class RecorderViewModel(app: Application) : AndroidViewModel(app) {
    val folder: File =
        (app.getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: File(app.filesDir, "Music")).also { it.mkdirs() }

    var recordings by mutableStateOf<List<File>>(emptyList())
        private set
    var recording by mutableStateOf(false)
        private set
    var seconds by mutableDoubleStateOf(0.0)
        private set
    var level by mutableFloatStateOf(0f)
        private set
    var playing by mutableStateOf<File?>(null)
        private set
    var message by mutableStateOf<String?>(null)
        private set

    private var pollJob: Job? = null
    private var player: MediaPlayer? = null

    init {
        refresh()
    }

    fun refresh() {
        recordings = folder.listFiles { f -> f.extension.equals("wav", ignoreCase = true) }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()
    }

    fun toggleRecording() = if (recording) stopRecording() else startRecording()

    fun startRecording() {
        if (recording) return
        stopPlayback()
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val file = File(folder, "Gravacao_$stamp.wav")
        if (!NativeAudio.recordStart(file.absolutePath)) {
            message = "Não foi possível abrir o microfone."
            return
        }
        message = null
        recording = true
        seconds = 0.0
        pollJob = viewModelScope.launch {
            while (isActive) {
                seconds = NativeAudio.recordSeconds()
                // Queda suave do medidor, para não piscar.
                level = maxOf(NativeAudio.recordTakePeak(), level * 0.85f)
                delay(33)
            }
        }
    }

    fun stopRecording() {
        if (!recording) return
        NativeAudio.recordStop()
        pollJob?.cancel()
        recording = false
        level = 0f
        refresh()
    }

    fun togglePlayback(file: File) {
        if (playing == file) {
            stopPlayback()
            return
        }
        stopPlayback()
        try {
            player = MediaPlayer().apply {
                setDataSource(file.absolutePath)
                setOnCompletionListener { stopPlayback() }
                prepare()
                start()
            }
            playing = file
        } catch (e: Exception) {
            message = "Não foi possível tocar ${file.name}."
            stopPlayback()
        }
    }

    fun stopPlayback() {
        player?.release()
        player = null
        playing = null
    }

    fun rename(file: File, newName: String) {
        val clean = newName.trim().replace(Regex("[\\\\/:*?\"<>|]"), "_").removeSuffix(".wav")
        if (clean.isEmpty()) return
        val target = File(folder, "$clean.wav")
        if (target.exists()) {
            message = "Já existe uma gravação com esse nome."
            return
        }
        if (playing == file) stopPlayback()
        if (!file.renameTo(target)) message = "Não foi possível renomear."
        refresh()
    }

    fun delete(file: File) {
        if (playing == file) stopPlayback()
        file.delete()
        refresh()
    }

    fun clearMessage() {
        message = null
    }

    override fun onCleared() {
        stopRecording()
        stopPlayback()
    }
}
