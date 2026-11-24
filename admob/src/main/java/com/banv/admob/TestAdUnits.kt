package com.banv.admob

/**
 * Google's public demo ad units (https://developers.google.com/admob/android/test-ads).
 * They always fill and never count as invalid traffic — use them, or [AdConfig.useTestAds], in debug.
 */
public object TestAdUnits {
    public const val APP_ID: String = "ca-app-pub-3940256099942544~3347511713"
    public const val APP_OPEN: String = "ca-app-pub-3940256099942544/9257395921"
    public const val BANNER_ADAPTIVE: String = "ca-app-pub-3940256099942544/9214589741"
    public const val BANNER_FIXED: String = "ca-app-pub-3940256099942544/6300978111"
    public const val BANNER_COLLAPSIBLE: String = "ca-app-pub-3940256099942544/2014213617"
    public const val INTERSTITIAL: String = "ca-app-pub-3940256099942544/1033173712"
    public const val REWARDED: String = "ca-app-pub-3940256099942544/5224354917"
    public const val REWARDED_INTERSTITIAL: String = "ca-app-pub-3940256099942544/5354046379"
    public const val NATIVE: String = "ca-app-pub-3940256099942544/2247696110"
    public const val NATIVE_VIDEO: String = "ca-app-pub-3940256099942544/1044960115"

    public fun forFormat(format: AdFormat): String = when (format) {
        AdFormat.INTERSTITIAL -> INTERSTITIAL
        AdFormat.REWARDED -> REWARDED
        AdFormat.NATIVE -> NATIVE
        AdFormat.BANNER -> BANNER_ADAPTIVE
        AdFormat.APP_OPEN -> APP_OPEN
    }
}
