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
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.app.PictureInPictureModeChangedInfo
import androidx.core.util.Consumer
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.mydigitaldevil.musicplayer.ads.AdManager
import com.mydigitaldevil.musicplayer.core.AppPreferences
import com.mydigitaldevil.musicplayer.core.AppSettings
import com.mydigitaldevil.musicplayer.library.LocalThumbnailLoader
import com.mydigitaldevil.musicplayer.library.MediaStoreRepository
import com.mydigitaldevil.musicplayer.library.ThumbnailLoader
import com.mydigitaldevil.musicplayer.playback.PlaybackSnapshot
import com.mydigitaldevil.musicplayer.playback.PlaybackStateRepository
import com.mydigitaldevil.musicplayer.playback.PlayerConnection
import com.mydigitaldevil.musicplayer.playback.SleepTimer
import com.mydigitaldevil.musicplayer.soundboard.ui.SoundboardSheet
import com.mydigitaldevil.musicplayer.soundboard.ui.SoundboardViewModel
import com.mydigitaldevil.musicplayer.ui.AppRoot
import com.mydigitaldevil.musicplayer.ui.LocalAdManager
import com.mydigitaldevil.musicplayer.ui.Routes
import com.mydigitaldevil.musicplayer.ui.theme.MusicPlayerTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var adManager: AdManager
    @Inject lateinit var playbackState: PlaybackStateRepository
    @Inject lateinit var player: PlayerConnection
    @Inject lateinit var prefs: AppPreferences
    @Inject lateinit var thumbnails: ThumbnailLoader
    @Inject lateinit var sleepTimer: SleepTimer
    @Inject lateinit var mediaStore: MediaStoreRepository

    private val soundboardViewModel: SoundboardViewModel by viewModels()

    private var inPip by mutableStateOf(false)
    private var soundboardOpen by mutableStateOf(false)
    private var externalRoute by mutableStateOf<String?>(null)
    /** PiP is only offered while the video screen is on top. */
    private val videoScreenActive = MutableStateFlow(false)
    private val pipListener = Consumer<PictureInPictureModeChangedInfo> { inPip = it.isInPictureInPictureMode }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge() // mandatory on API 36: the edge-to-edge opt-out no longer exists
        super.onCreate(savedInstanceState)
        addOnPictureInPictureModeChangedListener(pipListener)
        if (savedInstanceState == null) handleIntent(intent) // not again on recreation

        lifecycleScope.launch { adManager.initialize(this@MainActivity) }
        lifecycleScope.launch {
            prefs.settings.collect { adManager.setOnboardingComplete(it.onboardingDone) }
        }
        lifecycleScope.launch {
            sleepTimer.finished.collect { closeApp -> if (closeApp) finishAndRemoveTask() }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(playbackState.state, videoScreenActive, ::Pair).collect { (state, onVideo) -> updatePipParams(state, onVideo) }
            }
        }

        setContent {
            val settings by prefs.settings.collectAsStateWithLifecycle(initialValue = null as AppSettings?)
            val loaded = settings ?: run {
                // DataStore answers in a few ms; render a plain surface rather than guess the start screen.
                Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black))
                return@setContent
            }
            val startRoute = remember { if (loaded.onboardingDone) Routes.LIBRARY else Routes.ONBOARDING }

            MusicPlayerTheme(dynamicColor = loaded.dynamicColor) {
                CompositionLocalProvider(
                    LocalAdManager provides adManager,
                    LocalThumbnailLoader provides thumbnails,
                ) {
                    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                        AppRoot(
                            startRoute = startRoute,
                            player = player,
                            inPip = inPip,
                            externalRoute = externalRoute,
                            onExternalRouteHandled = { externalRoute = null },
                            onOnboardingFinished = { lifecycleScope.launch { prefs.setOnboardingDone(true) } },
                            onOpenSoundboard = { soundboardOpen = true },
                            onEnterPip = ::enterPip,
                            onVideoScreenActive = { videoScreenActive.value = it },
                        )
                    }
                    if (soundboardOpen && !inPip) {
                        SoundboardSheet(soundboardViewModel, onDismiss = { soundboardOpen = false })
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        player.connect()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onDestroy() {
        removeOnPictureInPictureModeChangedListener(pipListener)
        super.onDestroy()
    }

    private fun handleIntent(intent: Intent?) {
        when (intent?.action) {
            ACTION_OPEN_SOUNDBOARD -> soundboardOpen = true
            ACTION_OPEN_AMBIENT -> externalRoute = Routes.AMBIENT
            Intent.ACTION_VIEW -> intent.data?.let { uri ->
                // A song or video opened from a file manager, browser download, chat app…
                val type = intent.type ?: contentResolver.getType(uri)
                val isVideo = type?.startsWith("video/") == true || type == "application/x-matroska"
                lifecycleScope.launch {
                    val title = mediaStore.displayName(uri) ?: uri.lastPathSegment ?: getString(R.string.app_name)
                    val item = MediaItem.Builder()
                        .setMediaId("${if (isVideo) "video" else "audio"}:ext:$uri")
                        .setUri(uri)
                        .setRequestMetadata(MediaItem.RequestMetadata.Builder().setMediaUri(uri).build())
                        .setMediaMetadata(MediaMetadata.Builder().setTitle(title).build())
                        .build()
                    player.play(listOf(item), 0)
                    externalRoute = if (isVideo) Routes.VIDEO else Routes.NOW_PLAYING
                }
            }
        }
    }

    /**
     * Android 12+: PiP auto-enters on the home gesture via `setAutoEnterEnabled`, which gives the
     * smooth system animation. Enabled only while the video screen is showing a playing video.
     */
    private fun updatePipParams(state: PlaybackSnapshot, onVideoScreen: Boolean) {
        val builder = PictureInPictureParams.Builder().setAspectRatio(pipAspectRatio(state))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setAutoEnterEnabled(onVideoScreen && state.isVideo && state.isPlaying)
                .setSeamlessResizeEnabled(true)
        }
        runCatching { setPictureInPictureParams(builder.build()) }
    }

    private fun enterPip() {
        if (!packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_PICTURE_IN_PICTURE)) return
        runCatching {
            enterPictureInPictureMode(PictureInPictureParams.Builder().setAspectRatio(pipAspectRatio(playbackState.state.value)).build())
        }
    }

    /** Android 8–11 have no auto-enter; enter manually when the user leaves mid-video. */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        val state = playbackState.state.value
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S && videoScreenActive.value && state.isVideo && state.isPlaying) {
            enterPip()
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
        /** Opens the ambient mixer; used by the ambient notification. */
        const val ACTION_OPEN_AMBIENT = "com.mydigitaldevil.musicplayer.OPEN_AMBIENT"
    }
}
