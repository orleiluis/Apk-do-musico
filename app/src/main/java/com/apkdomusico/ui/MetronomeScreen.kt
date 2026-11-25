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
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.content.ContextCompat
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
    val context = LocalContext.current
    var askedNotification by rememberSaveable { mutableStateOf(false) }
    // A notificação permite parar o metrônomo com a tela apagada; tocando com ou sem permissão.
    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { vm.toggle() }
    val view = LocalView.current
    DisposableEffect(vm.running) {
        view.keepScreenOn = vm.running
        onDispose { view.keepScreenOn = false }
    }
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Metrônomo", style = MaterialTheme.typography.titleLarge)

        BeatLights(
            beats = vm.signature.beats,
            subdivision = vm.subdivision.value,
            currentTick = vm.currentTick,
            accent = vm.accent,
        )

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
            Button(
                onClick = {
                    if (!vm.running && Build.VERSION.SDK_INT >= 33 &&
                        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
                        !askedNotification
                    ) {
                        askedNotification = true
                        notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        vm.toggle()
                    }
                },
                modifier = Modifier.height(56.dp),
            ) {
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

        if (vm.subdivision.value > 1) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text("Volume das subdivisões: ${(vm.subdivisionVolume * 100).roundToInt()}%")
                Slider(value = vm.subdivisionVolume, onValueChange = vm::changeSubdivisionVolume, valueRange = 0f..1f)
            }
        }

        Text(
            "O metrônomo continua tocando com a tela apagada ou em outro app. Para parar, use o botão aqui ou a notificação.",
            style = MaterialTheme.typography.bodySmall,
        )

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
fun BeatLights(beats: Int, subdivision: Int, currentTick: Int, accent: Boolean, small: Boolean = false) {
    val beat = if (currentTick >= 0) currentTick / subdivision else -1
    FlowRow(horizontalArrangement = Arrangement.spacedBy(if (small) 8.dp else 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (i in 0 until beats) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                val big = i == 0 && accent
                val size = when {
                    small && big -> 22.dp
                    small -> 16.dp
                    big -> 34.dp
                    else -> 26.dp
                }
                Box(
                    modifier = Modifier
                        .size(size)
                        .clip(CircleShape)
                        .background(if (i == beat) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant),
                )
                if (subdivision > 1) {
                    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        for (sub in 0 until subdivision) {
                            val on = currentTick == i * subdivision + sub
                            Box(
                                Modifier
                                    .size(if (small) 5.dp else 7.dp)
                                    .clip(CircleShape)
                                    .background(
                                        if (on) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.outlineVariant,
                                    ),
                            )
                        }
                    }
                }
            }
        }
    }
}
