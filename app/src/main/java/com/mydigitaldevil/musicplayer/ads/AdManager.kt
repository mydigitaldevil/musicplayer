package com.mydigitaldevil.musicplayer.ads

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.annotation.MainThread
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdLoader
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.RequestConfiguration
import com.google.android.gms.ads.VideoOptions
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback
import com.google.android.gms.ads.nativead.NativeAd
import com.google.android.gms.ads.nativead.NativeAdOptions
import com.google.android.gms.ads.rewarded.RewardedAd
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback
import com.mydigitaldevil.musicplayer.playback.PlaybackStateRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.math.min

sealed interface RewardOutcome {
    data object Granted : RewardOutcome
    /** Closed before the reward was earned: nothing is granted, nothing is retried automatically. */
    data object Cancelled : RewardOutcome
    data object Unavailable : RewardOutcome
}

/**
 * Single entry point for all monetization. Screens never touch the Mobile Ads SDK directly,
 * so every policy rule (consent, pacing, placement, suppression) is enforced here.
 *
 * Threading: all public methods are main-thread only.
 */
@Singleton
class AdManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val consent: ConsentManager,
    private val capper: FrequencyCapper,
    private val unlocks: RewardUnlockStore,
    private val policy: AdPolicy,
    playback: PlaybackStateRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _adsReady = MutableStateFlow(false)
    /** False until consent allows ads and the SDK is initialised. Ad UI renders nothing until then. */
    val adsReady: StateFlow<Boolean> = _adsReady.asStateFlow()

    private val _rewardedReady = MutableStateFlow(false)
    /** UI shows a rewarded offer only while this is true, so "Watch ad" never leads to nothing. */
    val rewardedReady: StateFlow<Boolean> = _rewardedReady.asStateFlow()

    private var initStarted = false
    private var fullScreenShowing = false
    private val suppressions = mutableSetOf<String>()

    private var interstitial: Loaded<InterstitialAd>? = null
    private var interstitialLoading = false
    private var rewarded: Loaded<RewardedAd>? = null
    private var rewardedLoading = false

    private val appOpen = AppOpenAdController(
        context = context,
        policy = policy,
        capper = capper,
        playback = playback,
        canShow = { _adsReady.value && !fullScreenShowing && suppressions.isEmpty() },
        onShowingChanged = { fullScreenShowing = it },
        request = ::buildRequest,
        retry = ::retryWithBackoff,
    )

    /** Call from Application.onCreate; App Open ads need process lifecycle from the start. */
    fun attach(application: Application) = appOpen.register(application)

    /** Call from the first activity's onCreate. Safe to call repeatedly. */
    suspend fun initialize(activity: Activity) {
        if (initStarted) return
        initStarted = true
        if (!consent.gather(activity)) {
            initStarted = false // retry on the next launch / after privacy options change
            return
        }
        MobileAds.setRequestConfiguration(
            RequestConfiguration.Builder()
                .setMaxAdContentRating(RequestConfiguration.MAX_AD_CONTENT_RATING_T)
                .build(),
        )
        withContext(Dispatchers.IO) {
            suspendCancellableCoroutine { cont -> MobileAds.initialize(context) { cont.resume(Unit) } }
        }
        _adsReady.value = true
        preloadInterstitial()
        preloadRewarded()
        appOpen.preload()
    }

    /** Onboarding must finish before any App Open ad; it explains the app first. */
    fun setOnboardingComplete(complete: Boolean) {
        appOpen.onboardingComplete = complete
    }

    /**
     * Blocks interstitial / App Open ads while [key] is active (live mic FX, recording, a
     * call-sensitive soundboard session…). Rewarded ads stay available: the user asks for those.
     */
    @MainThread
    fun setFullScreenSuppressed(key: String, suppressed: Boolean) {
        if (suppressed) suppressions += key else suppressions -= key
    }

    /** Call before launching an external activity (share sheet, file picker) we return from. */
    @MainThread
    fun suppressNextAppOpen() = appOpen.suppressNext()

    // ---- Interstitial ------------------------------------------------------------------

    /**
     * Shows an interstitial *if* every rule allows it, then calls [onContinue] exactly once —
     * immediately when no ad is shown, or after the ad closes. Navigation never waits on ad
     * loading and never depends on whether an ad was shown.
     */
    @MainThread
    fun showInterstitialAtNaturalBreak(activity: Activity, breakPoint: NaturalBreak, onContinue: () -> Unit) {
        var continued = false
        val continueOnce = { if (!continued) { continued = true; onContinue() } }

        val ad = interstitial?.takeIf { it.isFresh(now(), policy.fullScreenExpiryMs) }?.ad
        val blockedBy = blockReason(activity) ?: when {
            ad == null -> "no fresh ad loaded"
            else -> (capper.interstitialDecision() as? CapDecision.Denied)?.reason
        }
        if (blockedBy != null) {
            Log.d(TAG, "Interstitial at $breakPoint skipped: $blockedBy")
            if (ad == null) { interstitial = null; preloadInterstitial() }
            continueOnce()
            return
        }

        interstitial = null
        fullScreenShowing = true
        ad!!.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdShowedFullScreenContent() = capper.recordInterstitialShown()
            override fun onAdClicked() = appOpen.suppressNext()
            override fun onAdDismissedFullScreenContent() = finish()
            override fun onAdFailedToShowFullScreenContent(error: AdError) = finish()
            private fun finish() {
                fullScreenShowing = false
                capper.recordFullScreenClosed()
                preloadInterstitial()
                continueOnce()
            }
        }
        ad.show(activity)
    }

    private fun preloadInterstitial(attempt: Int = 0) {
        if (!_adsReady.value || interstitialLoading || interstitial != null) return
        interstitialLoading = true
        InterstitialAd.load(context, AdUnits.INTERSTITIAL, buildRequest(), object : InterstitialAdLoadCallback() {
            override fun onAdLoaded(ad: InterstitialAd) {
                interstitialLoading = false
                interstitial = Loaded(ad, now())
            }

            override fun onAdFailedToLoad(error: LoadAdError) {
                interstitialLoading = false
                retryWithBackoff(attempt) { preloadInterstitial(attempt + 1) }
            }
        })
    }

    // ---- Rewarded ----------------------------------------------------------------------

    /**
     * Shows a rewarded ad for [unlock]. Must only be called after the user explicitly accepted
     * an offer that states what they get (see `RewardedOfferDialog`). The unlock is granted
     * solely from `onUserEarnedReward`.
     */
    @MainThread
    fun showRewarded(activity: Activity, unlock: RewardedUnlock, onResult: (RewardOutcome) -> Unit) {
        val ad = rewarded?.takeIf { it.isFresh(now(), policy.fullScreenExpiryMs) }?.ad
        if (ad == null || fullScreenShowing || !activity.isUsable()) {
            rewarded = null
            _rewardedReady.value = false
            preloadRewarded()
            onResult(RewardOutcome.Unavailable)
            return
        }

        rewarded = null
        _rewardedReady.value = false
        fullScreenShowing = true
        var earned = false
        ad.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdClicked() = appOpen.suppressNext()
            override fun onAdDismissedFullScreenContent() = finish()
            override fun onAdFailedToShowFullScreenContent(error: AdError) = finish()
            private fun finish() {
                fullScreenShowing = false
                capper.recordFullScreenClosed()
                preloadRewarded()
                onResult(if (earned) RewardOutcome.Granted else RewardOutcome.Cancelled)
            }
        }
        ad.show(activity) {
            earned = true
            scope.launch { unlocks.grant(unlock) }
        }
    }

    private fun preloadRewarded(attempt: Int = 0) {
        if (!_adsReady.value || rewardedLoading || rewarded != null) return
        rewardedLoading = true
        RewardedAd.load(context, AdUnits.REWARDED, buildRequest(), object : RewardedAdLoadCallback() {
            override fun onAdLoaded(ad: RewardedAd) {
                rewardedLoading = false
                rewarded = Loaded(ad, now())
                _rewardedReady.value = true
            }

            override fun onAdFailedToLoad(error: LoadAdError) {
                rewardedLoading = false
                retryWithBackoff(attempt) { preloadRewarded(attempt + 1) }
            }
        })
    }

    // ---- Native ------------------------------------------------------------------------

    /**
     * Loads one native ad for a feed slot. The caller owns it and must call `destroy()` when
     * the slot leaves composition (NativeAdCard does this).
     */
    suspend fun loadNativeAd(): NativeAd? {
        if (!_adsReady.value) return null
        return suspendCancellableCoroutine { cont ->
            val loader = AdLoader.Builder(context, AdUnits.NATIVE)
                .forNativeAd { ad -> if (cont.isActive) cont.resume(ad) else ad.destroy() }
                .withAdListener(object : com.google.android.gms.ads.AdListener() {
                    override fun onAdFailedToLoad(error: LoadAdError) {
                        if (cont.isActive) cont.resume(null)
                    }

                    override fun onAdClicked() = appOpen.suppressNext()
                })
                .withNativeAdOptions(
                    NativeAdOptions.Builder()
                        .setAdChoicesPlacement(NativeAdOptions.ADCHOICES_TOP_RIGHT)
                        .setVideoOptions(VideoOptions.Builder().setStartMuted(true).build())
                        .build(),
                )
                .build()
            loader.loadAd(buildRequest())
        }
    }

    // ---- Banner support ----------------------------------------------------------------

    /** Banners call this from `AdListener.onAdClicked` so returning from the click-through is quiet. */
    @MainThread
    fun onBannerClicked() = appOpen.suppressNext()

    // ---- Shared ------------------------------------------------------------------------

    private fun blockReason(activity: Activity): String? = when {
        !_adsReady.value -> "ads not ready"
        fullScreenShowing -> "another full-screen ad is showing"
        suppressions.isNotEmpty() -> "suppressed by $suppressions"
        !activity.isUsable() -> "activity not resumed"
        activity.isInPictureInPictureMode -> "activity in PiP"
        else -> null
    }

    private fun Activity.isUsable(): Boolean =
        !isFinishing && !isDestroyed &&
            (this as? LifecycleOwner)?.lifecycle?.currentState?.isAtLeast(Lifecycle.State.RESUMED) != false

    private fun buildRequest(): AdRequest = AdRequest.Builder().build()

    private fun retryWithBackoff(attempt: Int, block: () -> Unit) {
        if (attempt >= policy.maxLoadRetries) return
        scope.launch {
            delay(min(policy.loadRetryBaseMs shl attempt, policy.loadRetryMaxMs))
            block()
        }
    }

    private fun now() = SystemClock.elapsedRealtime()

    private companion object {
        const val TAG = "AdManager"
    }
}
