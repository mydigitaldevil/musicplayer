package com.mydigitaldevil.musicplayer.soundboard

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.annotation.CallSuper
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import kotlin.coroutines.resume

/**
 * Hilt injects receivers inside a generated `onReceive`, which subclasses must call through
 * `super`. Kotlin can't call the abstract `BroadcastReceiver.onReceive`, so this concrete base
 * class sits in between.
 */
abstract class HiltBroadcastReceiver : BroadcastReceiver() {
    @CallSuper
    override fun onReceive(context: Context, intent: Intent) = Unit
}

/** Handles taps on the quick-pads notification. */
@AndroidEntryPoint
class QuickPadReceiver : HiltBroadcastReceiver() {

    @Inject lateinit var repository: SoundboardRepository
    @Inject lateinit var player: SoundboardPlayer

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        val pending = goAsync()
        scope.launch {
            try {
                when (intent.action) {
                    ACTION_PLAY -> intent.getStringExtra(EXTRA_PAD_ID)
                        ?.let { repository.padById(it) }
                        ?.let { pad ->
                            // Keep the process prioritised until the clip ends, within the
                            // broadcast deadline. A clip still playing past it is allowed to finish.
                            withTimeoutOrNull(MAX_HOLD_MS) {
                                suspendCancellableCoroutine { cont -> player.play(pad) { cont.resume(Unit) } }
                            }
                        }
                    ACTION_DISMISSED -> repository.setQuickPadsEnabled(false)
                }
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_PLAY = "com.mydigitaldevil.musicplayer.QUICK_PAD_PLAY"
        const val ACTION_DISMISSED = "com.mydigitaldevil.musicplayer.QUICK_PADS_DISMISSED"
        const val EXTRA_PAD_ID = "pad_id"

        /** Below the 10 s foreground-broadcast limit. */
        private const val MAX_HOLD_MS = 8_000L
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    }
}
