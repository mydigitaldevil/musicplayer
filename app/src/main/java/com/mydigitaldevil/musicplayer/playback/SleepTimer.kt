package com.mydigitaldevil.musicplayer.playback

import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

data class SleepTimerState(
    val endsAtElapsedMs: Long? = null,
    val fadeMs: Long = 0,
    val closeApp: Boolean = false,
) {
    val active: Boolean get() = endsAtElapsedMs != null
    fun remainingMs(now: Long = SystemClock.elapsedRealtime()): Long = ((endsAtElapsedMs ?: now) - now).coerceAtLeast(0)
}

/**
 * Smart sleep timer shared by the music player and the ambient mixer.
 *
 * Both observe [volume] (1 → 0 linearly over the final [SleepTimerState.fadeMs]) and react to
 * [finished] by pausing / stopping. With `closeApp`, the activity also finishes its task.
 */
@Singleton
class SleepTimer @Inject constructor() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null

    private val _state = MutableStateFlow(SleepTimerState())
    val state: StateFlow<SleepTimerState> = _state.asStateFlow()

    private val _volume = MutableStateFlow(1f)
    val volume: StateFlow<Float> = _volume.asStateFlow()

    private val _finished = MutableSharedFlow<Boolean>(extraBufferCapacity = 1)
    /** Emits `closeApp` once when the timer runs out. */
    val finished: SharedFlow<Boolean> = _finished.asSharedFlow()

    fun start(durationMs: Long, fadeMs: Long, closeApp: Boolean) {
        job?.cancel()
        val fade = fadeMs.coerceIn(0, durationMs)
        val endsAt = SystemClock.elapsedRealtime() + durationMs
        _state.value = SleepTimerState(endsAt, fade, closeApp)
        _volume.value = 1f
        job = scope.launch {
            while (true) {
                val remaining = endsAt - SystemClock.elapsedRealtime()
                if (remaining <= 0) break
                _volume.value = if (fade > 0 && remaining < fade) remaining.toFloat() / fade else 1f
                delay(minOf(TICK_MS, remaining))
            }
            _volume.value = 0f
            _state.value = SleepTimerState()
            _finished.emit(closeApp)
            // Give players time to pause at volume 0 before restoring it for the next play.
            delay(RESTORE_DELAY_MS)
            _volume.value = 1f
        }
    }

    fun cancel() {
        job?.cancel()
        job = null
        _state.value = SleepTimerState()
        _volume.value = 1f
    }

    private companion object {
        const val TICK_MS = 250L
        const val RESTORE_DELAY_MS = 1_500L
    }
}
