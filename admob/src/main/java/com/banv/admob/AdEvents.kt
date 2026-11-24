package com.banv.admob

/** What happened to an ad, for analytics. Every event names its placement. */
public data class AdEvent(
    val placementKey: String,
    val format: AdFormat,
    val adUnitId: String?,
    val type: Type,
) {
    public sealed interface Type {
        public data object Requested : Type
        public data object Loaded : Type
        public data class LoadFailed(val code: Int, val message: String) : Type
        public data object Shown : Type
        public data class ShowFailed(val code: Int, val message: String) : Type
        public data object Impression : Type
        public data object Clicked : Type
        public data object Dismissed : Type
        public data class RewardEarned(val reward: AdReward) : Type
        /** Impression-level revenue (ILRD). [valueMicros] / 1e6 = value in [currencyCode]. */
        public data class Paid(
            val valueMicros: Long,
            val currencyCode: String,
            val precision: Int,
            val adSourceName: String?,
        ) : Type
        /** A show/load was not attempted; see [reason]. */
        public data class Skipped(val reason: SkipReason) : Type
    }
}

public fun interface AdEventListener {
    public fun onAdEvent(event: AdEvent)
}

public data class AdReward(val type: String, val amount: Int)

public enum class SkipReason {
    /** Placement, its format, the master switch, or premium turned it off. */
    DISABLED,
    /** UMP consent not obtained yet, or the user refused and ads can't be requested. */
    NO_CONSENT,
    /** Another full-screen ad is on screen. */
    ALREADY_SHOWING,
    /** Frequency cap ([FullscreenOptions.interstitialIntervalMs] / [AppOpenOptions.intervalMs]). */
    FREQUENCY_CAP,
    LOAD_FAILED,
    TIMEOUT,
    SHOW_FAILED,
    /** The activity was finishing or no longer in the foreground when the ad became ready. */
    ACTIVITY_GONE,
    NOT_INITIALIZED,
}

/** Outcome of showing an interstitial, rewarded or app-open ad. Always delivered exactly once. */
public sealed interface AdShowResult {
    /** The ad was shown and closed. [reward] is set for a rewarded ad the user watched through. */
    public data class Shown(val reward: AdReward? = null) : AdShowResult
    public data class NotShown(val reason: SkipReason, val message: String? = null) : AdShowResult

    public val wasShown: Boolean get() = this is Shown
}
