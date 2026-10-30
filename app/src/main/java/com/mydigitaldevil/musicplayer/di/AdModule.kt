package com.mydigitaldevil.musicplayer.di

import android.content.Context
import android.os.SystemClock
import androidx.core.content.edit
import com.mydigitaldevil.musicplayer.ads.AdPolicy
import com.mydigitaldevil.musicplayer.ads.CapStore
import com.mydigitaldevil.musicplayer.ads.FrequencyCapper
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AdModule {

    @Provides
    @Singleton
    fun provideAdPolicy(): AdPolicy = AdPolicy()

    @Provides
    @Singleton
    fun provideCapStore(@ApplicationContext context: Context): CapStore {
        // Synchronous SharedPreferences on purpose: read once on the main thread at ON_START,
        // where a suspending DataStore read would race the App Open decision.
        val prefs = context.getSharedPreferences("ad_caps", Context.MODE_PRIVATE)
        return object : CapStore {
            override var lastAppOpenShownAtWallMs: Long
                get() = prefs.getLong("last_app_open", 0L)
                set(value) = prefs.edit { putLong("last_app_open", value) }
        }
    }

    @Provides
    @Singleton
    fun provideFrequencyCapper(policy: AdPolicy, store: CapStore): FrequencyCapper = FrequencyCapper(
        policy = policy,
        monotonicNow = SystemClock::elapsedRealtime,
        wallNow = System::currentTimeMillis,
        store = store,
    )
}
