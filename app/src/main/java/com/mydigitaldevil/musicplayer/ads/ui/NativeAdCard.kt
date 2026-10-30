package com.mydigitaldevil.musicplayer.ads.ui

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.google.android.gms.ads.nativead.MediaView
import com.google.android.gms.ads.nativead.NativeAd
import com.google.android.gms.ads.nativead.NativeAdView
import com.mydigitaldevil.musicplayer.ads.AdManager

private data class NativeColors(val surface: Int, val onSurface: Int, val muted: Int, val primary: Int, val onPrimary: Int)

/**
 * Native ad for playlist feeds and the stats screen.
 *
 * Policy points handled here: a visible "Ad" badge at the top-left, AdChoices top-right (set in
 * [AdManager.loadNativeAd]), all assets registered with the NativeAdView so only real ad assets
 * are clickable, and a card style that is distinguishable from song rows. Place it as its own
 * list item (e.g. every 10th row), never inside a clickable song row.
 */
@Composable
fun NativeAdCard(adManager: AdManager, modifier: Modifier = Modifier) {
    var nativeAd by remember { mutableStateOf<NativeAd?>(null) }
    LaunchedEffect(Unit) { nativeAd = adManager.loadNativeAd() }
    DisposableEffect(Unit) { onDispose { nativeAd?.destroy() } }

    val ad = nativeAd ?: return // collapse the slot entirely if no fill; no empty placeholder
    val scheme = MaterialTheme.colorScheme
    val colors = NativeColors(
        surface = scheme.surfaceContainerHigh.toArgb(),
        onSurface = scheme.onSurface.toArgb(),
        muted = scheme.onSurfaceVariant.toArgb(),
        primary = scheme.primary.toArgb(),
        onPrimary = scheme.onPrimary.toArgb(),
    )

    Card(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        AndroidView(
            modifier = Modifier.fillMaxWidth(),
            factory = { ctx -> buildNativeAdView(ctx, colors) },
            update = { view -> bind(view, ad) },
        )
    }
}

private class Holder(
    val headline: TextView,
    val body: TextView,
    val advertiser: TextView,
    val icon: ImageView,
    val media: MediaView,
    val cta: Button,
)

private fun buildNativeAdView(ctx: Context, c: NativeColors): NativeAdView {
    fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), ctx.resources.displayMetrics).toInt()

    val badge = TextView(ctx).apply {
        text = "Ad"
        setTextColor(c.onPrimary)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        setPadding(dp(6), dp(1), dp(6), dp(1))
        background = GradientDrawable().apply { setColor(c.primary); cornerRadius = dp(4).toFloat() }
    }
    val icon = ImageView(ctx).apply { layoutParams = LinearLayout.LayoutParams(dp(40), dp(40)) }
    val headline = TextView(ctx).apply {
        setTextColor(c.onSurface); setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f); maxLines = 2
    }
    val advertiser = TextView(ctx).apply { setTextColor(c.muted); setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f) }
    val titles = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(12), 0, 0, 0)
        addView(headline); addView(advertiser)
    }
    val header = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(badge)
        addView(View(ctx), LinearLayout.LayoutParams(dp(8), 0))
        addView(icon); addView(titles)
    }
    // MediaView must be >= 120x120 dp for video creatives.
    val media = MediaView(ctx).apply { layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, dp(180)) }
    val body = TextView(ctx).apply { setTextColor(c.muted); setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f); maxLines = 3 }
    val cta = Button(ctx).apply {
        setTextColor(c.onPrimary)
        background = GradientDrawable().apply { setColor(c.primary); cornerRadius = dp(20).toFloat() }
        layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { gravity = Gravity.END }
    }

    val column = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(12), dp(16), dp(16))
        setBackgroundColor(c.surface)
        addView(header)
        addView(media, LinearLayout.LayoutParams(MATCH_PARENT, dp(180)).apply { topMargin = dp(12) })
        addView(body, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(8) })
        addView(cta, (cta.layoutParams as LinearLayout.LayoutParams).apply { topMargin = dp(12) })
    }

    return NativeAdView(ctx).apply {
        addView(column, MATCH_PARENT, WRAP_CONTENT)
        headlineView = headline
        bodyView = body
        advertiserView = advertiser
        iconView = icon
        mediaView = media
        callToActionView = cta
        tag = Holder(headline, body, advertiser, icon, media, cta)
    }
}

private fun bind(view: NativeAdView, ad: NativeAd) {
    val h = view.tag as Holder
    h.headline.text = ad.headline
    h.body.setTextOrHide(ad.body)
    h.advertiser.setTextOrHide(ad.advertiser)
    h.cta.setTextOrHide(ad.callToAction)
    ad.icon?.drawable?.let { h.icon.setImageDrawable(it); h.icon.visibility = View.VISIBLE }
        ?: run { h.icon.visibility = View.GONE }
    ad.mediaContent?.let { h.media.mediaContent = it }
    view.setNativeAd(ad) // must be last: registers the views above for click/impression tracking
}

private fun TextView.setTextOrHide(value: String?) {
    text = value
    visibility = if (value.isNullOrBlank()) View.GONE else View.VISIBLE
}
