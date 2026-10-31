package com.mydigitaldevil.musicplayer.ui.micfx

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mydigitaldevil.musicplayer.R
import com.mydigitaldevil.musicplayer.ads.AdManager
import com.mydigitaldevil.musicplayer.ads.NaturalBreak
import com.mydigitaldevil.musicplayer.ads.RewardOutcome
import com.mydigitaldevil.musicplayer.ads.RewardedUnlock
import com.mydigitaldevil.musicplayer.ads.ui.RewardedOfferDialog
import com.mydigitaldevil.musicplayer.audio.fx.AmbiencePreset
import com.mydigitaldevil.musicplayer.audio.fx.HeadphoneRoute
import com.mydigitaldevil.musicplayer.audio.fx.MicFxError
import com.mydigitaldevil.musicplayer.audio.fx.MicFxService
import com.mydigitaldevil.musicplayer.audio.fx.VoicePreset
import com.mydigitaldevil.musicplayer.library.formatDuration
import com.mydigitaldevil.musicplayer.ui.LocalAdManager
import kotlinx.coroutines.launch
import java.io.File

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun MicFxScreen(onOpenSoundboard: () -> Unit, viewModel: MicFxViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val activity = LocalActivity.current
    val adManager = LocalAdManager.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    val recordings by viewModel.recordings.collectAsStateWithLifecycle()
    val exporting by viewModel.exporting.collectAsStateWithLifecycle()
    val exclusiveUntil by viewModel.exclusiveUntil.collectAsStateWithLifecycle()
    val exclusiveUnlocked = exclusiveUntil > System.currentTimeMillis()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var lockedVoice by remember { mutableStateOf<VoicePreset?>(null) }
    var previewing by remember { mutableStateOf<File?>(null) }

    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) MicFxService.start(context)
    }

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is MicFxEvent.Exported -> {
                    // Export finished = natural stopping point; the only place this screen may show an interstitial.
                    val act = activity
                    val announce: () -> Unit = {
                        scope.launch {
                            val r = snackbar.showSnackbar(context.getString(R.string.micfx_exported), context.getString(R.string.action_share))
                            if (r == SnackbarResult.ActionPerformed) shareFile(context, adManager, event.file)
                        }
                    }
                    if (act != null) adManager.showInterstitialAtNaturalBreak(act, NaturalBreak.RECORDING_EXPORTED, announce) else announce()
                }
                MicFxEvent.ExportFailed -> snackbar.showSnackbar(context.getString(R.string.micfx_export_failed))
                MicFxEvent.SavedToMusic -> snackbar.showSnackbar(context.getString(R.string.micfx_saved_to_music))
                MicFxEvent.AddedToSoundboard -> snackbar.showSnackbar(context.getString(R.string.micfx_added_to_soundboard))
                MicFxEvent.SoundboardTooLong -> snackbar.showSnackbar(context.getString(R.string.soundboard_msg_too_long))
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.micfx_title)) },
                actions = {
                    IconButton(onClick = onOpenSoundboard) { Icon(Icons.Filled.GridView, stringResource(R.string.soundboard_open)) }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(
            Modifier.padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                StatusCard(
                    running = state.running,
                    route = state.route,
                    latencyMs = state.outputLatencyMs,
                    error = state.error,
                    onToggle = {
                        if (state.running) {
                            MicFxService.stop(context)
                        } else {
                            viewModel.engine.clearError()
                            if (hasMicPermission(context)) MicFxService.start(context) else micPermission.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    },
                )
            }

            item {
                Section(stringResource(R.string.micfx_voice)) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        VoicePreset.entries.forEach { v ->
                            val locked = v in EXCLUSIVE_VOICES && !exclusiveUnlocked
                            FilterChip(
                                selected = state.voice == v,
                                onClick = { if (locked) lockedVoice = v else viewModel.engine.setVoice(v) },
                                label = { Text(stringResource(v.labelRes())) },
                                trailingIcon = if (locked) { { Icon(Icons.Filled.Lock, stringResource(R.string.locked), Modifier.size(16.dp)) } } else null,
                            )
                        }
                    }
                }
            }

            item {
                Section(stringResource(R.string.micfx_room)) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AmbiencePreset.entries.forEach { a ->
                            FilterChip(selected = state.ambience == a, onClick = { viewModel.engine.setAmbience(a) }, label = { Text(stringResource(a.labelRes())) })
                        }
                    }
                    Text(stringResource(R.string.micfx_effect_amount), style = MaterialTheme.typography.labelLarge)
                    Slider(value = state.wet, onValueChange = viewModel.engine::setWet)
                    Text(stringResource(R.string.micfx_mic_volume), style = MaterialTheme.typography.labelLarge)
                    Slider(value = state.inputGain, onValueChange = viewModel.engine::setInputGain, valueRange = 0f..2f)
                }
            }

            item {
                Section(stringResource(R.string.micfx_record)) {
                    val beat = remember(state.recording, state.running) { viewModel.beatTitle() }
                    Text(
                        if (beat != null) stringResource(R.string.micfx_beat_sync_on, beat) else stringResource(R.string.micfx_beat_sync_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(
                        onClick = { if (state.recording) viewModel.stopRecording() else viewModel.startRecording() },
                        enabled = state.running,
                        colors = if (state.recording) ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error) else ButtonDefaults.buttonColors(),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(if (state.recording) Icons.Filled.Stop else Icons.Filled.FiberManualRecord, null)
                        Text(stringResource(if (state.recording) R.string.micfx_stop_recording else R.string.micfx_start_recording), Modifier.padding(start = 8.dp))
                    }
                }
            }

            if (recordings.isNotEmpty()) {
                item { Text(stringResource(R.string.micfx_recordings), style = MaterialTheme.typography.titleMedium) }
            }
            items(recordings, key = { it.file.path }) { rec ->
                RecordingRow(
                    rec = rec,
                    playing = previewing == rec.file,
                    exporting = exporting == rec.file,
                    onPlay = {
                        if (previewing == rec.file) { viewModel.stopPreview(); previewing = null } else { viewModel.preview(rec); previewing = rec.file }
                    },
                    onExport = { viewModel.export(rec) },
                    onShare = { shareFile(context, adManager, rec.file) },
                    onSaveToMusic = { viewModel.saveToMusic(rec) },
                    onAddToSoundboard = { viewModel.addToSoundboard(rec) },
                    onDelete = { viewModel.delete(rec) },
                )
            }
        }
    }

    lockedVoice?.let { voice ->
        RewardedOfferDialog(
            adManager = adManager,
            unlock = RewardedUnlock.EXCLUSIVE_MIC_FILTERS,
            onWatch = {
                lockedVoice = null
                activity?.let { act ->
                    adManager.showRewarded(act, RewardedUnlock.EXCLUSIVE_MIC_FILTERS) { if (it == RewardOutcome.Granted) viewModel.engine.setVoice(voice) }
                }
            },
            onDismiss = { lockedVoice = null },
        )
    }
}

