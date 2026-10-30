package com.mydigitaldevil.musicplayer.ads

import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test

class FrequencyCapperTest {

    private val policy = AdPolicy()
    private var monotonic = 1_000_000L
    private var wall = 1_700_000_000_000L
    private val store = object : CapStore {
        override var lastAppOpenShownAtWallMs: Long = 0L
    }
    private lateinit var capper: FrequencyCapper

    @Before
    fun setUp() {
        capper = FrequencyCapper(policy, { monotonic }, { wall }, store)
    }

    private fun advance(ms: Long) {
        monotonic += ms
        wall += ms
    }

    @Test
    fun `interstitial is blocked during session grace period`() {
        assertThat(capper.interstitialDecision()).isInstanceOf(CapDecision.Denied::class.java)
        advance(policy.interstitialSessionGraceMs)
        assertThat(capper.interstitialDecision()).isEqualTo(CapDecision.Allowed)
    }

    @Test
    fun `interstitial cooldown is 90 seconds`() {
        advance(policy.interstitialSessionGraceMs)
        capper.recordInterstitialShown()
        advance(89_999)
        assertThat(capper.interstitialDecision()).isEqualTo(CapDecision.Denied("interstitial cooldown"))
        advance(1)
        assertThat(capper.interstitialDecision()).isEqualTo(CapDecision.Allowed)
    }

    @Test
    fun `any full-screen ad closing blocks the next interstitial`() {
        advance(policy.interstitialSessionGraceMs)
        capper.recordFullScreenClosed() // e.g. a rewarded ad just closed
        assertThat(capper.interstitialDecision()).isEqualTo(CapDecision.Denied("full-screen gap"))
    }

    @Test
    fun `session cap is enforced`() {
        advance(policy.interstitialSessionGraceMs)
        repeat(policy.maxInterstitialsPerSession) {
            capper.recordInterstitialShown()
            advance(policy.interstitialCooldownMs)
        }
        assertThat(capper.interstitialDecision()).isEqualTo(CapDecision.Denied("session cap reached"))
    }

    @Test
    fun `app open requires a real trip to the background`() {
        assertThat(capper.appOpenDecision(null)).isEqualTo(CapDecision.Denied("short app switch"))
        assertThat(capper.appOpenDecision(5_000)).isEqualTo(CapDecision.Denied("short app switch"))
        assertThat(capper.appOpenDecision(policy.appOpenMinBackgroundMs)).isEqualTo(CapDecision.Allowed)
    }

    @Test
    fun `app open interval persists through the store`() {
        capper.recordAppOpenShown()
        advance(policy.appOpenMinIntervalMs - 1)
        // A fresh capper simulates a process restart; the interval must still apply.
        val restarted = FrequencyCapper(policy, { monotonic }, { wall }, store)
        assertThat(restarted.appOpenDecision(60_000)).isEqualTo(CapDecision.Denied("app open interval"))
        advance(1)
        assertThat(restarted.appOpenDecision(60_000)).isEqualTo(CapDecision.Allowed)
    }

    @Test
    fun `clock moved backwards counts as recently shown`() {
        capper.recordAppOpenShown()
        wall -= 10 * AdPolicy.HOUR
        assertThat(capper.appOpenDecision(60_000)).isEqualTo(CapDecision.Denied("app open interval"))
    }
}
