package com.apkdomusico.browser

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.apkdomusico.tabs.TabWebView
import com.apkdomusico.tabs.TabsViewModel
import com.apkdomusico.ui.BeatLights
import com.apkdomusico.ui.MetronomeViewModel
import com.apkdomusico.ui.Subdivisions
import com.apkdomusico.ui.TimeSignatures
import androidx.compose.foundation.verticalScroll
import java.io.File

@Composable
fun BrowserScreen(
    state: BrowserState,
    metronome: MetronomeViewModel,
    tabs: TabsViewModel,
    modifier: Modifier = Modifier,
) {
    var study by rememberSaveable { mutableStateOf(false) }
    var studyPanel by rememberSaveable { mutableStateOf(0) } // 0 metrônomo, 1 tablatura
    var studyTab by remember { mutableStateOf<File?>(null) }
    var editing by remember { mutableStateOf(false) }
    var details by remember { mutableStateOf(false) }
    var address by remember { mutableStateOf(state.url) }
    val focus = LocalFocusManager.current

    LaunchedEffect(Unit) { state.ensureLoaded() }
    LaunchedEffect(state.url) { if (!editing) address = state.url }
    BackHandler(enabled = state.canGoBack || state.popup != null) { state.back() }
    DisposableEffect(Unit) { onDispose { state.contentBounds = null } }

    Column(modifier.fillMaxSize()) {
        // Endereço
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = address,
                onValueChange = { address = it },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium,
                placeholder = { Text("Endereço ou pesquisa") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = {
                    state.load(address)
                    focus.clearFocus()
                }),
                modifier = Modifier
                    .weight(1f)
                    .onFocusChanged { editing = it.isFocused },
            )
        }
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(onClick = state::back, enabled = state.canGoBack, contentPadding = Small) { Text("‹") }
            OutlinedButton(onClick = state::forward, enabled = state.canGoForward, contentPadding = Small) { Text("›") }
            OutlinedButton(onClick = state::reload, contentPadding = Small) { Text("⟳") }
            OutlinedButton(onClick = state::toggleBookmark, contentPadding = Small) {
                Text(if (state.isBookmarked(state.url)) "★ Salvo" else "☆ Salvar")
            }
            FilterChip(selected = study, onClick = { study = !study }, label = { Text("Modo estudo") })
            TextButton(onClick = { state.openSecure() }) { Text("Abrir no navegador seguro") }
            TextButton(onClick = { details = true }) { Text(if (state.problems.isEmpty()) "Detalhes" else "Detalhes (${state.problems.size})") }
        }
        if (state.progress in 1..99) {
            LinearProgressIndicator(progress = { state.progress / 100f }, modifier = Modifier.fillMaxWidth().height(3.dp))
        }
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            state.bookmarks.forEach { b ->
                AssistChip(onClick = { state.load(b.url) }, label = { Text(b.title, maxLines = 1) })
            }
        }

        if (state.blankSuspected) {
            Row(
                Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.errorContainer).padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "A página ficou em branco?",
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
                TextButton(onClick = state::reload) { Text("Recarregar") }
                TextButton(onClick = { details = true }) { Text("Detalhes") }
                TextButton(onClick = { state.openSecure() }) { Text("Abrir fora") }
            }
        }

        if (details) PageDetailsDialog(state) { details = false }

        // Espaço da página: o WebView é desenhado por cima, pelo BrowserHost, para não ser recriado ao trocar de aba.
        Box(
            Modifier
                .fillMaxWidth()
                .weight(if (study) 0.55f else 1f)
                .onGloballyPositioned { state.contentBounds = it.boundsInRoot() },
        )

        if (study) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .weight(0.45f)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FilterChip(selected = studyPanel == 0, onClick = { studyPanel = 0 }, label = { Text("Metrônomo") })
                    FilterChip(selected = studyPanel == 1, onClick = { studyPanel = 1 }, label = { Text("Tablatura") })
                    if (studyPanel == 1 && studyTab != null) {
                        TextButton(onClick = { studyTab = null }) { Text("Trocar") }
                    }
                }
                when {
                    studyPanel == 0 -> MiniMetronome(metronome)
                    studyTab != null -> TabWebView(tabs.folder, studyTab!!, Modifier.fillMaxSize())
                    else -> TabPicker(tabs) { studyTab = it }
                }
            }
        }
    }
}

private val Small = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 2.dp)

@Composable
private fun MiniMetronome(vm: MetronomeViewModel) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        BeatLights(
            beats = vm.signature.beats,
            subdivision = vm.subdivision.value,
            currentTick = vm.currentTick,
            accent = vm.accent,
            small = true,
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedButton(onClick = { vm.changeBpm(vm.bpm - 5) }, contentPadding = Small) { Text("−5") }
            OutlinedButton(onClick = { vm.changeBpm(vm.bpm - 1) }, contentPadding = Small) { Text("−1") }
            Text("${vm.bpm}", fontSize = 34.sp, fontWeight = FontWeight.Bold)
            OutlinedButton(onClick = { vm.changeBpm(vm.bpm + 1) }, contentPadding = Small) { Text("+1") }
            OutlinedButton(onClick = { vm.changeBpm(vm.bpm + 5) }, contentPadding = Small) { Text("+5") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = vm::toggle) { Text(if (vm.running) "Parar" else "Tocar") }
            OutlinedButton(onClick = vm::tap) { Text("Tap tempo") }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TimeSignatures.forEach { sig ->
                FilterChip(selected = sig == vm.signature, onClick = { vm.changeSignature(sig) }, label = { Text(sig.label) })
            }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Subdivisions.forEach { sub ->
                FilterChip(selected = sub == vm.subdivision, onClick = { vm.changeSubdivision(sub) }, label = { Text(sub.label) })
            }
        }
    }
}

@Composable
private fun TabPicker(tabs: TabsViewModel, onPick: (File) -> Unit) {
    if (tabs.files.isEmpty()) {
        Text(
            "Nenhuma tablatura na biblioteca. Abra um arquivo Guitar Pro na aba Tabs.",
            Modifier.padding(12.dp),
            style = MaterialTheme.typography.bodyMedium,
        )
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
        items(tabs.files, key = { it.absolutePath }) { f ->
            Text(
                f.nameWithoutExtension,
                Modifier.fillMaxWidth().clickable { onPick(f) }.padding(horizontal = 12.dp, vertical = 12.dp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun PageDetailsDialog(state: BrowserState, onDismiss: () -> Unit) {
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Detalhes da página") },
        text = {
            Column(
                Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    "Se a página ficar em branco, toque em Copiar e mande este texto: ele mostra o que o site tentou carregar e não conseguiu.",
                    style = MaterialTheme.typography.bodySmall,
                )
                androidx.compose.foundation.text.selection.SelectionContainer {
                    Text(state.diagnosticText(), style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { clipboard.setText(androidx.compose.ui.text.AnnotatedString(state.diagnosticText())) }) { Text("Copiar") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Fechar") } },
    )
}
