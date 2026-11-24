package com.banv.admob

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AdConfigTest {
    private val inter = AdPlacement.interstitial("inter_home", "real/inter")
    private val reward = AdPlacement.rewarded("reward_coins", "real/reward")
    private val native = AdPlacement.native("native_home", "real/native")
    private val all = listOf(inter, reward, native)

    @Test fun `declared unit is used by default`() {
        assertEquals("real/inter", AdConfig().resolve(inter))
    }

    @Test fun `master switch, format switch and placement flag turn ads off`() {
        assertNull(AdConfig(enabled = false).resolve(inter))
        assertNull(AdConfig(disabledFormats = setOf(AdFormat.NATIVE)).resolve(native))
        assertEquals("real/inter", AdConfig(disabledFormats = setOf(AdFormat.NATIVE)).resolve(inter))
        assertNull(AdConfig().resolve(inter.copy(enabled = false)))
        assertNull(AdConfig().resolve(inter.copy(adUnitId = " ")))
    }

    @Test fun `premium keeps only rewarded`() {
        val c = AdConfig(premium = true)
        assertNull(c.resolve(inter))
        assertNull(c.resolve(native))
        assertEquals("real/reward", c.resolve(reward))
    }

    @Test fun `test ads replace unit per format`() {
        val c = AdConfig(useTestAds = true)
        assertEquals(TestAdUnits.INTERSTITIAL, c.resolve(inter))
        assertEquals(TestAdUnits.NATIVE, c.resolve(native))
        assertNull(c.copy(enabled = false).resolve(inter))
    }

    @Test fun `override wins over declaration`() {
        val c = AdConfig(overrides = mapOf("inter_home" to PlacementOverride(enabled = false)))
        assertFalse(c.isEnabled(inter))
        val c2 = AdConfig(overrides = mapOf("inter_home" to PlacementOverride(adUnitId = "remote/inter")))
        assertEquals("remote/inter", c2.resolve(inter))
        val c3 = AdConfig(overrides = mapOf("inter_home" to PlacementOverride(enabled = true)))
        assertTrue(c3.isEnabled(inter.copy(enabled = false)))
    }

    @Test fun `remote values map to switches and ids`() {
        val remote = mapOf(
            "ads_native_enabled" to "false",
            "inter_home_id" to "remote/inter",
            "reward_coins_enabled" to "0",
        )
        val c = AdConfig().withRemoteValues(all) { remote[it] }
        assertNull(c.resolve(native))
        assertEquals("remote/inter", c.resolve(inter))
        assertNull(c.resolve(reward))
        assertTrue(c.enabled)
    }

    @Test fun `missing or garbage remote values keep current config`() {
        val start = AdConfig(disabledFormats = setOf(AdFormat.BANNER))
        val c = start.withRemoteValues(all) { if (it == "ads_enabled") "maybe" else null }
        assertEquals(start, c)
    }

    @Test fun `remote can re-enable a format and flip master`() {
        val c = AdConfig(disabledFormats = setOf(AdFormat.APP_OPEN))
            .withRemoteValues(all) { mapOf("ads_app_open_enabled" to "true", "ads_enabled" to "off")[it] }
        assertTrue(AdFormat.APP_OPEN !in c.disabledFormats)
        assertFalse(c.enabled)
    }

    @Test fun `remote id keeps an earlier remote enabled flag`() {
        val c = AdConfig()
            .withRemoteValues(all) { mapOf("inter_home_enabled" to "false")[it] }
            .withRemoteValues(all) { mapOf("inter_home_id" to "x/y")[it] }
        assertEquals(PlacementOverride(enabled = false, adUnitId = "x/y"), c.overrides["inter_home"])
    }
}

class PolicyTest {
    @Test fun `frequency cap`() {
        assertFalse(FrequencyCap.blocked(now = 10_000, lastShownAt = 0, intervalMs = 0))
        assertTrue(FrequencyCap.blocked(now = 10_000, lastShownAt = 5_000, intervalMs = 30_000))
        assertFalse(FrequencyCap.blocked(now = 40_000, lastShownAt = 5_000, intervalMs = 30_000))
        assertFalse(FrequencyCap.blocked(now = 0, lastShownAt = Long.MIN_VALUE / 2, intervalMs = 30_000))
    }

    private fun reason(
        now: Long = 100_000,
        cold: Boolean = false,
        skipNext: Boolean = false,
        excluded: Boolean = false,
        showing: Boolean = false,
        lastDismissed: Long = Long.MIN_VALUE / 2,
        lastAppOpen: Long = Long.MIN_VALUE / 2,
        options: AppOpenOptions = AppOpenOptions(),
    ) = AppOpenPolicy.skipReason(now, cold, skipNext, excluded, showing, lastDismissed, lastAppOpen, options)

    @Test fun `app open shows on a plain resume`() = assertNull(reason())

    @Test fun `app open skips cold start unless asked`() {
        assertEquals(SkipReason.DISABLED, reason(cold = true))
        assertNull(reason(cold = true, options = AppOpenOptions(showOnColdStart = true)))
    }

    @Test fun `app open skips when told, excluded, or another ad is up`() {
        assertEquals(SkipReason.DISABLED, reason(skipNext = true))
        assertEquals(SkipReason.DISABLED, reason(excluded = true))
        assertEquals(SkipReason.ALREADY_SHOWING, reason(showing = true))
    }

    @Test fun `app open waits out the grace after a fullscreen ad and its own interval`() {
        assertEquals(SkipReason.FREQUENCY_CAP, reason(lastDismissed = 98_000))
        assertNull(reason(lastDismissed = 90_000))
        assertEquals(SkipReason.FREQUENCY_CAP, reason(lastAppOpen = 80_000, options = AppOpenOptions(intervalMs = 60_000)))
    }
}
