package com.apkdomusico.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Recording
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.camera.view.video.AudioConfig
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Aba Gravador: escolhe entre gravar só o áudio ou gravar vídeo com a câmera. */
@Composable
fun RecorderTab(vm: RecorderViewModel, metronome: MetronomeViewModel, modifier: Modifier = Modifier) {
    var video by rememberSaveable { mutableStateOf(false) }
    var videoBusy by remember { mutableStateOf(false) }
    Column(modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val locked = vm.recording || videoBusy
            FilterChip(selected = !video, onClick = { video = false }, enabled = !locked, label = { Text("Áudio") })
            FilterChip(selected = video, onClick = { video = true }, enabled = !locked, label = { Text("Vídeo (câmera)") })
        }
        if (video) {
            VideoRecorderPanel(metronome, onBusyChange = { videoBusy = it }, modifier = Modifier.weight(1f))
        } else {
            RecorderScreen(vm, metronome, Modifier.weight(1f))
        }
    }
}

private const val VIDEO_FOLDER = "Apk do Musico"

private data class VideoItem(val uri: Uri, val name: String, val bytes: Long, val durationMs: Long, val file: File?)

private fun hasCameraPermissions(context: Context) = listOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
    .all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }

/** No Android 10 ou mais novo os vídeos vão para a galeria; antes disso ficam na pasta do app. */
private fun usesGallery() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

private fun appVideoDir(context: Context): File =
    (context.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: File(context.filesDir, "videos")).also { it.mkdirs() }

private fun listVideos(context: Context): List<VideoItem> {
    if (!usesGallery()) {
        return appVideoDir(context).listFiles { f -> f.extension == "mp4" }.orEmpty()
            .sortedByDescending { it.lastModified() }
            .map { VideoItem(Uri.fromFile(it), it.nameWithoutExtension, it.length(), 0, it) }
    }
    val out = mutableListOf<VideoItem>()
    val projection = arrayOf(
        MediaStore.Video.Media._ID,
        MediaStore.Video.Media.DISPLAY_NAME,
        MediaStore.Video.Media.SIZE,
        MediaStore.Video.Media.DURATION,
    )
    try {
        context.contentResolver.query(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            projection,
            "${MediaStore.Video.Media.RELATIVE_PATH} LIKE ?",
            arrayOf("${Environment.DIRECTORY_MOVIES}/$VIDEO_FOLDER%"),
            "${MediaStore.Video.Media.DATE_ADDED} DESC",
        )?.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                out += VideoItem(
                    ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id),
                    c.getString(1).substringBeforeLast('.'),
                    c.getLong(2),
                    c.getLong(3),
                    null,
                )
            }
        }
    } catch (e: Exception) {
        // Sem acesso à galeria: a lista fica vazia, a gravação continua funcionando.
    }
    return out
}

