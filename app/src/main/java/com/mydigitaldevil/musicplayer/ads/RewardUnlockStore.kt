package com.mydigitaldevil.musicplayer.ads

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

private val Context.unlockStore by preferencesDataStore("reward_unlocks")

sealed interface KaraokeAccess {
    data object Unlimited : KaraokeAccess
    data object FreeSongUsed : KaraokeAccess
    /** Today's free song is gone; offer the rewarded unlock (never auto-play an ad). */
    data object NeedsReward : KaraokeAccess
}

@Singleton
class RewardUnlockStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val policy = AdPolicy()
    private val lastFreeKaraokeDay = stringPreferencesKey("free_karaoke_day")

    private fun expiryKey(unlock: RewardedUnlock) = longPreferencesKey("unlock_until_${unlock.name}")

    /** Wall-clock millis until which [unlock] is active (0 = never unlocked). */
    fun unlockedUntil(unlock: RewardedUnlock): Flow<Long> =
        context.unlockStore.data.map { it[expiryKey(unlock)] ?: 0L }

    suspend fun isUnlocked(unlock: RewardedUnlock, now: Long = System.currentTimeMillis()): Boolean =
        unlockedUntil(unlock).first() > now

    /** Only called from `OnUserEarnedRewardListener`, never on click or impression. */
    suspend fun grant(unlock: RewardedUnlock, now: Long = System.currentTimeMillis()) {
        context.unlockStore.edit { it[expiryKey(unlock)] = now + policy.rewardUnlockMs }
    }

    /** Gate for starting a karaoke song: 24 h unlock > one free song per calendar day > reward. */
    suspend fun tryStartKaraokeSong(today: LocalDate = LocalDate.now()): KaraokeAccess {
        if (isUnlocked(RewardedUnlock.UNLIMITED_KARAOKE)) return KaraokeAccess.Unlimited
        var granted = false
        context.unlockStore.edit { prefs ->
            if (prefs[lastFreeKaraokeDay] != today.toString()) {
                prefs[lastFreeKaraokeDay] = today.toString()
                granted = true
            }
        }
        return if (granted) KaraokeAccess.FreeSongUsed else KaraokeAccess.NeedsReward
    }
}
