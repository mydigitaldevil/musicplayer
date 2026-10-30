package com.mydigitaldevil.musicplayer

import android.app.Application
import com.mydigitaldevil.musicplayer.ads.AdManager
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class MusicPlayerApp : Application() {

    @Inject lateinit var adManager: AdManager

    override fun onCreate() {
        super.onCreate()
        // Only registers lifecycle observers. The SDK itself is initialised after consent,
        // from MainActivity, so no ad request can precede the UMP flow.
        adManager.attach(this)
    }
}
