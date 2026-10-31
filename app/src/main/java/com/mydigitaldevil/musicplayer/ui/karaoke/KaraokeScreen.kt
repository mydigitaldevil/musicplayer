package com.mydigitaldevil.musicplayer.ui.karaoke

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mydigitaldevil.musicplayer.R
import com.mydigitaldevil.musicplayer.ads.NaturalBreak
import com.mydigitaldevil.musicplayer.ads.RewardOutcome
import com.mydigitaldevil.musicplayer.ads.RewardedUnlock
import com.mydigitaldevil.musicplayer.ads.ui.RewardedOfferDialog
import com.mydigitaldevil.musicplayer.audio.fx.MicFxError
import com.mydigitaldevil.musicplayer.audio.fx.MicFxService
import com.mydigitaldevil.musicplayer.ui.LocalAdManager
import com.mydigitaldevil.musicplayer.ui.player.ArtworkImage
import com.mydigitaldevil.musicplayer.ui.player.rememberArtwork

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KaraokeScreen(
    onBack: () -> Unit,
    onPickSong: () -> Unit,
    viewModel: KaraokeViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val activity = LocalActivity.current
    val adManager = LocalAdManager.current
    val np by viewModel.nowPlaying.collectAsStateWithLifecycle()
    val gate by viewModel.gate.collectAsStateWithLifecycle()
    val removal by viewModel.vocalRemoval.collectAsStateWithLifecycle()
    val mic by viewModel.micState.collectAsStateWithLifecycle()
    val rewardedReady by adManager.rewardedReady.collectAsStateWithLifecycle()
    var showOffer by remember { mutableStateOf(false) }

    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) MicFxService.start(context)
    }

    val leave: () -> Unit = {
        if (mic.running) MicFxService.stop(context)
        val act = activity
        // Finishing a karaoke session is a natural break; nothing is shown if they never sang.
        if (act != null && viewModel.sessionHappened) {
            adManager.showInterstitialAtNaturalBreak(act, NaturalBreak.KARAOKE_SESSION_FINISHED, onBack)
        } else {
            onBack()
        }
    }
    BackHandler(onBack = leave)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.karaoke_title)) },
                navigationIcon = { IconButton(onClick = leave) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back)) } },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (!np.hasMedia || np.isVideo) {
                Spacer(Modifier.size(48.dp))
                Text(stringResource(R.string.karaoke_pick_song_hint), textAlign = TextAlign.Center)
                Button(onClick = onPickSong) {
                    Icon(Icons.Filled.LibraryMusic, null)
                    Text(stringResource(R.string.karaoke_pick_song), Modifier.padding(start = 8.dp))
                }
                return@Column
            }

            val artPx = with(LocalDensity.current) { 240.dp.roundToPx() }
            val art by rememberArtwork(np.mediaId, np.artworkData, artPx)
            ArtworkImage(art, Modifier.widthIn(max = 240.dp).fillMaxWidth().aspectRatio(1f).clip(MaterialTheme.shapes.extraLarge))
            Text(np.title, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
            np.artist?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }

            when (gate) {
                KaraokeGate.CHECKING -> CircularProgressIndicator()
                KaraokeGate.LOCKED -> Card {
                    Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Filled.Lock, null, tint = MaterialTheme.colorScheme.primary)
                        Text(stringResource(R.string.karaoke_locked), textAlign = TextAlign.Center)
                        if (rewardedReady) {
                            Button(onClick = { showOffer = true }) { Text(stringResource(R.string.karaoke_unlock)) }
                        } else {
                            Text(stringResource(R.string.reward_unavailable), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                        }
                    }
                }
                KaraokeGate.OPEN -> {
                    FilledIconButton(onClick = viewModel.player::togglePlayPause, modifier = Modifier.size(64.dp)) {
                        Icon(if (np.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow, stringResource(if (np.isPlaying) R.string.player_pause else R.string.player_play))
                    }
                    LabeledSlider(stringResource(R.string.karaoke_vocal_removal, (removal * 100).toInt()), removal, viewModel::setVocalRemoval)
                    Text(stringResource(R.string.karaoke_vocal_removal_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)

                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(stringResource(R.string.karaoke_live_mic), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                                OutlinedButton(onClick = {
                                    if (mic.running) {
                                        MicFxService.stop(context)
                                    } else {
                                        viewModel.mic.clearError()
                                        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                                        if (granted) MicFxService.start(context) else micPermission.launch(Manifest.permission.RECORD_AUDIO)
                                    }
                                }) {
                                    Icon(if (mic.running) Icons.Filled.MicOff else Icons.Filled.Mic, null)
                                    Text(stringResource(if (mic.running) R.string.micfx_stop else R.string.micfx_start), Modifier.padding(start = 6.dp))
                                }
                            }
                            if (mic.error == MicFxError.HeadphonesRequired || mic.error == MicFxError.HeadphonesDisconnected) {
                                Text(stringResource(R.string.micfx_error_headphones), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                            }
                            if (mic.running) {
                                LabeledSlider(stringResource(R.string.karaoke_echo), mic.wet, viewModel::setEcho)
                                LabeledSlider(stringResource(R.string.micfx_mic_volume), mic.inputGain / 2f) { viewModel.setMicVolume(it * 2f) }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showOffer) {
        RewardedOfferDialog(
            adManager = adManager,
            unlock = RewardedUnlock.UNLIMITED_KARAOKE,
            onWatch = {
                showOffer = false
                activity?.let { act ->
                    adManager.showRewarded(act, RewardedUnlock.UNLIMITED_KARAOKE) { if (it == RewardOutcome.Granted) viewModel.onRewardGranted() }
                }
            },
            onDismiss = { showOffer = false },
        )
    }
}

@Composable
private fun LabeledSlider(label: String, value: Float, onChange: (Float) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        Slider(value = value, onValueChange = onChange)
    }
}