private fun shareableUri(context: Context, item: VideoItem): Uri =
    item.file?.let { FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", it) } ?: item.uri

@SuppressLint("MissingPermission") // a permissão do microfone é conferida antes de gravar
@Composable
private fun VideoRecorderPanel(metronome: MetronomeViewModel, onBusyChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var granted by remember { mutableStateOf(hasCameraPermissions(context)) }
    val askPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        granted = hasCameraPermissions(context)
    }

    if (!granted) {
        Column(
            modifier.fillMaxSize().padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Gravar vídeo", style = MaterialTheme.typography.titleLarge)
            Text("Para se filmar tocando, o app precisa da câmera e do microfone.")
            Button(onClick = { askPermission.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)) }) {
                Text("Permitir câmera e microfone")
            }
        }
        return
    }

    val controller = remember {
        LifecycleCameraController(context).apply {
            setEnabledUseCases(CameraController.VIDEO_CAPTURE)
            cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA
        }
    }
    var front by rememberSaveable { mutableStateOf(true) }
    var recording by remember { mutableStateOf<Recording?>(null) }
    var startedAt by remember { mutableStateOf(0L) }
    var seconds by remember { mutableIntStateOf(0) }
    var withClick by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var videos by remember { mutableStateOf(listVideos(context)) }
    var deleting by remember { mutableStateOf<VideoItem?>(null) }

    DisposableEffect(lifecycleOwner) {
        controller.bindToLifecycle(lifecycleOwner)
        onDispose {
            recording?.stop()
            controller.unbind()
            onBusyChange(false)
        }
    }

    // Tablets e celulares sem câmera frontal usam a traseira.
    LaunchedEffect(front) {
        val wanted = if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
        controller.initializationFuture.addListener({
            val ok = try { controller.hasCamera(wanted) } catch (e: Exception) { false }
            if (ok) {
                controller.cameraSelector = wanted
            } else {
                val other = if (front) CameraSelector.DEFAULT_BACK_CAMERA else CameraSelector.DEFAULT_FRONT_CAMERA
                if (try { controller.hasCamera(other) } catch (e: Exception) { false }) controller.cameraSelector = other
                else message = "Nenhuma câmera encontrada neste aparelho."
            }
        }, ContextCompat.getMainExecutor(context))
    }

    LaunchedEffect(recording) {
        while (recording != null) {
            seconds = ((System.currentTimeMillis() - startedAt) / 1000).toInt()
            delay(250)
        }
    }

    fun stop() {
        recording?.stop()
        recording = null
        if (withClick) metronome.stop()
    }

    fun start() {
        message = null
        val name = "Video_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val listener = androidx.core.util.Consumer<VideoRecordEvent> { event ->
            if (event is VideoRecordEvent.Finalize) {
                if (event.hasError() && event.error != VideoRecordEvent.Finalize.ERROR_SOURCE_INACTIVE) {
                    message = "O vídeo não foi salvo (erro ${event.error})."
                } else {
                    message = if (usesGallery()) "Vídeo salvo na galeria, em Filmes/$VIDEO_FOLDER." else "Vídeo salvo."
                }
                recording = null
                onBusyChange(false)
                videos = listVideos(context)
            }
        }
        val executor = ContextCompat.getMainExecutor(context)
        recording = try {
            if (usesGallery()) {
                val values = ContentValues().apply {
                    put(MediaStore.Video.Media.DISPLAY_NAME, "$name.mp4")
                    put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                    put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/$VIDEO_FOLDER")
                }
                val options = MediaStoreOutputOptions.Builder(context.contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
                    .setContentValues(values)
                    .build()
                controller.startRecording(options, AudioConfig.create(true), executor, listener)
            } else {
                val options = FileOutputOptions.Builder(File(appVideoDir(context), "$name.mp4")).build()
                controller.startRecording(options, AudioConfig.create(true), executor, listener)
            }
        } catch (e: Exception) {
            message = "Não foi possível começar a gravar: ${e.message}"
            null
        }
        if (recording != null) {
            startedAt = System.currentTimeMillis()
            seconds = 0
            onBusyChange(true)
            if (withClick) metronome.start()
        }
    }

    Column(
        modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            Modifier.fillMaxWidth().weight(1f).heightIn(min = 160.dp).background(Color.Black),
        ) {
            AndroidView(
                factory = { ctx ->
                    PreviewView(ctx).apply {
                        scaleType = PreviewView.ScaleType.FIT_CENTER
                        this.controller = controller
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
            if (recording != null) {
                Text(
                    "● %02d:%02d".format(seconds / 60, seconds % 60),
                    color = Color.White,
                    fontSize = 18.sp,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(8.dp)
                        .background(Color(0xCCB3261E))
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                )
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(
                onClick = { if (recording != null) stop() else start() },
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (recording != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                ),
                modifier = Modifier.weight(1f).height(52.dp),
            ) { Text(if (recording != null) "Parar vídeo" else "Gravar vídeo", fontSize = 18.sp) }
            OutlinedButton(onClick = { front = !front }, enabled = recording == null, modifier = Modifier.height(52.dp)) {
                Text("Virar câmera")
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Metrônomo junto (${metronome.bpm} BPM)")
                Text("Sem fone, o clique também entra no vídeo.", style = MaterialTheme.typography.bodySmall)
            }
            Switch(checked = withClick, onCheckedChange = { withClick = it }, enabled = recording == null)
        }

        message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }

        Text("Vídeos (${videos.size})", style = MaterialTheme.typography.titleSmall)
        LazyColumn(Modifier.heightIn(max = 200.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(videos, key = { it.uri.toString() }) { item ->
                Card(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(start = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(item.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            val dur = if (item.durationMs > 0) "%d:%02d · ".format(item.durationMs / 60000, item.durationMs / 1000 % 60) else ""
                            Text("$dur${item.bytes / (1024 * 1024)} MB", style = MaterialTheme.typography.bodySmall)
                        }
                        TextButton(onClick = {
                            val intent = Intent(Intent.ACTION_VIEW).setDataAndType(shareableUri(context, item), "video/mp4")
                                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            try { context.startActivity(intent) } catch (e: Exception) { message = "Nenhum app para assistir vídeos." }
                        }) { Text("Assistir") }
                        TextButton(onClick = {
                            val intent = Intent(Intent.ACTION_SEND).setType("video/mp4")
                                .putExtra(Intent.EXTRA_STREAM, shareableUri(context, item))
                                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            context.startActivity(Intent.createChooser(intent, "Compartilhar vídeo"))
                        }) { Text("Enviar") }
                        TextButton(onClick = { deleting = item }) { Text("Apagar") }
                    }
                }
            }
        }
    }

    deleting?.let { item ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Apagar vídeo?") },
            text = { Text(item.name) },
            confirmButton = {
                TextButton(onClick = {
                    try {
                        if (item.file != null) item.file.delete() else context.contentResolver.delete(item.uri, null, null)
                    } catch (e: Exception) {
                        message = "Não foi possível apagar: ${e.message}"
                    }
                    deleting = null
                    videos = listVideos(context)
                }) { Text("Apagar") }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancelar") } },
        )
    }
}
