package com.apkdomusico.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import android.app.Activity
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import com.apkdomusico.browser.BrowserHost
import com.apkdomusico.browser.BrowserScreen
import com.apkdomusico.browser.BrowserState
import com.apkdomusico.multitrack.MultitrackScreen
import com.apkdomusico.multitrack.MultitrackViewModel
import com.apkdomusico.tabs.TabsScreen
import com.apkdomusico.tabs.TabsViewModel

private val Accent = Color(0xFFE0A030)

private const val AULAS = 2

private val Tabs = listOf(
    "≡" to "Estúdio",
    "♫" to "Tabs",
    "▶" to "Aulas",
    "♩" to "Metrônomo",
    "●" to "Gravador",
)

@Composable
fun AppRoot() {
    val colors = if (isSystemInDarkTheme()) {
        darkColorScheme(primary = Accent, secondary = Accent)
    } else {
        lightColorScheme(primary = Color(0xFFB07010), secondary = Color(0xFFB07010))
    }
    MaterialTheme(colorScheme = colors) {
        val metronome: MetronomeViewModel = viewModel()
        val recorder: RecorderViewModel = viewModel()
        val studio: MultitrackViewModel = viewModel()
        val tabs: TabsViewModel = viewModel()
        val activity = LocalContext.current as Activity
        val browser = remember(activity) { BrowserState(activity) }
        val lifecycle = LocalLifecycleOwner.current.lifecycle
        DisposableEffect(lifecycle, browser) {
            val observer = LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_PAUSE -> browser.onPause()
                    Lifecycle.Event.ON_RESUME -> browser.onResume()
                    else -> Unit
                }
            }
            lifecycle.addObserver(observer)
            onDispose { lifecycle.removeObserver(observer) }
        }
        var tab by rememberSaveable { mutableIntStateOf(0) }

        LaunchedEffect(tab) {
            if (tab == AULAS) browser.mini = false else browser.checkPlaying { browser.mini = it }
        }
        Box(Modifier.fillMaxSize()) {
        Scaffold(
            bottomBar = {
                NavigationBar {
                    Tabs.forEachIndexed { index, (icon, label) ->
                        NavigationBarItem(
                            selected = tab == index,
                            onClick = { tab = index },
                            icon = { Text(icon) },
                            label = { Text(label, maxLines = 1) },
                        )
                    }
                }
            },
        ) { padding ->
            val modifier = Modifier.padding(padding)
            when (tab) {
                0 -> MultitrackScreen(studio, modifier)
                1 -> TabsScreen(tabs, modifier)
                2 -> BrowserScreen(browser, metronome, tabs, modifier)
                3 -> MetronomeScreen(metronome, modifier)
                else -> RecorderScreen(recorder, metronome, modifier)
            }
        }
        BrowserHost(browser, onAulasTab = tab == AULAS, onExpand = { tab = AULAS })
        }
    }
}
