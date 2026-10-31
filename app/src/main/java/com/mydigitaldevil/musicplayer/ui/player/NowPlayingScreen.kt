package com.mydigitaldevil.musicplayer.ui.player

import android.os.Build
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Wallpaper
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import com.mydigitaldevil.musicplayer.R
import com.mydigitaldevil.musicplayer.ads.RewardOutcome
import com.mydigitaldevil.musicplayer.ads.RewardedUnlock
import com.mydigitaldevil.musicplayer.ads.ui.RewardedOfferDialog
import com.mydigitaldevil.musicplayer.core.VisualizerStyle
import com.mydigitaldevil.musicplayer.library.formatDuration
import com.mydigitaldevil.musicplayer.ui.LocalAdManager

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NowPlayingScreen(
    onBack: () -> Unit,
    onOpenKaraoke: () -> Unit,
    viewModel: NowPlayingViewModel = hiltViewModel(),
) {
    val adManager = LocalAdManager.current
    val activity = LocalActivity.current
    val np by viewModel.player.nowPlaying.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val background by viewModel.background.collectAsStateWithLifecycle()
    val proUntil by viewModel.proVisualizersUntil.collectAsStateWithLifecycle()
    val timer by viewModel.sleepTimer.state.collectAsStateWithLifecycle()
    val position by remember { viewModel.player.positionTicks() }.collectAsStateWithLifecycle(0L)

    val artPx = with(LocalDensity.current) { 360.dp.roundToPx() }
    val artwork by rememberArtwork(np.mediaId, np.artworkData, artPx)
    val proUnlocked = proUntil > System.currentTimeMillis()
    // A lapsed unlock quietly falls back to the free style; the saved choice returns if re-unlocked.
    val style = if (settings.visualizerStyle.pro && !proUnlocked) VisualizerStyle.BARS else settings.visualizerStyle

    var showTimer by remember { mutableStateOf(false) }
    var showBackground by remember { mutableStateOf(false) }
    var styleMenu by remember { mutableStateOf(false) }
    var pendingProStyle by remember { mutableStateOf<VisualizerStyle?>(null) }
    var dragPosition by remember { mutableStateOf<Float?>(null) }

    val pickPhoto = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let(viewModel::setBackground)
    }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        // Background: custom photo, else the album art, blurred (blur needs Android 12+; older
        // versions get the image under a stronger scrim instead).
        val bgImage = background ?: artwork
        if (bgImage != null) {
            val blurDp = if (background != null) (settings.backgroundBlur * 40).dp else 60.dp
            ArtworkImage(
                bgImage,
                Modifier.fillMaxSize().then(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Modifier.blur(blurDp) else Modifier),
            )
        }
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    listOf(
                        MaterialTheme.colorScheme.surface.copy(alpha = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) 0.45f else 0.75f),
                        MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                    ),
                ),
            ),
        )

        Column(
            Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back)) }
                Text(stringResource(R.string.now_playing_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
                IconButton(onClick = { showBackground = true }) { Icon(Icons.Filled.Wallpaper, stringResource(R.string.now_playing_background)) }
            }

            Spacer(Modifier.weight(0.5f))
            ArtworkImage(
                artwork,
                Modifier.widthIn(max = 360.dp).fillMaxWidth().aspectRatio(1f).clip(MaterialTheme.shapes.extraLarge),
            )
            AudioVisualizer(
                analyzer = viewModel.spectrum,
                style = style,
                primary = MaterialTheme.colorScheme.primary,
                secondary = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.fillMaxWidth().height(if (style == VisualizerStyle.NEON_RING) 140.dp else 72.dp).padding(top = 12.dp),
            )
            Spacer(Modifier.weight(0.3f))

            Text(np.title.ifEmpty { stringResource(R.string.now_playing_nothing) }, style = MaterialTheme.typography.headlineSmall, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
            np.artist?.let { Text(it, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis) }

            val duration = np.durationMs.coerceAtLeast(1)
            Slider(
                value = dragPosition ?: (position.toFloat() / duration).coerceIn(0f, 1f),
                onValueChange = { dragPosition = it },
                onValueChangeFinished = {
                    dragPosition?.let { viewModel.player.seekTo((it * duration).toLong()) }
                    dragPosition = null
                },
                enabled = np.hasMedia && np.durationMs > 0,
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
            )
            Row(Modifier.fillMaxWidth()) {
                Text(formatDuration(dragPosition?.let { (it * duration).toLong() } ?: position), style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.weight(1f))
                Text(formatDuration(np.durationMs), style = MaterialTheme.typography.labelMedium)
            }

            Row(
                Modifier.fillMaxWidth().padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = viewModel.player::toggleShuffle) {
                    Icon(Icons.Filled.Shuffle, stringResource(R.string.player_shuffle), tint = if (np.shuffle) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = viewModel.player::previous, enabled = np.hasMedia) {
                    Icon(Icons.Filled.SkipPrevious, stringResource(R.string.player_previous), Modifier.size(36.dp))
                }
                FilledIconButton(onClick = viewModel.player::togglePlayPause, enabled = np.hasMedia, modifier = Modifier.size(72.dp), colors = IconButtonDefaults.filledIconButtonColors()) {
                    Icon(if (np.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow, stringResource(if (np.isPlaying) R.string.player_pause else R.string.player_play), Modifier.size(40.dp))
                }
                IconButton(onClick = viewModel.player::next, enabled = np.hasNext) {
                    Icon(Icons.Filled.SkipNext, stringResource(R.string.player_next), Modifier.size(36.dp))
                }
                IconButton(onClick = viewModel.player::cycleRepeat) {
                    Icon(
                        if (np.repeatMode == Player.REPEAT_MODE_ONE) Icons.Filled.RepeatOne else Icons.Filled.Repeat,
                        stringResource(R.string.player_repeat),
                        tint = if (np.repeatMode != Player.REPEAT_MODE_OFF) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // Secondary actions, kept in their own row well away from transport controls.
            Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 16.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                TextButton(onClick = { showTimer = true }) {
                    Icon(Icons.Filled.Bedtime, null, tint = if (timer.active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(stringResource(R.string.sleep_timer_short), Modifier.padding(start = 6.dp))
                }
                TextButton(onClick = onOpenKaraoke, enabled = np.hasMedia && !np.isVideo) {
                    Icon(Icons.Filled.Mic, null)
                    Text(stringResource(R.string.karaoke_short), Modifier.padding(start = 6.dp))
                }
                Box {
                    TextButton(onClick = { styleMenu = true }) {
                        Icon(Icons.Filled.GraphicEq, null)
                        Text(stringResource(R.string.visualizer_short), Modifier.padding(start = 6.dp))
                    }
                    DropdownMenu(expanded = styleMenu, onDismissRequest = { styleMenu = false }) {
                        VisualizerStyle.entries.forEach { s ->
                            DropdownMenuItem(
                                text = { Text(stringResource(s.labelRes())) },
                                trailingIcon = if (s.pro && !proUnlocked) { { Icon(Icons.Filled.Lock, stringResource(R.string.locked)) } } else null,
                                onClick = {
                                    styleMenu = false
                                    if (s.pro && !viewModel.isProUnlocked()) pendingProStyle = s else viewModel.setVisualizerStyle(s)
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    pendingProStyle?.let { wanted ->
        RewardedOfferDialog(
            adManager = adManager,
            unlock = RewardedUnlock.PRO_VISUALIZERS,
            onWatch = {
                pendingProStyle = null
                activity?.let { act ->
                    adManager.showRewarded(act, RewardedUnlock.PRO_VISUALIZERS) { outcome ->
                        if (outcome == RewardOutcome.Granted) viewModel.setVisualizerStyle(wanted)
                    }
                }
            },
            onDismiss = { pendingProStyle = null },
        )
    }

    if (showTimer) {
        SleepTimerSheet(timer, viewModel::startSleepTimer, viewModel.sleepTimer::cancel) { showTimer = false }
    }

    if (showBackground) {
        ModalBottomSheet(onDismissRequest = { showBackground = false }) {
            Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.now_playing_background), style = MaterialTheme.typography.titleLarge)
                TextButton(onClick = {
                    pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }) { Text(stringResource(R.string.background_choose_photo)) }
                if (settings.backgroundPath != null) {
                    Text(stringResource(R.string.background_blur), style = MaterialTheme.typography.labelLarge)
                    Slider(value = settings.backgroundBlur, onValueChange = viewModel::setBackgroundBlur)
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                        Text(stringResource(R.string.background_blur_unsupported), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(onClick = { viewModel.clearBackground(); showBackground = false }) {
                        Text(stringResource(R.string.background_use_artwork))
                    }
                }
            }
        }
    }
}

private fun VisualizerStyle.labelRes(): Int = when (this) {
    VisualizerStyle.BARS -> R.string.visualizer_bars
    VisualizerStyle.WAVE -> R.string.visualizer_wave
    VisualizerStyle.NEON_RING -> R.string.visualizer_neon_ring
}
