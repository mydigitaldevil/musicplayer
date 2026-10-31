package com.mydigitaldevil.musicplayer.ui.player

import android.app.Activity
import android.view.View
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import com.mydigitaldevil.musicplayer.R
import com.mydigitaldevil.musicplayer.playback.PlayerConnection
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class VideoPlayerViewModel @Inject constructor(val player: PlayerConnection) : ViewModel()

/**
 * Full-screen video. Leaving the screen (back, home, screen off) keeps the audio going in the
 * background. On home it auto-enters picture-in-picture (Android 12+), driven by
 * [onActiveChanged] -> MainActivity's PiP params.
 */
@OptIn(UnstableApi::class)
@Composable
fun VideoPlayerScreen(
    inPip: Boolean,
    onBack: () -> Unit,
    onEnterPip: () -> Unit,
    onActiveChanged: (Boolean) -> Unit,
    viewModel: VideoPlayerViewModel = hiltViewModel(),
) {
    val controller by viewModel.player.controller.collectAsStateWithLifecycle()
    var controlsVisible by remember { mutableStateOf(true) }
    val view = LocalView.current

    DisposableEffect(Unit) {
        onActiveChanged(true)
        onDispose { onActiveChanged(false) }
    }
    // Immersive while watching; bars come back with a swipe and are restored on exit.
    DisposableEffect(view) {
        val window = (view.context as? Activity)?.window
        val insets = window?.let { WindowCompat.getInsetsController(it, view) }
        insets?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        insets?.hide(WindowInsetsCompat.Type.systemBars())
        view.keepScreenOn = true
        onDispose {
            insets?.show(WindowInsetsCompat.Type.systemBars())
            view.keepScreenOn = false
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                PlayerView(ctx).apply {
                    setShowNextButton(true)
                    setShowPreviousButton(true)
                    setShowShuffleButton(true)
                    setShowSubtitleButton(true)
                    setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { visibility ->
                        controlsVisible = visibility == View.VISIBLE
                    })
                }
            },
            update = { playerView ->
                playerView.player = controller
                playerView.useController = !inPip // the PiP window gets system media controls instead
            },
            onRelease = { it.player = null },
        )
        if (!inPip && controlsVisible) {
            Row(
                Modifier.fillMaxWidth().statusBarsPadding().padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val buttonColors = IconButtonDefaults.iconButtonColors(contentColor = Color.White)
                IconButton(onClick = onBack, colors = buttonColors) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back))
                }
                Box(Modifier.weight(1f))
                IconButton(onClick = onEnterPip, colors = buttonColors) {
                    Icon(Icons.Filled.PictureInPictureAlt, stringResource(R.string.video_enter_pip))
                }
            }
        }
    }
}
