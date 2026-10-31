package com.mydigitaldevil.musicplayer.playback

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.mydigitaldevil.musicplayer.MainActivity
import com.mydigitaldevil.musicplayer.R
import com.mydigitaldevil.musicplayer.audio.KaraokeAudioControl
import com.mydigitaldevil.musicplayer.audio.SpectrumAnalyzer
import com.mydigitaldevil.musicplayer.audio.VocalRemoverProcessor
import com.mydigitaldevil.musicplayer.di.ApplicationScope
import com.mydigitaldevil.musicplayer.stats.PlayEventLogger
import com.mydigitaldevil.musicplayer.stats.StatsDao
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Background / lock-screen playback for both audio and video.
 *
 * Media3 handles the foreground-service lifecycle, the media notification, lock-screen
 * controls, Bluetooth/headset media buttons and audio focus. UI talks to this service only
 * through a `MediaController` ([PlayerConnection]), so the activity can die (or sit in PiP)
 * while playback continues.
 */
@OptIn(UnstableApi::class)
@AndroidEntryPoint
class PlaybackService : MediaSessionService() {

    @Inject lateinit var playbackState: PlaybackStateRepository
    @Inject lateinit var sleepTimer: SleepTimer
    @Inject lateinit var spectrum: SpectrumAnalyzer
    @Inject lateinit var karaoke: KaraokeAudioControl
    @Inject lateinit var statsDao: StatsDao
    @Inject @field:ApplicationScope lateinit var appScope: CoroutineScope

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var mediaSession: MediaSession? = null
    private var statsLogger: PlayEventLogger? = null

    private val stateListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            val videoSize = player.videoSize
            playbackState.update(
                PlaybackSnapshot(
                    isPlaying = player.isPlaying,
                    playWhenReady = player.playWhenReady,
                    hasMedia = player.mediaItemCount > 0,
                    isVideo = player.currentTracks.isTypeSelected(C.TRACK_TYPE_VIDEO),
                    videoWidth = videoSize.width,
                    videoHeight = videoSize.height,
                ),
            )
        }
    }

    override fun onCreate() {
        super.onCreate()

        // Format coverage: platform decoders first; if one is missing or fails, fall back to the
        // next decoder and finally to the bundled FFmpeg audio renderer. MKV, MP4/MOV, WebM, AVI,
        // TS, FLV, OGG, FLAC, WAV, MP3, AAC, AMR and Opus are parsed by ExoPlayer's own extractors.
        val renderersFactory = EffectsRenderersFactory(this, VocalRemoverProcessor(karaoke), TeeAudioProcessor(spectrum))
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
            .setEnableDecoderFallback(true)
        // Lets MP3/AAC/AMR files without a seek index (common in old rips) still seek.
        val extractorsFactory = DefaultExtractorsFactory()
            .setConstantBitrateSeekingEnabled(true)
            .setConstantBitrateSeekingAlwaysEnabled(true)

        val player = ExoPlayer.Builder(this, renderersFactory)
            .setMediaSourceFactory(DefaultMediaSourceFactory(this, extractorsFactory))
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true) // pause when headphones are unplugged
            .setWakeMode(C.WAKE_MODE_LOCAL) // local files only; no Wi-Fi lock needed
            .setSeekBackIncrementMs(SEEK_INCREMENT_MS)
            .setSeekForwardIncrementMs(SEEK_INCREMENT_MS)
            .build()
        player.addListener(stateListener)
        statsLogger = PlayEventLogger(player, statsDao, appScope).also(player::addListener)

        mediaSession = MediaSession.Builder(this, player)
            .setId(SESSION_ID)
            .setSessionActivity(sessionActivityIntent())
            .setCallback(SessionCallback())
            .build()

        setMediaNotificationProvider(
            DefaultMediaNotificationProvider.Builder(this)
                .setChannelId(NOTIFICATION_CHANNEL_ID)
                .setChannelName(R.string.notification_channel_playback)
                .build()
                .apply { setSmallIcon(R.drawable.ic_notification) },
        )

        // Smart sleep timer: follow its fade curve, then pause (and stop the service if the
        // user asked for the app to close).
        scope.launch { sleepTimer.volume.collect { player.volume = it } }
        scope.launch {
            sleepTimer.finished.collect { closeApp ->
                player.pause()
                if (closeApp) pauseAllPlayersAndStopSelf()
            }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    /**
     * Swiping the app away from Recents keeps music going if the user is listening, and
     * tears everything down (removing the notification) if nothing is playing.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!isPlaybackOngoing) pauseAllPlayersAndStopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        statsLogger?.flush()
        mediaSession?.run {
            player.removeListener(stateListener)
            statsLogger?.let(player::removeListener)
            player.release()
            release()
        }
        mediaSession = null
        statsLogger = null
        playbackState.reset()
        karaoke.setVocalRemoval(0f)
        super.onDestroy()
    }

    private fun sessionActivityIntent(): PendingIntent = PendingIntent.getActivity(
        this,
        0,
        Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private inner class SessionCallback : MediaSession.Callback {
        /**
         * Controllers (including our own UI) send MediaItems that may only carry a request URI.
         * Resolve them into playable items so ExoPlayer has a `localConfiguration`.
         */
        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> = Futures.immediateFuture(
            mediaItems.mapTo(mutableListOf()) { item ->
                val uri = item.requestMetadata.mediaUri
                if (item.localConfiguration == null && uri != null) item.buildUpon().setUri(uri).build() else item
            },
        )
    }

    /** Inserts our PCM processors (vocal remover, then the visualizer tap) into the audio sink. */
    private class EffectsRenderersFactory(
        context: Context,
        private val vocalRemover: VocalRemoverProcessor,
        private val spectrumTap: TeeAudioProcessor,
    ) : DefaultRenderersFactory(context) {
        override fun buildAudioSink(
            context: Context,
            enableFloatOutput: Boolean,
            enableAudioTrackPlaybackParams: Boolean,
        ): AudioSink = DefaultAudioSink.Builder(context)
            .setEnableFloatOutput(enableFloatOutput)
            .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
            .setAudioProcessors(arrayOf(vocalRemover, spectrumTap))
            .build()
    }

    private companion object {
        const val SESSION_ID = "main"
        const val NOTIFICATION_CHANNEL_ID = "playback"
        const val SEEK_INCREMENT_MS = 10_000L
    }
}
