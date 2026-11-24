package com.apkdomusico.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

@Composable
fun RecorderScreen(vm: RecorderViewModel, metronome: MetronomeViewModel, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var withClick by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<File?>(null) }
    var deleting by remember { mutableStateOf<File?>(null) }

    fun begin() {
        if (withClick) metronome.start()
        vm.startRecording()
    }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) begin()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Gravador", style = MaterialTheme.typography.titleLarge)

        Text(formatTime(vm.seconds), fontSize = 48.sp, modifier = Modifier.align(Alignment.CenterHorizontally))
        LinearProgressIndicator(
            progress = { vm.level.coerceIn(0f, 1f) },
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp),
        )

        Button(
            onClick = {
                if (vm.recording) {
                    vm.stopRecording()
                    if (withClick) metronome.stop()
                } else if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                    PackageManager.PERMISSION_GRANTED
                ) {
                    begin()
                } else {
                    permission.launch(Manifest.permission.RECORD_AUDIO)
                }
            },
            colors = ButtonDefaults.buttonColors(
                containerColor = if (vm.recording) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
        ) {
            Text(if (vm.recording) "Parar gravação" else "Gravar", fontSize = 20.sp)
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Metrônomo junto (${metronome.bpm} BPM)")
                Text(
                    "Use fone para o clique não entrar na gravação.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Switch(checked = withClick, onCheckedChange = { withClick = it }, enabled = !vm.recording)
        }

        vm.message?.let {
            Text(it, color = MaterialTheme.colorScheme.error)
        }

        Text("Gravações (${vm.recordings.size})", style = MaterialTheme.typography.titleSmall)
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(vm.recordings, key = { it.absolutePath }) { file ->
                RecordingRow(
                    file = file,
                    isPlaying = vm.playing == file,
                    onPlay = { vm.togglePlayback(file) },
                    onRename = { renaming = file },
                    onShare = { share(context, file) },
                    onDelete = { deleting = file },
                )
            }
        }
    }

    renaming?.let { file ->
        var name by remember(file) { mutableStateOf(file.nameWithoutExtension) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("Renomear") },
            text = { OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true) },
            confirmButton = {
                TextButton(onClick = {
                    vm.rename(file, name)
                    renaming = null
                }) { Text("Salvar") }
            },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("Cancelar") } },
        )
    }

    deleting?.let { file ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Apagar gravação?") },
            text = { Text(file.name) },
            confirmButton = {
                TextButton(onClick = {
                    vm.delete(file)
                    deleting = null
                }) { Text("Apagar") }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancelar") } },
        )
    }
}

@Composable
private fun RecordingRow(
    file: File,
    isPlaying: Boolean,
    onPlay: () -> Unit,
    onRename: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(file.nameWithoutExtension, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "${formatTime(wavSeconds(file))} · ${file.length() / 1024} KB",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (isPlaying) {
                TextButton(onClick = onPlay) { Text("Parar") }
            } else {
                IconButton(onClick = onPlay) { Icon(Icons.Filled.PlayArrow, contentDescription = "Ouvir") }
            }
            IconButton(onClick = onRename) { Icon(Icons.Filled.Edit, contentDescription = "Renomear") }
            IconButton(onClick = onShare) { Icon(Icons.Filled.Share, contentDescription = "Compartilhar") }
            IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, contentDescription = "Apagar") }
        }
    }
}

private fun share(context: Context, file: File) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "audio/wav"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, "Compartilhar gravação"))
}

/** Duração de um WAV PCM 16 bits, lendo a taxa e os canais do cabeçalho. */
private fun wavSeconds(file: File): Double = try {
    RandomAccessFile(file, "r").use { raf ->
        val header = ByteArray(44)
        raf.readFully(header)
        val buf = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        val channels = buf.getShort(22).toInt().coerceAtLeast(1)
        val rate = buf.getInt(24).coerceAtLeast(1)
        (file.length() - 44).coerceAtLeast(0) / (2.0 * channels * rate)
    }
} catch (e: Exception) {
    0.0
}

private fun formatTime(seconds: Double): String {
    val total = seconds.toInt()
    val tenths = ((seconds - total) * 10).toInt()
    return "%02d:%02d.%d".format(total / 60, total % 60, tenths)
}
