package com.mydigitaldevil.musicplayer.core

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.appStore by preferencesDataStore("app_settings")

/** `pro` styles need the PRO_VISUALIZERS rewarded unlock. */
enum class VisualizerStyle(val pro: Boolean) { BARS(false), WAVE(true), NEON_RING(true) }

data class AppSettings(
    val onboardingDone: Boolean = false,
    val dynamicColor: Boolean = true,
    val visualizerStyle: VisualizerStyle = VisualizerStyle.BARS,
    /** Custom Now Playing background copied into app storage, or null for artwork. */
    val backgroundPath: String? = null,
    /** 0 = sharp, 1 = maximum blur. */
    val backgroundBlur: Float = 0.6f,
)

@Singleton
class AppPreferences @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val onboardingKey = booleanPreferencesKey("onboarding_done")
    private val dynamicColorKey = booleanPreferencesKey("dynamic_color")
    private val visualizerKey = stringPreferencesKey("visualizer_style")
    private val backgroundKey = stringPreferencesKey("background_path")
    private val blurKey = floatPreferencesKey("background_blur")

    val settings: Flow<AppSettings> = context.appStore.data.map { p ->
        AppSettings(
            onboardingDone = p[onboardingKey] ?: false,
            dynamicColor = p[dynamicColorKey] ?: true,
            visualizerStyle = p[visualizerKey]
                ?.let { name -> VisualizerStyle.entries.firstOrNull { it.name == name } }
                ?: VisualizerStyle.BARS,
            backgroundPath = p[backgroundKey],
            backgroundBlur = p[blurKey] ?: 0.6f,
        )
    }

    suspend fun setOnboardingDone(done: Boolean) = context.appStore.edit { it[onboardingKey] = done }
    suspend fun setDynamicColor(enabled: Boolean) = context.appStore.edit { it[dynamicColorKey] = enabled }
    suspend fun setVisualizerStyle(style: VisualizerStyle) = context.appStore.edit { it[visualizerKey] = style.name }
    suspend fun setBackgroundBlur(blur: Float) = context.appStore.edit { it[blurKey] = blur.coerceIn(0f, 1f) }

    suspend fun setBackgroundPath(path: String?) = context.appStore.edit {
        if (path == null) it.remove(backgroundKey) else it[backgroundKey] = path
    }
}
