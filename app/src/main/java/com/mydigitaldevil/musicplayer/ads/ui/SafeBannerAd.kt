package com.mydigitaldevil.musicplayer.ads.ui

import android.os.Bundle
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.ads.mediation.admob.AdMobAdapter
import com.google.android.gms.ads.AdListener
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.mydigitaldevil.musicplayer.ads.AdManager
import com.mydigitaldevil.musicplayer.ads.AdUnits
import java.util.UUID

enum class BannerPlacement(val collapsible: Boolean) {
    /** Library / browse lists: a collapsible banner anchored at the bottom of the content. */
    BROWSE(collapsible = true),
    /**
     * Now Playing / Mic FX: never collapsible. An expanded collapsible banner would sit over
     * seek bars and transport controls, which is the classic accidental-click violation.
     */
    PLAYER(collapsible = false),
}

/**
 * Anchored adaptive banner with a mandatory clear zone above it.
 *
 * Placement rules enforced here:
 *  - a 16 dp clear zone plus a divider separates it from interactive content above it;
 *  - nothing is rendered until consent + SDK init are done, or while [hidden] is true
 *    (callers pass `isInPictureInPictureMode`; an ad must never show inside the PiP window);
 *  - the AdView follows the host lifecycle: paused when the screen stops (so it never refreshes
 *    while the app is in the background playing music), destroyed when it leaves composition.
 *
 * Put it in a Scaffold `bottomBar` / outside scrolling content, never on top of the player
 * controls or overlapping system gesture areas.
 */
@Composable
fun SafeBannerAd(
    adManager: AdManager,
    placement: BannerPlacement,
    modifier: Modifier = Modifier,
    hidden: Boolean = false,
) {
    val adsReady by adManager.adsReady.collectAsStateWithLifecycle()
    if (!adsReady || hidden) return

    Column(modifier.fillMaxWidth()) {
        HorizontalDivider(Modifier.padding(top = CLEAR_ZONE))
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val context = LocalContext.current
            val widthDp = maxWidth.value.toInt()
            val adSize = remember(widthDp) {
                AdSize.getCurrentOrientationAnchoredAdaptiveBannerAdSize(context, widthDp)
            }
            val heightDp = with(LocalDensity.current) { adSize.getHeightInPixels(context).toDp() }
            val lifecycle = LocalLifecycleOwner.current.lifecycle

            val adView = remember(adSize) {
                AdView(context).apply {
                    adUnitId = AdUnits.BANNER
                    setAdSize(adSize)
                    adListener = object : AdListener() {
                        override fun onAdClicked() = adManager.onBannerClicked()
                    }
                    loadAd(bannerRequest(placement))
                }
            }

            DisposableEffect(adView, lifecycle) {
                val observer = LifecycleEventObserver { _, event ->
                    when (event) {
                        Lifecycle.Event.ON_RESUME -> adView.resume()
                        Lifecycle.Event.ON_PAUSE -> adView.pause()
                        else -> Unit
                    }
                }
                lifecycle.addObserver(observer)
                onDispose {
                    lifecycle.removeObserver(observer)
                    adView.destroy()
                }
            }

            // Fixed height reserved up front: no layout shift pushing content under a finger.
            // key(): a new AdView (after a width change) needs a new view node; the factory runs once per node.
            key(adView) {
                AndroidView(factory = { adView }, modifier = Modifier.fillMaxWidth().height(heightDp))
            }
        }
    }
}

/**
 * The collapsible extra is sent only on the first request of a screen visit. Auto-refreshes
 * (configured in the AdMob console, >= 60 s) reuse the AdView and don't re-expand it.
 */
private fun bannerRequest(placement: BannerPlacement): AdRequest {
    val builder = AdRequest.Builder()
    if (placement.collapsible) {
        builder.addNetworkExtrasBundle(
            AdMobAdapter::class.java,
            Bundle().apply {
                putString("collapsible", "bottom")
                putString("collapsible_request_id", UUID.randomUUID().toString())
            },
        )
    }
    return builder.build()
}

private val CLEAR_ZONE = 16.dp