@Composable
private fun StatusCard(running: Boolean, route: HeadphoneRoute?, latencyMs: Double?, error: MicFxError?, onToggle: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = if (running) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh)) {
        Column(Modifier.padding(16.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Headphones, null)
                Text(
                    when {
                        running && route == HeadphoneRoute.BLUETOOTH -> stringResource(R.string.micfx_status_bluetooth)
                        running -> stringResource(R.string.micfx_status_live, latencyMs?.toInt() ?: 0)
                        else -> stringResource(R.string.micfx_status_idle)
                    },
                    Modifier.padding(start = 12.dp).weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            error?.let {
                Text(stringResource(it.messageRes()), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }
            Button(onClick = onToggle, modifier = Modifier.fillMaxWidth()) {
                Icon(if (running) Icons.Filled.MicOff else Icons.Filled.Mic, null)
                Text(stringResource(if (running) R.string.micfx_stop else R.string.micfx_start), Modifier.padding(start = 8.dp))
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        content()
    }
}

@Composable
private fun RecordingRow(
    rec: RecordingInfo,
    playing: Boolean,
    exporting: Boolean,
    onPlay: () -> Unit,
    onExport: () -> Unit,
    onShare: () -> Unit,
    onSaveToMusic: () -> Unit,
    onAddToSoundboard: () -> Unit,
    onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    ListItem(
        leadingContent = {
            IconButton(onClick = onPlay) {
                Icon(if (playing) Icons.Filled.Stop else Icons.Filled.PlayArrow, stringResource(if (playing) R.string.player_pause else R.string.player_play))
            }
        },
        headlineContent = { Text(rec.file.nameWithoutExtension, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            val tags = buildList {
                add(formatDuration(rec.durationMs))
                if (rec.isMix) add(stringResource(R.string.micfx_tag_mix))
                if (rec.hasBeat) add(stringResource(R.string.micfx_tag_beat))
            }
            Text(tags.joinToString(" · "))
        },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (!rec.isMix) {
                    if (exporting) {
                        CircularProgressIndicator(Modifier.size(24.dp).padding(2.dp), strokeWidth = 2.dp)
                    } else {
                        IconButton(onClick = onExport) { Icon(Icons.Filled.Upload, stringResource(R.string.micfx_export)) }
                    }
                }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, stringResource(R.string.library_more_options)) }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.action_share)) }, onClick = { menu = false; onShare() })
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            DropdownMenuItem(text = { Text(stringResource(R.string.micfx_save_to_music)) }, onClick = { menu = false; onSaveToMusic() })
                        }
                        DropdownMenuItem(text = { Text(stringResource(R.string.soundboard_add_as_pad)) }, onClick = { menu = false; onAddToSoundboard() })
                        DropdownMenuItem(text = { Text(stringResource(R.string.action_delete)) }, onClick = { menu = false; onDelete() })
                    }
                }
            }
        },
    )
}

