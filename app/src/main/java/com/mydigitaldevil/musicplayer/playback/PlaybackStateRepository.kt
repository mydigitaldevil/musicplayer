package com.mydigitaldevil.musicplayer.playback

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

data class PlaybackSnapshot(
    val isPlaying: Boolean = false,
    val playWhenReady: Boolean = false,
    val hasMedia: Boolean = false,
    val isVideo: Boolean = false,
    val videoWidth: Int = 0,
    val videoHeight: Int = 0,
) {
    /** True while the user expects sound to continue (playing, or buffering towards playing). */
    val isSessionActive: Boolean get() = hasMedia && (isPlaying || playWhenReady)
}

/**
 * Process-wide read model of the playback service. The service is the only writer; UI
 * (PiP params) and the ad layer (pausing banners / blocking App Open ads) are readers.
 */
@Singleton
class PlaybackStateRepository @Inject constructor() {
    private val _state = MutableStateFlow(PlaybackSnapshot())
    val state: StateFlow<PlaybackSnapshot> = _state.asStateFlow()

    internal fun update(snapshot: PlaybackSnapshot) {
        _state.value = snapshot
    }

    internal fun reset() {
        _state.value = PlaybackSnapshot()
    }
}
