package com.mydigitaldevil.musicplayer.di

import android.content.Context
import androidx.room.Room
import com.mydigitaldevil.musicplayer.stats.StatsDao
import com.mydigitaldevil.musicplayer.stats.StatsDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Qualifier
import javax.inject.Singleton

/** Process-lifetime scope for work that must outlive a screen or service (e.g. final stats writes). */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope

@Module
@InstallIn(SingletonComponent::class)
object CoreModule {

    @Provides
    @Singleton
    @ApplicationScope
    fun provideApplicationScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Provides
    @Singleton
    fun provideStatsDatabase(@ApplicationContext context: Context): StatsDatabase =
        Room.databaseBuilder(context, StatsDatabase::class.java, "listening_stats.db").build()

    @Provides
    fun provideStatsDao(db: StatsDatabase): StatsDao = db.dao()
}
