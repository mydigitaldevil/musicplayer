package com.mydigitaldevil.musicplayer.ads

import com.mydigitaldevil.musicplayer.BuildConfig

/** Ad unit IDs. Debug builds get Google's test units (see app/build.gradle.kts). */
object AdUnits {
    val BANNER = BuildConfig.AD_BANNER
    val INTERSTITIAL = BuildConfig.AD_INTERSTITIAL
    val REWARDED = BuildConfig.AD_REWARDED
    val NATIVE = BuildConfig.AD_NATIVE
    val APP_OPEN = BuildConfig.AD_APP_OPEN
}

/**
 * Every pacing rule in one place, so a policy review reads one file.
 * Times are in milliseconds.
 */
data class AdPolicy(
    /** Minimum gap between two interstitials. */
    val interstitialCooldownMs: Long = 90_000,
    /** No interstitial in the first N ms of a process: interstitials never act as an app-open ad. */
    val interstitialSessionGraceMs: Long = 90_000,
    val maxInterstitialsPerSession: Int = 6,
    /** Gap after *any* full-screen ad closes before the next one (no back-to-back ads). */
    val fullScreenGapMs: Long = 90_000,
    /** App Open ads at most once per this interval, persisted across process restarts. */
    val appOpenMinIntervalMs: Long = 4 * HOUR,
    /** Quick app switches (copying a link, answering a notification) never trigger an App Open ad. */
    val appOpenMinBackgroundMs: Long = 30_000,
    /** Google: App Open ads expire after 4 h; interstitial / rewarded are refreshed after 1 h. */
    val appOpenExpiryMs: Long = 4 * HOUR,
    val fullScreenExpiryMs: Long = 1 * HOUR,
    /** Failed loads back off exponentially from this base up to [loadRetryMaxMs]. */
    val loadRetryBaseMs: Long = 5_000,
    val loadRetryMaxMs: Long = 5 * 60_000,
    val maxLoadRetries: Int = 5,
    /** Duration of a rewarded unlock. */
    val rewardUnlockMs: Long = 24 * HOUR,
) {
    companion object {
        const val HOUR = 60 * 60_000L
    }
}

/**
 * The only moments an interstitial may appear. There is deliberately no entry for app open,
 * screen unlock, playback start/skip, or generic navigation.
 */
enum class NaturalBreak {
    RECORDING_EXPORTED,
    BACK_FROM_STATS,
    KARAOKE_SESSION_FINISHED,
}

enum class RewardedUnlock {
    UNLIMITED_KARAOKE,
    PRO_VISUALIZERS,
    EXCLUSIVE_MIC_FILTERS,
}

sealed interface CapDecision {
    data object Allowed : CapDecision
    data class Denied(val reason: String) : CapDecision
}

/** Persisted state that must survive process death (App Open interval is measured in hours). */
interface CapStore {
    var lastAppOpenShownAtWallMs: Long
}

/**
 * Pure frequency-capping logic. No Android or SDK types, so it is fully unit-tested.
 * Session-relative timing uses a monotonic clock (immune to the user changing the time);
 * only the multi-hour App Open interval uses wall time.
 * Not thread-safe: call from the main thread only.
 */
class FrequencyCapper(
    private val policy: AdPolicy,
    private val monotonicNow: () -> Long,
    private val wallNow: () -> Long,
    private val store: CapStore,
) {
    private val sessionStart = monotonicNow()
    private var lastInterstitialAt: Long? = null
    private var lastFullScreenClosedAt: Long? = null
    private var interstitialsThisSession = 0

    fun interstitialDecision(): CapDecision {
        val now = monotonicNow()
        return when {
            now - sessionStart < policy.interstitialSessionGraceMs -> CapDecision.Denied("session grace period")
            interstitialsThisSession >= policy.maxInterstitialsPerSession -> CapDecision.Denied("session cap reached")
            lastInterstitialAt?.let { now - it < policy.interstitialCooldownMs } == true ->
                CapDecision.Denied("interstitial cooldown")
            withinFullScreenGap(now) -> CapDecision.Denied("full-screen gap")
            else -> CapDecision.Allowed
        }
    }

    fun appOpenDecision(backgroundDurationMs: Long?): CapDecision {
        val wall = wallNow()
        val last = store.lastAppOpenShownAtWallMs
        return when {
            backgroundDurationMs == null || backgroundDurationMs < policy.appOpenMinBackgroundMs ->
                CapDecision.Denied("short app switch")
            // `wall < last` means the clock moved backwards: treat as "recently shown".
            last > 0 && (wall < last || wall - last < policy.appOpenMinIntervalMs) ->
                CapDecision.Denied("app open interval")
            withinFullScreenGap(monotonicNow()) -> CapDecision.Denied("full-screen gap")
            else -> CapDecision.Allowed
        }
    }

    fun recordInterstitialShown() {
        lastInterstitialAt = monotonicNow()
        interstitialsThisSession++
    }

    fun recordAppOpenShown() {
        store.lastAppOpenShownAtWallMs = wallNow()
    }

    fun recordFullScreenClosed() {
        lastFullScreenClosedAt = monotonicNow()
    }

    private fun withinFullScreenGap(now: Long) =
        lastFullScreenClosedAt?.let { now - it < policy.fullScreenGapMs } == true
}

/** A loaded ad plus when it was loaded, so stale inventory is never shown. */
internal class Loaded<T>(val ad: T, private val loadedAt: Long) {
    fun isFresh(now: Long, maxAgeMs: Long) = now - loadedAt < maxAgeMs
}
