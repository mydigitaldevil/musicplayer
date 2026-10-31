package com.mydigitaldevil.musicplayer.soundboard

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.mydigitaldevil.musicplayer.MainActivity
import com.mydigitaldevil.musicplayer.R
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Quick Settings tile that plays the first pinned pad, so a drop is one swipe away from any app,
 * including over a game, without an overlay window. With nothing pinned, it opens the soundboard.
 */
@AndroidEntryPoint
class SoundboardTileService : TileService() {

    @Inject lateinit var repository: SoundboardRepository
    @Inject lateinit var player: SoundboardPlayer

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var listening: Job? = null

    override fun onStartListening() {
        super.onStartListening()
        listening = scope.launch {
            repository.pinnedPads.collect { pads -> render(pads.firstOrNull()) }
        }
    }

    override fun onStopListening() {
        listening?.cancel()
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        scope.launch {
            val pad = repository.pinnedPads.first().firstOrNull()
            if (pad != null) player.play(pad) else openSoundboard()
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun render(pad: SoundPad?) {
        val tile = qsTile ?: return
        tile.label = pad?.label ?: getString(R.string.soundboard_tile_label)
        tile.state = if (pad != null) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = getString(if (pad != null) R.string.soundboard_tile_tap_to_play else R.string.soundboard_tile_pin_hint)
        }
        tile.updateTile()
    }

    private fun openSoundboard() {
        val intent = Intent(this, MainActivity::class.java)
            .setAction(MainActivity.ACTION_OPEN_SOUNDBOARD)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // The Intent overload throws on API 34+; only the PendingIntent one is allowed.
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE))
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}
