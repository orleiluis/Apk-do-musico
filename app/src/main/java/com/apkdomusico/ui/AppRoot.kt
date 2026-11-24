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
import androidx.lifecycle.viewmodel.compose.viewModel

private val Accent = Color(0xFFE0A030)

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
        var tab by rememberSaveable { mutableIntStateOf(0) }

        Scaffold(
            bottomBar = {
                NavigationBar {
                    NavigationBarItem(
                        selected = tab == 0,
                        onClick = { tab = 0 },
                        icon = { Text("♩") },
                        label = { Text("Metrônomo") },
                    )
                    NavigationBarItem(
                        selected = tab == 1,
                        onClick = { tab = 1 },
                        icon = { Text("●") },
                        label = { Text("Gravador") },
                    )
                }
            },
        ) { padding ->
            val modifier = Modifier.padding(padding)
            when (tab) {
                0 -> MetronomeScreen(metronome, modifier)
                else -> RecorderScreen(recorder, metronome, modifier)
            }
        }
    }
}
