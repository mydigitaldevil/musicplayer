package com.mydigitaldevil.musicplayer.ui.ambient

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Air
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Coffee
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Water
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mydigitaldevil.musicplayer.R
import com.mydigitaldevil.musicplayer.ambient.AmbientEngine
import com.mydigitaldevil.musicplayer.ambient.AmbientService
import com.mydigitaldevil.musicplayer.ambient.AmbientSound
import com.mydigitaldevil.musicplayer.playback.SleepTimer
import com.mydigitaldevil.musicplayer.ui.player.SleepTimerSheet
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class AmbientViewModel @Inject constructor(
    val engine: AmbientEngine,
    val sleepTimer: SleepTimer,
) : ViewModel()

/**
 * Ambient soundboard that runs alongside the music player. No ads here: it's full of sliders,
 * and it's typically used at bedtime with the screen about to go off.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AmbientScreen(viewModel: AmbientViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val volumes by viewModel.engine.volumes.collectAsStateWithLifecycle()
    val playing by viewModel.engine.playing.collectAsStateWithLifecycle()
    val timer by viewModel.sleepTimer.state.collectAsStateWithLifecycle()
    var showTimer by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.ambient_title)) },
                actions = {
                    IconButton(onClick = { showTimer = true }) {
                        Icon(
                            Icons.Filled.Bedtime,
                            stringResource(R.string.sleep_timer_title),
                            tint = if (timer.active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { if (playing) AmbientService.stop(context) else AmbientService.start(context) },
                icon = { Icon(if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, null) },
                text = { Text(stringResource(if (playing) R.string.ambient_stop else R.string.ambient_play)) },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(stringResource(R.string.ambient_intro), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            items(AmbientSound.entries) { sound ->
                Card {
                    Column(Modifier.fillMaxWidth().padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(sound.icon(), null, tint = MaterialTheme.colorScheme.primary)
                            Text(stringResource(sound.labelRes()), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 12.dp).weight(1f))
                            Text("${((volumes[sound] ?: 0f) * 100).toInt()}%", style = MaterialTheme.typography.labelLarge)
                        }
                        if (sound == AmbientSound.BINAURAL) {
                            Text(stringResource(R.string.ambient_binaural_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Slider(value = volumes[sound] ?: 0f, onValueChange = { viewModel.engine.setVolume(sound, it) })
                    }
                }
            }
        }
    }

    if (showTimer) {
        SleepTimerSheet(timer, viewModel.sleepTimer::start, viewModel.sleepTimer::cancel) { showTimer = false }
    }
}

private fun AmbientSound.labelRes(): Int = when (this) {
    AmbientSound.RAIN -> R.string.ambient_rain
    AmbientSound.BONFIRE -> R.string.ambient_bonfire
    AmbientSound.CAFE -> R.string.ambient_cafe
    AmbientSound.RIVER -> R.string.ambient_river
    AmbientSound.WHITE_NOISE -> R.string.ambient_white_noise
    AmbientSound.BINAURAL -> R.string.ambient_binaural
}

private fun AmbientSound.icon(): ImageVector = when (this) {
    AmbientSound.RAIN -> Icons.Filled.WaterDrop
    AmbientSound.BONFIRE -> Icons.Filled.LocalFireDepartment
    AmbientSound.CAFE -> Icons.Filled.Coffee
    AmbientSound.RIVER -> Icons.Filled.Water
    AmbientSound.WHITE_NOISE -> Icons.Filled.Air
    AmbientSound.BINAURAL -> Icons.Filled.Headphones
}
