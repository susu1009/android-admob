package com.banv.admob

/** The five ad formats this library serves. */
public enum class AdFormat { INTERSTITIAL, REWARDED, NATIVE, BANNER, APP_OPEN }

/**
 * One place in the app where an ad is shown. Give every place its own ad unit so AdMob reports
 * revenue per place; [key] is the stable name used for on/off toggles, Remote Config keys and
 * [AdEvent]s.
 *
 * Declare them once, e.g. `val InterHome = AdPlacement.interstitial("inter_home", "ca-app-pub-…/…")`.
 */
public data class AdPlacement(
    val key: String,
    val format: AdFormat,
    val adUnitId: String,
    val enabled: Boolean = true,
) {
    public companion object {
        public fun interstitial(key: String, adUnitId: String, enabled: Boolean = true): AdPlacement =
            AdPlacement(key, AdFormat.INTERSTITIAL, adUnitId, enabled)

        public fun rewarded(key: String, adUnitId: String, enabled: Boolean = true): AdPlacement =
            AdPlacement(key, AdFormat.REWARDED, adUnitId, enabled)

        public fun native(key: String, adUnitId: String, enabled: Boolean = true): AdPlacement =
            AdPlacement(key, AdFormat.NATIVE, adUnitId, enabled)

        public fun banner(key: String, adUnitId: String, enabled: Boolean = true): AdPlacement =
            AdPlacement(key, AdFormat.BANNER, adUnitId, enabled)

        public fun appOpen(key: String, adUnitId: String, enabled: Boolean = true): AdPlacement =
            AdPlacement(key, AdFormat.APP_OPEN, adUnitId, enabled)
    }
}

/** Runtime override of one placement, typically from Remote Config. `null` keeps the declared value. */
public data class PlacementOverride(val enabled: Boolean? = null, val adUnitId: String? = null)

/**
 * Everything that decides whether, and with which ad unit, an ad is requested. Immutable: change
 * it at runtime with [AdKit.updateConfig].
 *
 * @param enabled master switch for every format.
 * @param disabledFormats formats switched off as a whole (e.g. all natives).
 * @param premium the user bought "remove ads": every format except [AdFormat.REWARDED] is off,
 *   because rewarded ads are something the user asks for.
 * @param useTestAds replace every ad unit id with Google's test unit of the same format
 *   ([TestAdUnits]). Turn on for debug builds.
 * @param overrides per placement key, applied over the declared [AdPlacement].
 */
public data class AdConfig(
    val enabled: Boolean = true,
    val disabledFormats: Set<AdFormat> = emptySet(),
    val premium: Boolean = false,
    val useTestAds: Boolean = false,
    val overrides: Map<String, PlacementOverride> = emptyMap(),
    val testDeviceIds: List<String> = emptyList(),
    /** Ask UMP for consent before any request ([AdKit.gatherConsent]). Off = start the SDK at once. */
    val requireConsent: Boolean = true,
    val fullscreen: FullscreenOptions = FullscreenOptions(),
    val appOpen: AppOpenOptions = AppOpenOptions(),
) {
    /** The ad unit to request for [placement], or `null` when that placement must not show. */
    public fun resolve(placement: AdPlacement): String? {
        if (!enabled || placement.format in disabledFormats) return null
        if (premium && placement.format != AdFormat.REWARDED) return null
        val override = overrides[placement.key]
        if (!(override?.enabled ?: placement.enabled)) return null
        if (useTestAds) return TestAdUnits.forFormat(placement.format)
        return (override?.adUnitId?.takeIf { it.isNotBlank() } ?: placement.adUnitId).takeIf { it.isNotBlank() }
    }

    public fun isEnabled(placement: AdPlacement): Boolean = resolve(placement) != null

    /**
     * Applies remote values read through [lookup] (e.g. `{ remoteConfig.getString(it).ifEmpty { null } }`):
     * - `ads_enabled` — master switch
     * - `ads_<format>_enabled` — per format, format in lower case (`ads_native_enabled`, `ads_app_open_enabled`)
     * - `<placementKey>_enabled` / `<placementKey>_id` — per placement
     *
     * A key with no value leaves the current setting alone. Booleans accept `true/false/1/0`.
     */
    public fun withRemoteValues(placements: Collection<AdPlacement>, lookup: (String) -> String?): AdConfig {
        val master = lookup(KEY_MASTER).toBool() ?: enabled
        val formats = disabledFormats.toMutableSet()
        AdFormat.entries.forEach { format ->
            when (lookup("ads_${format.name.lowercase()}_enabled").toBool()) {
                true -> formats -= format
                false -> formats += format
                null -> Unit
            }
        }
        val newOverrides = overrides.toMutableMap()
        placements.forEach { p ->
            val on = lookup("${p.key}_enabled").toBool()
            val id = lookup("${p.key}_id")?.trim()?.takeIf { it.isNotEmpty() }
            if (on != null || id != null) {
                val old = newOverrides[p.key] ?: PlacementOverride()
                newOverrides[p.key] = PlacementOverride(on ?: old.enabled, id ?: old.adUnitId)
            }
        }
        return copy(enabled = master, disabledFormats = formats, overrides = newOverrides)
    }

    public companion object {
        public const val KEY_MASTER: String = "ads_enabled"

        private fun String?.toBool(): Boolean? = when (this?.trim()?.lowercase()) {
            "true", "1", "yes", "on" -> true
            "false", "0", "no", "off" -> false
            else -> null
        }
    }
}

/** Interstitial / rewarded behaviour. */
public data class FullscreenOptions(
    /** Give up showing when the ad is not loaded within this time (loading dialog on screen). */
    val loadTimeoutMs: Long = 8_000,
    /** When the ad is already loaded, keep the loading dialog up at least this long so it doesn't flash. */
    val minLoadingDialogMs: Long = 500,
    /** Minimum gap between two interstitials (any placement). 0 = no cap. */
    val interstitialIntervalMs: Long = 0,
    /** Load the next ad for a placement as soon as its previous one is dismissed. */
    val reloadAfterShow: Boolean = true,
)

public data class AppOpenOptions(
    /** Placement used when the app comes back from background. `null` = no app-open ads on resume. */
    val placement: AdPlacement? = null,
    /** Don't show on resume within this time after any interstitial/rewarded was dismissed
     *  (a click on that ad leaves the app and returns). */
    val graceAfterFullscreenMs: Long = 3_000,
    /** Minimum gap between two app-open ads. */
    val intervalMs: Long = 0,
    /** Process start counts as a resume too. Usually off: the splash screen decides that one. */
    val showOnColdStart: Boolean = false,
)
