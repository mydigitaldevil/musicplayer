package com.mydigitaldevil.musicplayer

import android.app.Application
import com.mydigitaldevil.musicplayer.ads.AdManager
import com.mydigitaldevil.musicplayer.soundboard.QuickPadsNotifier
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class MusicPlayerApp : Application() {

    @Inject lateinit var adManager: AdManager
    @Inject lateinit var quickPads: QuickPadsNotifier

    override fun onCreate() {
        super.onCreate()
        // Only registers lifecycle observers. The SDK itself is initialised after consent,
        // from MainActivity, so no ad request can precede the UMP flow.
        adManager.attach(this)
        // Re-syncs the quick-pads notification with pinned pads whenever the process starts.
        quickPads.start()
    }
}
