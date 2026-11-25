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
import com.apkdomusico.multitrack.MultitrackScreen
import com.apkdomusico.multitrack.MultitrackViewModel

private val Accent = Color(0xFFE0A030)

private val Tabs = listOf(
    "≡" to "Estúdio",
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
        var tab by rememberSaveable { mutableIntStateOf(0) }

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
                1 -> MetronomeScreen(metronome, modifier)
                else -> RecorderScreen(recorder, metronome, modifier)
            }
        }
    }
}
