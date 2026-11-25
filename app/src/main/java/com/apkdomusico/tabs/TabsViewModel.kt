package com.apkdomusico.tabs

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Biblioteca de tablaturas guardada na pasta do app. */
class TabsViewModel(app: Application) : AndroidViewModel(app) {
    val folder: File = File(app.filesDir, "tabs").also { it.mkdirs() }

    var files by mutableStateOf<List<File>>(emptyList())
        private set
    var opened by mutableStateOf<File?>(null)
    var message by mutableStateOf<String?>(null)

    init {
        refresh()
    }

    fun refresh() {
        files = folder.listFiles()?.filter { it.isFile && isSupported(it.name) }
            ?.sortedByDescending { it.lastModified() } ?: emptyList()
    }

    fun import(uri: Uri) {
        val ctx = getApplication<Application>()
        val name = ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: "tablatura.gp"
        if (!isSupported(name)) {
            message = "Formato não suportado: $name. Use Guitar Pro (.gp, .gp3, .gp4, .gp5, .gpx), MusicXML ou Capella."
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            var target = File(folder, name.replace(Regex("[\\\\/:*?\"<>|]"), "_"))
            var n = 2
            while (target.exists()) target = File(folder, "${target.nameWithoutExtension.substringBeforeLast(" (")} ($n).${target.extension}").also { n++ }
            val ok = runCatching {
                ctx.contentResolver.openInputStream(uri)!!.use { input -> target.outputStream().use { input.copyTo(it) } }
            }.isSuccess
            withContext(Dispatchers.Main) {
                if (!ok) message = "Não foi possível copiar $name."
                refresh()
                if (ok) opened = target
            }
        }
    }

    fun delete(file: File) {
        file.delete()
        if (opened == file) opened = null
        refresh()
    }

    companion object {
        val Extensions = setOf("gp", "gp3", "gp4", "gp5", "gpx", "gp7", "xml", "musicxml", "mxl", "capx")
        fun isSupported(name: String) = name.substringAfterLast('.', "").lowercase() in Extensions
    }
}
