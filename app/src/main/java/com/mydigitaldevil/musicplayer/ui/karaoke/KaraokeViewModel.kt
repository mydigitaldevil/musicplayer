package com.mydigitaldevil.musicplayer.ui.karaoke

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mydigitaldevil.musicplayer.ads.KaraokeAccess
import com.mydigitaldevil.musicplayer.ads.RewardUnlockStore
import com.mydigitaldevil.musicplayer.ads.RewardedUnlock
import com.mydigitaldevil.musicplayer.audio.KaraokeAudioControl
import com.mydigitaldevil.musicplayer.audio.fx.AmbiencePreset
import com.mydigitaldevil.musicplayer.audio.fx.MicFxEngine
import com.mydigitaldevil.musicplayer.audio.fx.MicFxState
import com.mydigitaldevil.musicplayer.audio.fx.VoicePreset
import com.mydigitaldevil.musicplayer.playback.NowPlaying
import com.mydigitaldevil.musicplayer.playback.PlayerConnection
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class KaraokeGate {
    /** Checking today's quota. */
    CHECKING,
    /** Karaoke is on for the current song. */
    OPEN,
    /** Today's free song is used up; waiting for the user to accept or decline the rewarded unlock. */
    LOCKED,
}

@HiltViewModel
class KaraokeViewModel @Inject constructor(
    val player: PlayerConnection,
    private val karaoke: KaraokeAudioControl,
    val mic: MicFxEngine,
    private val unlocks: RewardUnlockStore,
) : ViewModel() {

    val nowPlaying: StateFlow<NowPlaying> = player.nowPlaying
    val vocalRemoval: StateFlow<Float> = karaoke.vocalRemoval
    val micState: StateFlow<MicFxState> = mic.state

    private val _gate = MutableStateFlow(KaraokeGate.CHECKING)
    val gate: StateFlow<KaraokeGate> = _gate.asStateFlow()

    /** True once any song was actually sung; leaving after that is a natural break. */
    var sessionHappened = false
        private set

    private var preferredRemoval = 0.85f
    private val checkedSongs = mutableSetOf<String>()

    init {
        // Every new song started in karaoke mode goes through the daily gate once.
        viewModelScope.launch {
            player.nowPlaying
                .filter { it.mediaId != null && !it.isVideo }
                .distinctUntilChangedBy { it.mediaId }
                .collect { np -> checkGate(np.mediaId!!) }
        }
    }

    private suspend fun checkGate(mediaId: String) {
        if (mediaId in checkedSongs) return
        _gate.value = KaraokeGate.CHECKING
        when (unlocks.tryStartKaraokeSong()) {
            KaraokeAccess.Unlimited, KaraokeAccess.FreeSongUsed -> open(mediaId)
            KaraokeAccess.NeedsReward -> {
                karaoke.setVocalRemoval(0f)
                player.pause() // don't keep a "karaoke" song running without karaoke
                _gate.value = KaraokeGate.LOCKED
            }
        }
    }

    private fun open(mediaId: String) {
        checkedSongs += mediaId
        sessionHappened = true
        karaoke.setVocalRemoval(preferredRemoval)
        _gate.value = KaraokeGate.OPEN
        // Sensible karaoke defaults for the live mic: clean voice in a club-sized room.
        mic.setVoice(VoicePreset.NONE)
        mic.setAmbience(AmbiencePreset.CLUB)
    }

    /** Called after the rewarded ad paid out (the 24 h unlock is already stored). */
    fun onRewardGranted() = viewModelScope.launch {
        nowPlaying.value.mediaId?.let {
            if (unlocks.isUnlocked(RewardedUnlock.UNLIMITED_KARAOKE)) {
                open(it)
                player.togglePlayPause()
            }
        }
    }

    fun setVocalRemoval(v: Float) {
        if (_gate.value != KaraokeGate.OPEN) return
        preferredRemoval = v
        karaoke.setVocalRemoval(v)
    }

    fun setEcho(v: Float) = mic.setWet(v)
    fun setMicVolume(v: Float) = mic.setInputGain(v)

    /** Leaving karaoke always returns the player to the normal mix. */
    override fun onCleared() {
        karaoke.setVocalRemoval(0f)
        super.onCleared()
    }
}
