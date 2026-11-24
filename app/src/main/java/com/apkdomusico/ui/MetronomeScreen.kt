package com.apkdomusico.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MetronomeScreen(vm: MetronomeViewModel, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Metrônomo", style = MaterialTheme.typography.titleLarge)

        BeatLights(beats = vm.signature.beats, current = vm.currentBeat, accent = vm.accent)

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            OutlinedButton(onClick = { vm.changeBpm(vm.bpm - 1) }) { Text("−1", fontSize = 18.sp) }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("${vm.bpm}", fontSize = 64.sp, fontWeight = FontWeight.Bold)
                Text("BPM", style = MaterialTheme.typography.labelLarge)
            }
            OutlinedButton(onClick = { vm.changeBpm(vm.bpm + 1) }) { Text("+1", fontSize = 18.sp) }
        }

        Slider(
            value = vm.bpm.toFloat(),
            onValueChange = { vm.changeBpm(it.roundToInt()) },
            valueRange = 30f..300f,
            modifier = Modifier.fillMaxWidth(),
        )

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = vm::toggle, modifier = Modifier.height(56.dp)) {
                Text(if (vm.running) "Parar" else "Tocar", fontSize = 20.sp)
            }
            OutlinedButton(onClick = vm::tap, modifier = Modifier.height(56.dp)) {
                Text("Tap tempo", fontSize = 16.sp)
            }
        }

        vm.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

        Text("Compasso", style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TimeSignatures.forEach { sig ->
                FilterChip(
                    selected = sig == vm.signature,
                    onClick = { vm.changeSignature(sig) },
                    label = { Text(sig.label) },
                )
            }
        }

        Text("Subdivisão", style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Subdivisions.forEach { sub ->
                FilterChip(
                    selected = sub == vm.subdivision,
                    onClick = { vm.changeSubdivision(sub) },
                    label = { Text(sub.label) },
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("Acentuar o tempo 1")
            Switch(checked = vm.accent, onCheckedChange = { vm.toggleAccent() })
        }

        Column(modifier = Modifier.fillMaxWidth()) {
            Text("Volume: ${(vm.volume * 100).roundToInt()}%")
            Slider(value = vm.volume, onValueChange = vm::changeVolume, valueRange = 0f..1f)
        }
        Spacer(Modifier.height(8.dp))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BeatLights(beats: Int, current: Int, accent: Boolean) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        for (i in 0 until beats) {
            val on = i == current
            val big = i == 0 && accent
            val color = when {
                on -> MaterialTheme.colorScheme.primary
                else -> MaterialTheme.colorScheme.surfaceVariant
            }
            Box(
                modifier = Modifier
                    .size(if (big) 34.dp else 26.dp)
                    .clip(CircleShape)
                    .background(color),
            )
        }
    }
}