private fun hasMicPermission(context: Context) =
    ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

private fun shareFile(context: Context, adManager: AdManager, file: File) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val send = Intent(Intent.ACTION_SEND)
        .setType(if (file.extension == "m4a") "audio/mp4" else "audio/wav")
        .putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    adManager.suppressNextAppOpen() // coming back from the share sheet must not trigger an App Open ad
    context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

private fun VoicePreset.labelRes(): Int = when (this) {
    VoicePreset.NONE -> R.string.voice_none
    VoicePreset.BABY -> R.string.voice_baby
    VoicePreset.CHIPMUNK -> R.string.voice_chipmunk
    VoicePreset.ROBOT -> R.string.voice_robot
    VoicePreset.MEGAPHONE -> R.string.voice_megaphone
    VoicePreset.DEEP_MONSTER -> R.string.voice_deep_monster
    VoicePreset.WHISPER -> R.string.voice_whisper
    VoicePreset.ANNOUNCER -> R.string.voice_announcer
}

private fun AmbiencePreset.labelRes(): Int = when (this) {
    AmbiencePreset.NONE -> R.string.room_none
    AmbiencePreset.STADIUM -> R.string.room_stadium
    AmbiencePreset.CLUB -> R.string.room_club
    AmbiencePreset.BATHROOM -> R.string.room_bathroom
    AmbiencePreset.SPACE_DELAY -> R.string.room_space_delay
}

private fun MicFxError.messageRes(): Int = when (this) {
    MicFxError.PermissionMissing -> R.string.micfx_error_permission
    MicFxError.HeadphonesRequired -> R.string.micfx_error_headphones
    MicFxError.HeadphonesDisconnected -> R.string.micfx_error_unplugged
    MicFxError.StreamFailed -> R.string.micfx_error_stream
}
