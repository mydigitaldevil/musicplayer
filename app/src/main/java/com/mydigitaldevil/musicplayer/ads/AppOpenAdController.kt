package com.mydigitaldevil.musicplayer.ads

import android.app.Activity
import android.app.Application
import android.app.KeyguardManager
import android.content.Context
import android.os.Bundle
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.appopen.AppOpenAd
import com.mydigitaldevil.musicplayer.playback.PlaybackStateRepository
import java.lang.ref.WeakReference

/**
 * App Open ads, shown only on **warm** starts that are a deliberate return to the app.
 *
 * Never shown:
 *  - on a cold start (the process just launched; the user is waiting for their content),
 *  - when returning from the lock screen / screen-off (not a user-initiated "open"),
 *  - while a playback session is active (user came back to control their music),
 *  - after a quick app switch, an ad click-through, or our own share/picker intents,
 *  - before onboarding has been completed, or while any full-screen ad / suppression is active.
 */
internal class AppOpenAdController(
    private val context: Context,
    private val policy: AdPolicy,
    private val capper: FrequencyCapper,
    private val playback: PlaybackStateRepository,
    private val canShow: () -> Boolean,
    private val onShowingChanged: (Boolean) -> Unit,
    private val request: () -> AdRequest,
    private val retry: (attempt: Int, block: () -> Unit) -> Unit,
) : DefaultLifecycleObserver, Application.ActivityLifecycleCallbacks {

    var onboardingComplete = false

    private val powerManager = context.getSystemService(PowerManager::class.java)
    private val keyguardManager = context.getSystemService(KeyguardManager::class.java)

    private var currentActivity: WeakReference<Activity>? = null
    private var cached: Loaded<AppOpenAd>? = null
    private var loading = false
    private var showing = false

    private var hasStartedOnce = false
    private var backgroundedAt: Long? = null
    private var backgroundedByScreenOff = false
    private var suppressNext = false

    fun register(application: Application) {
        application.registerActivityLifecycleCallbacks(this)
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
    }

    fun suppressNext() {
        suppressNext = true
    }

    fun preload(attempt: Int = 0) {
        if (loading || cached?.isFresh(now(), policy.appOpenExpiryMs) == true) return
        loading = true
        AppOpenAd.load(context, AdUnits.APP_OPEN, request(), object : AppOpenAd.AppOpenAdLoadCallback() {
            override fun onAdLoaded(ad: AppOpenAd) {
                loading = false
                cached = Loaded(ad, now())
            }

            override fun onAdFailedToLoad(error: LoadAdError) {
                loading = false
                retry(attempt) { preload(attempt + 1) }
            }
        })
    }

    override fun onStop(owner: LifecycleOwner) {
        backgroundedAt = now()
        // When the screen turns off, onStop arrives after the display is already non-interactive.
        backgroundedByScreenOff = !powerManager.isInteractive
    }

    override fun onStart(owner: LifecycleOwner) {
        val coldStart = !hasStartedOnce
        hasStartedOnce = true
        val backgroundFor = backgroundedAt?.let { now() - it }
        val ad = cached?.takeIf { it.isFresh(now(), policy.appOpenExpiryMs) }?.ad
        val activity = currentActivity?.get()

        val skip = when {
            coldStart -> "cold start"
            suppressNext -> "suppressed (ad click-through / own intent)"
            backgroundedByScreenOff || keyguardManager.isKeyguardLocked -> "returning from lock screen"
            playback.state.value.isSessionActive -> "active playback session"
            !onboardingComplete -> "onboarding not complete"
            showing || !canShow() -> "blocked by ad manager"
            activity == null || activity.isFinishing || activity.isInPictureInPictureMode -> "no usable activity"
            ad == null -> "no fresh ad"
            else -> (capper.appOpenDecision(backgroundFor) as? CapDecision.Denied)?.reason
        }
        suppressNext = false
        backgroundedByScreenOff = false

        if (skip != null) {
            Log.d(TAG, "App Open skipped: $skip")
            if (ad == null) { cached = null; preload() }
            return
        }

        cached = null
        showing = true
        onShowingChanged(true)
        ad!!.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdShowedFullScreenContent() = capper.recordAppOpenShown()
            override fun onAdClicked() = suppressNext()
            override fun onAdDismissedFullScreenContent() = finish()
            override fun onAdFailedToShowFullScreenContent(error: AdError) = finish()
            private fun finish() {
                showing = false
                onShowingChanged(false)
                capper.recordFullScreenClosed()
                preload()
            }
        }
        ad.show(activity!!)
    }

    // Track the foreground activity without leaking it. Ad activities are ignored.
    override fun onActivityStarted(activity: Activity) = track(activity)
    override fun onActivityResumed(activity: Activity) = track(activity)

    private fun track(activity: Activity) {
        if (!activity.javaClass.name.startsWith(ADS_SDK_PACKAGE)) currentActivity = WeakReference(activity)
    }

    override fun onActivityDestroyed(activity: Activity) {
        if (currentActivity?.get() === activity) currentActivity = null
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

    private fun now() = SystemClock.elapsedRealtime()

    private companion object {
        const val TAG = "AppOpenAd"
        const val ADS_SDK_PACKAGE = "com.google.android.gms.ads"
    }
}
