package com.apkdomusico.tabs

import android.annotation.SuppressLint
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebViewAssetLoader
import java.io.File

@Composable
fun TabsScreen(vm: TabsViewModel, modifier: Modifier = Modifier) {
    val opened = vm.opened
    if (opened != null) {
        TabViewer(vm, opened, modifier)
    } else {
        TabLibrary(vm, modifier)
    }
}

@Composable
private fun TabLibrary(vm: TabsViewModel, modifier: Modifier) {
    var deleting by remember { mutableStateOf<File?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) vm.import(uri)
    }
    Column(modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Tablaturas", style = MaterialTheme.typography.titleLarge)
        Button(onClick = { picker.launch(arrayOf("*/*")) }, modifier = Modifier.fillMaxWidth().height(52.dp)) {
            Text("Abrir arquivo Guitar Pro", fontSize = 18.sp)
        }
        Text(
            "Abre .gp, .gp3, .gp4, .gp5, .gpx, MusicXML e Capella. O arquivo fica guardado aqui na biblioteca.",
            style = MaterialTheme.typography.bodySmall,
        )
        vm.message?.let {
            Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.clickable { vm.message = null })
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(vm.files, key = { it.absolutePath }) { f ->
                Card(Modifier.fillMaxWidth().clickable { vm.opened = f }) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(f.nameWithoutExtension, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(f.extension.uppercase(), style = MaterialTheme.typography.bodySmall)
                        }
                        TextButton(onClick = { deleting = f }) { Text("Remover") }
                    }
                }
            }
        }
    }
    deleting?.let { f ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Remover da biblioteca?") },
            text = { Text(f.name) },
            confirmButton = {
                TextButton(onClick = {
                    vm.delete(f)
                    deleting = null
                }) { Text("Remover") }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancelar") } },
        )
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun TabViewer(vm: TabsViewModel, file: File, modifier: Modifier) {
    BackHandler { vm.opened = null }
    var webView by remember { mutableStateOf<WebView?>(null) }
    DisposableEffect(file) {
        onDispose {
            webView?.apply {
                loadUrl("about:blank")
                destroy()
            }
        }
    }
    Column(modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { vm.opened = null }) { Text("‹ Biblioteca") }
            Text(file.nameWithoutExtension, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
        }
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                val loader = WebViewAssetLoader.Builder()
                    .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(ctx))
                    .addPathHandler("/tabs/", WebViewAssetLoader.InternalStoragePathHandler(ctx, vm.folder))
                    .build()
                WebView(ctx).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.mediaPlaybackRequiresUserGesture = false
                    settings.allowFileAccess = false
                    settings.cacheMode = WebSettings.LOAD_DEFAULT
                    webViewClient = object : WebViewClient() {
                        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                            loader.shouldInterceptRequest(request.url)
                    }
                    val url = "https://appassets.androidplatform.net/assets/tabs/viewer.html?file=" +
                        Uri.encode("/tabs/" + Uri.encode(file.name))
                    loadUrl(url)
                    webView = this
                }
            },
        )
    }
}
