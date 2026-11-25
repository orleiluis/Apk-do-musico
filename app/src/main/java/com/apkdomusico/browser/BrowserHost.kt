package com.apkdomusico.browser

import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlin.math.roundToInt

/**
 * Desenha o WebView das aulas por cima de todas as telas, sempre no mesmo lugar da árvore,
 * para ele nunca ser desanexado (o que pausa o vídeo):
 * - na aba Aulas, ocupa o espaço reservado pela tela;
 * - nas outras abas, vira um mini player flutuante se um vídeo estava tocando;
 * - senão, fica escondido com 1 px.
 */
@Composable
fun BrowserHost(state: BrowserState, onAulasTab: Boolean, onExpand: () -> Unit) {
    if (!state.started) return

    val fullscreen = state.fullscreenView
    val density = LocalDensity.current
    var miniOffset by remember { mutableStateOf(Offset.Zero) }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val bounds = state.contentBounds
        val maxW = constraints.maxWidth.toFloat()
        val maxH = constraints.maxHeight.toFloat()
        val miniW = with(density) { 232.dp.toPx() }
        val miniH = with(density) { 130.dp.toPx() }
        val barH = with(density) { 32.dp.toPx() }
        val margin = with(density) { 12.dp.toPx() }
        val bottomGap = with(density) { 96.dp.toPx() } // acima da barra de abas

        val showPage = onAulasTab && bounds != null
        val showMini = !onAulasTab && state.mini

        val pageModifier = when {
            showPage -> Modifier
                .offset { IntOffset(bounds!!.left.roundToInt(), bounds.top.roundToInt()) }
                .size(with(density) { bounds!!.width.toDp() }, with(density) { bounds!!.height.toDp() })
            showMini -> {
                val x = (maxW - miniW - margin + miniOffset.x).coerceIn(0f, maxW - miniW)
                val y = (maxH - miniH - bottomGap + miniOffset.y).coerceIn(barH, maxH - miniH)
                Modifier
                    .offset { IntOffset(x.roundToInt(), y.roundToInt()) }
                    .size(with(density) { miniW.toDp() }, with(density) { miniH.toDp() })
                    .shadow(8.dp, RoundedCornerShape(8.dp))
                    .clip(RoundedCornerShape(bottomStart = 8.dp, bottomEnd = 8.dp))
            }
            else -> Modifier.size(1.dp).alpha(0f)
        }

        AndroidView(
            factory = { state.webView.also { (it.parent as? ViewGroup)?.removeView(it) } },
            modifier = pageModifier,
        )

        // Janela aberta pela página (login em popup)
        val popup = state.popup
        if (popup != null && showPage) {
            Column(
                Modifier
                    .offset { IntOffset(bounds!!.left.roundToInt(), bounds.top.roundToInt()) }
                    .size(with(density) { bounds!!.width.toDp() }, with(density) { bounds!!.height.toDp() })
                    .background(MaterialTheme.colorScheme.surface),
            ) {
                Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant), verticalAlignment = Alignment.CenterVertically) {
                    Text("Janela da página", Modifier.weight(1f).padding(horizontal = 12.dp), style = MaterialTheme.typography.labelLarge)
                    TextButton(onClick = state::closePopup) { Text("Fechar ✕") }
                }
                AndroidView(
                    factory = { popup.also { (it.parent as? ViewGroup)?.removeView(it) } },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        // Barra do mini player: arrastar, voltar para a aula, fechar
        if (showMini) {
            val x = (maxW - miniW - margin + miniOffset.x).coerceIn(0f, maxW - miniW)
            val y = (maxH - miniH - bottomGap + miniOffset.y).coerceIn(barH, maxH - miniH)
            Row(
                Modifier
                    .offset { IntOffset(x.roundToInt(), (y - barH).roundToInt()) }
                    .size(with(density) { miniW.toDp() }, with(density) { barH.toDp() })
                    .clip(RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp))
                    .background(Color(0xE6222222))
                    .pointerInput(Unit) {
                        detectDragGestures { change, drag ->
                            change.consume()
                            miniOffset += drag
                        }
                    },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("  ⠿ Aula", Modifier.weight(1f), color = Color.White, style = MaterialTheme.typography.labelMedium)
                TextButton(onClick = onExpand, contentPadding = SmallBar) { Text("Abrir", color = Color.White) }
                TextButton(
                    onClick = {
                        state.pauseMedia()
                        state.mini = false
                    },
                    contentPadding = SmallBar,
                ) { Text("✕", color = Color.White) }
            }
        }

        // Vídeo em tela cheia, por cima de tudo
        if (fullscreen != null) {
            BackHandler { state.exitFullscreen() }
            AndroidView(
                factory = { fullscreen.also { (it.parent as? ViewGroup)?.removeView(it) } },
                modifier = Modifier.fillMaxSize().background(Color.Black),
            )
        }
    }
}

private val SmallBar = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 0.dp)
