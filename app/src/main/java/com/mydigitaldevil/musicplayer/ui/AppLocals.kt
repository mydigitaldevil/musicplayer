package com.mydigitaldevil.musicplayer.ui

import androidx.compose.runtime.staticCompositionLocalOf
import com.mydigitaldevil.musicplayer.ads.AdManager

/** The process-wide ad manager, provided by MainActivity so any screen can place ads safely. */
val LocalAdManager = staticCompositionLocalOf<AdManager> { error("AdManager not provided") }
