package com.mydigitaldevil.musicplayer.soundboard

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.SoundPool
import android.util.Log
import androidx.annotation.MainThread
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Plays soundboard drops on top of whatever else is playing.
 *
 * No audio focus is requested, so music, videos and games keep playing underneath. Audio goes to
 * the media stream: during a phone call it's heard locally only. Android gives regular apps no
 * way to inject audio into the call uplink, and this class doesn't try to.
 *
 * Presets go through [SoundPool] for near-zero tap latency. Recordings (which can exceed
 * SoundPool's per-sample memory limit on older devices) and any play that needs a completion
 * callback use a short-lived [MediaPlayer].
 */
@Singleton
class SoundboardPlayer @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    private val soundPool = SoundPool.Builder()
        .setMaxStreams(MAX_STREAMS)
        .setAudioAttributes(attributes)
        .build()

    private val sampleIds = mutableMapOf<Int, Int>() // raw res id -> SoundPool sample id
    private val readySamples = mutableSetOf<Int>()
    private val playWhenLoaded = mutableSetOf<Int>()
    private val poolStreams = ArrayDeque<Int>()
    private val mediaPlayers = mutableMapOf<MediaPlayer, () -> Unit>() // player -> its finish()

    init {
        soundPool.setOnLoadCompleteListener { pool, sampleId, status ->
            if (status != 0) return@setOnLoadCompleteListener
            readySamples += sampleId
            if (playWhenLoaded.remove(sampleId)) playSample(pool, sampleId)
        }
    }

    /** Decodes presets ahead of time so the first tap is instant. */
    @MainThread
    fun preload(pads: List<SoundPad>) {
        pads.mapNotNull { (it.source as? PadSource.Preset)?.resId }.forEach(::sampleFor)
    }

    /**
     * Plays [pad]. With [onComplete] set, playback goes through MediaPlayer and the callback runs
     * once when the clip ends or fails.
     */
    @MainThread
    fun play(pad: SoundPad, onComplete: (() -> Unit)? = null) {
        val source = pad.source
        if (source is PadSource.Preset && onComplete == null) {
            val sampleId = sampleFor(source.resId)
            if (sampleId in readySamples) playSample(soundPool, sampleId) else playWhenLoaded += sampleId
        } else {
            playWithMediaPlayer(source, onComplete ?: {})
        }
    }

    @MainThread
    fun stopAll() {
        poolStreams.forEach(soundPool::stop)
        poolStreams.clear()
        playWhenLoaded.clear()
        // finish() releases the player and fires its onComplete, so callers waiting on a clip
        // (the quick-pad receiver) are released immediately rather than at their timeout.
        mediaPlayers.toList().forEach { (player, finish) ->
            runCatching { player.stop() }
            finish()
        }
    }

    private fun sampleFor(resId: Int): Int = sampleIds.getOrPut(resId) { soundPool.load(context, resId, 1) }

    private fun playSample(pool: SoundPool, sampleId: Int) {
        val streamId = pool.play(sampleId, 1f, 1f, 1, 0, 1f)
        if (streamId == 0) return
        poolStreams.addLast(streamId)
        while (poolStreams.size > MAX_STREAMS) poolStreams.removeFirst()
    }

    private fun playWithMediaPlayer(source: PadSource, onComplete: () -> Unit) {
        var done = false
        val player = MediaPlayer()
        val finish = {
            if (!done) {
                done = true
                if (mediaPlayers.remove(player) != null) player.release()
                onComplete()
            }
        }
        try {
            player.setAudioAttributes(attributes)
            when (source) {
                is PadSource.Preset -> context.resources.openRawResourceFd(source.resId).use { afd ->
                    player.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                }
                is PadSource.Recording -> player.setDataSource(source.path)
            }
            player.setOnPreparedListener { it.start() }
            player.setOnCompletionListener { finish() }
            player.setOnErrorListener { _, what, extra ->
                Log.w(TAG, "Pad playback failed: $what/$extra")
                finish()
                true
            }
            mediaPlayers[player] = finish
            player.prepareAsync()
        } catch (e: Exception) {
            Log.w(TAG, "Pad could not be opened", e)
            mediaPlayers.remove(player)
            player.release()
            done = true
            onComplete()
        }
    }

    private companion object {
        const val TAG = "SoundboardPlayer"
        const val MAX_STREAMS = 4
    }
}
