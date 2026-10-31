package com.mydigitaldevil.musicplayer

import android.app.PictureInPictureParams
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.core.app.PictureInPictureModeChangedInfo
import androidx.core.util.Consumer
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.mydigitaldevil.musicplayer.ads.AdManager
import com.mydigitaldevil.musicplayer.ads.ui.BannerPlacement
import com.mydigitaldevil.musicplayer.ads.ui.SafeBannerAd
import com.mydigitaldevil.musicplayer.playback.PlaybackSnapshot
import com.mydigitaldevil.musicplayer.playback.PlaybackStateRepository
import com.mydigitaldevil.musicplayer.soundboard.ui.SoundboardSheet
import com.mydigitaldevil.musicplayer.soundboard.ui.SoundboardViewModel
import com.mydigitaldevil.musicplayer.ui.theme.MusicPlayerTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var adManager: AdManager
    @Inject lateinit var playbackState: PlaybackStateRepository

    private val soundboardViewModel: SoundboardViewModel by viewModels()

    private var inPip by mutableStateOf(false)
    private var soundboardOpen by mutableStateOf(false)
    private val pipListener = Consumer<PictureInPictureModeChangedInfo> { inPip = it.isInPictureInPictureMode }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge() // mandatory on API 36: the edge-to-edge opt-out no longer exists
        super.onCreate(savedInstanceState)
        addOnPictureInPictureModeChangedListener(pipListener)
        if (savedInstanceState == null) handleIntent(intent) // not again on recreation

        lifecycleScope.launch { adManager.initialize(this@MainActivity) }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                playbackState.state.collect(::updatePipParams)
            }
        }

        setContent {
            MusicPlayerTheme {
                Scaffold(
                    bottomBar = {
                        SafeBannerAd(adManager, BannerPlacement.BROWSE, hidden = inPip)
                    },
                    floatingActionButton = {
                        if (!inPip) {
                            FloatingActionButton(onClick = { soundboardOpen = true }) {
                                Icon(Icons.Filled.GridView, contentDescription = stringResource(R.string.soundboard_open))
                            }
                        }
                    },
                ) { padding ->
                    // Feature screens (library, player, mic FX, mixer, stats, onboarding) plug in here.
                    Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                        Text(stringResource(R.string.app_name))
                    }
                }
                if (soundboardOpen && !inPip) {
                    SoundboardSheet(soundboardViewModel, onDismiss = { soundboardOpen = false })
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.action == ACTION_OPEN_SOUNDBOARD) soundboardOpen = true
    }

    override fun onDestroy() {
        removeOnPictureInPictureModeChangedListener(pipListener)
        super.onDestroy()
    }

    /**
     * Android 12+: PiP auto-enters on the home gesture via `setAutoEnterEnabled`, which gives the
     * smooth system animation. It is enabled only while a video is actually playing.
     */
    private fun updatePipParams(state: PlaybackSnapshot) {
        val builder = PictureInPictureParams.Builder()
            .setAspectRatio(pipAspectRatio(state))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setAutoEnterEnabled(state.isVideo && state.isPlaying)
                .setSeamlessResizeEnabled(true)
        }
        setPictureInPictureParams(builder.build())
    }

    /** Android 8–11 have no auto-enter; enter manually when the user leaves mid-video. */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        val state = playbackState.state.value
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S && state.isVideo && state.isPlaying) {
            enterPictureInPictureMode(
                PictureInPictureParams.Builder().setAspectRatio(pipAspectRatio(state)).build(),
            )
        }
    }

    /** The framework rejects ratios outside 1:2.39 … 2.39:1, so extreme videos are clamped. */
    private fun pipAspectRatio(state: PlaybackSnapshot): Rational {
        if (state.videoWidth <= 0 || state.videoHeight <= 0) return Rational(16, 9)
        val ratio = (state.videoWidth.toFloat() / state.videoHeight).coerceIn(1f / 2.39f, 2.39f)
        return Rational((ratio * 10_000).toInt(), 10_000)
    }

    companion object {
        /** Opens the soundboard sheet; used by the quick-pads notification and the QS tile. */
        const val ACTION_OPEN_SOUNDBOARD = "com.mydigitaldevil.musicplayer.OPEN_SOUNDBOARD"
    }
}
