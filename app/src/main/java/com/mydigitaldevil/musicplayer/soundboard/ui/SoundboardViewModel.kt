package com.mydigitaldevil.musicplayer.soundboard.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mydigitaldevil.musicplayer.soundboard.AddPadResult
import com.mydigitaldevil.musicplayer.soundboard.PadSource
import com.mydigitaldevil.musicplayer.soundboard.PinResult
import com.mydigitaldevil.musicplayer.soundboard.QuickPadsNotifier
import com.mydigitaldevil.musicplayer.soundboard.SoundPad
import com.mydigitaldevil.musicplayer.soundboard.SoundboardPlayer
import com.mydigitaldevil.musicplayer.soundboard.SoundboardRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

data class SoundboardUiState(
    val pads: List<SoundPad> = emptyList(),
    val pinnedIds: List<String> = emptyList(),
    val quickPadsEnabled: Boolean = false,
    /** Recordings not yet bound to a pad. */
    val recordings: List<File> = emptyList(),
    val message: SoundboardMessage? = null,
)

enum class SoundboardMessage { PIN_LIMIT, NEED_PIN_FIRST, RECORDING_TOO_LONG, RECORDING_UNREADABLE, NOTIFICATIONS_BLOCKED }

sealed interface SoundboardIntent {
    data class Play(val pad: SoundPad) : SoundboardIntent
    data class TogglePin(val pad: SoundPad) : SoundboardIntent
    data class Remove(val pad: SoundPad) : SoundboardIntent
    data class AddRecording(val file: File) : SoundboardIntent
    /** [permissionGranted] is the POST_NOTIFICATIONS result when turning quick pads on. */
    data class SetQuickPads(val enabled: Boolean, val permissionGranted: Boolean = true) : SoundboardIntent
    data object StopAll : SoundboardIntent
    data object RefreshRecordings : SoundboardIntent
    data object MessageShown : SoundboardIntent
}

@HiltViewModel
class SoundboardViewModel @Inject constructor(
    private val repository: SoundboardRepository,
    private val player: SoundboardPlayer,
    private val notifier: QuickPadsNotifier,
) : ViewModel() {

    private val recordings = MutableStateFlow<List<File>>(emptyList())
    private val message = MutableStateFlow<SoundboardMessage?>(null)

    val state: StateFlow<SoundboardUiState> = combine(
        repository.pads,
        repository.pinnedIds,
        repository.quickPadsEnabled,
        recordings,
        message,
    ) { pads, pinned, quick, recs, msg ->
        val bound = pads.mapNotNull { (it.source as? PadSource.Recording)?.path }.toSet()
        SoundboardUiState(pads, pinned, quick, recs.filterNot { it.path in bound }, msg)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SoundboardUiState())

    init {
        player.preload(repository.presets)
        onIntent(SoundboardIntent.RefreshRecordings)
    }

    fun onIntent(intent: SoundboardIntent) {
        when (intent) {
            is SoundboardIntent.Play -> player.play(intent.pad)
            SoundboardIntent.StopAll -> player.stopAll()
            SoundboardIntent.MessageShown -> message.value = null
            SoundboardIntent.RefreshRecordings -> viewModelScope.launch {
                recordings.value = repository.availableRecordings()
            }
            is SoundboardIntent.TogglePin -> viewModelScope.launch {
                if (repository.togglePin(intent.pad.id) == PinResult.LIMIT_REACHED) message.value = SoundboardMessage.PIN_LIMIT
            }
            is SoundboardIntent.Remove -> viewModelScope.launch { repository.remove(intent.pad.id) }
            is SoundboardIntent.AddRecording -> viewModelScope.launch {
                when (repository.addRecording(intent.file)) {
                    is AddPadResult.Added -> recordings.update { list -> list - intent.file }
                    AddPadResult.TooLong -> message.value = SoundboardMessage.RECORDING_TOO_LONG
                    AddPadResult.Unreadable -> message.value = SoundboardMessage.RECORDING_UNREADABLE
                }
            }
            is SoundboardIntent.SetQuickPads -> viewModelScope.launch {
                when {
                    !intent.enabled -> repository.setQuickPadsEnabled(false)
                    !intent.permissionGranted || !notifier.canPostNotifications() ->
                        message.value = SoundboardMessage.NOTIFICATIONS_BLOCKED
                    repository.pinnedIds.first().isEmpty() -> message.value = SoundboardMessage.NEED_PIN_FIRST
                    else -> repository.setQuickPadsEnabled(true)
                }
            }
        }
    }
}
