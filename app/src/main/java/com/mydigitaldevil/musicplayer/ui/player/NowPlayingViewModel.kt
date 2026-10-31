package com.mydigitaldevil.musicplayer.ui.player

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mydigitaldevil.musicplayer.ads.RewardUnlockStore
import com.mydigitaldevil.musicplayer.ads.RewardedUnlock
import com.mydigitaldevil.musicplayer.audio.SpectrumAnalyzer
import com.mydigitaldevil.musicplayer.core.AppPreferences
import com.mydigitaldevil.musicplayer.core.AppSettings
import com.mydigitaldevil.musicplayer.core.VisualizerStyle
import com.mydigitaldevil.musicplayer.playback.PlayerConnection
import com.mydigitaldevil.musicplayer.playback.SleepTimer
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

@HiltViewModel
class NowPlayingViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    val player: PlayerConnection,
    val spectrum: SpectrumAnalyzer,
    val sleepTimer: SleepTimer,
    private val prefs: AppPreferences,
    unlocks: RewardUnlockStore,
) : ViewModel() {

    val settings: StateFlow<AppSettings> = prefs.settings.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    val proVisualizersUntil: StateFlow<Long> = unlocks.unlockedUntil(RewardedUnlock.PRO_VISUALIZERS)
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0L)

    /** Custom background photo, decoded once per path change. */
    val background: StateFlow<ImageBitmap?> = settings
        .map { it.backgroundPath }
        .distinctUntilChanged()
        .map { path -> path?.let { decodeFile(it, BACKGROUND_MAX_PX)?.asImageBitmap() } }
        .flowOn(Dispatchers.IO)
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    fun isProUnlocked(now: Long = System.currentTimeMillis()) = proVisualizersUntil.value > now

    fun setVisualizerStyle(style: VisualizerStyle) = viewModelScope.launch { prefs.setVisualizerStyle(style) }

    fun setBackgroundBlur(blur: Float) = viewModelScope.launch { prefs.setBackgroundBlur(blur) }

    fun clearBackground() = viewModelScope.launch {
        settings.value.backgroundPath?.let { File(it).delete() }
        prefs.setBackgroundPath(null)
    }

    /**
     * Copies the picked photo into app storage (downscaled). Photo Picker grants are temporary, so
     * keeping only the URI would lose the background after a restart.
     */
    fun setBackground(uri: Uri) = viewModelScope.launch(Dispatchers.IO) {
        val bitmap = decodeUri(uri, BACKGROUND_MAX_PX) ?: return@launch
        val old = prefs.settings.first().backgroundPath
        val file = File(context.filesDir, "player_background_${System.currentTimeMillis()}.jpg")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        prefs.setBackgroundPath(file.path)
        old?.let { File(it).delete() }
    }

    fun startSleepTimer(durationMs: Long, fadeMs: Long, closeApp: Boolean) = sleepTimer.start(durationMs, fadeMs, closeApp)

    private fun decodeUri(uri: Uri, maxPx: Int): Bitmap? = runCatching {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        resolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sampleFor(bounds, maxPx) })
        }
    }.getOrNull()

    private fun decodeFile(path: String, maxPx: Int): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sampleFor(bounds, maxPx) })
    }.getOrNull()

    private fun sampleFor(bounds: BitmapFactory.Options, maxPx: Int): Int {
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxPx) sample *= 2
        return sample
    }

    private companion object {
        const val BACKGROUND_MAX_PX = 1440
    }
}
