package com.banv.admob.sample

import android.app.Application
import android.util.Log
import com.banv.admob.AdConfig
import com.banv.admob.AdKit
import com.banv.admob.AdPlacement
import com.banv.admob.AppOpenOptions
import com.banv.admob.FullscreenOptions

/** Every place the app shows an ad, each with its own ad unit (here: placeholders). */
object Ads {
    val InterHome = AdPlacement.interstitial("inter_home", "ca-app-pub-XXXX/inter_home")
    val InterResult = AdPlacement.interstitial("inter_result", "ca-app-pub-XXXX/inter_result")
    val RewardCoins = AdPlacement.rewarded("reward_coins", "ca-app-pub-XXXX/reward_coins")
    val NativeHome = AdPlacement.native("native_home", "ca-app-pub-XXXX/native_home")
    val NativeList = AdPlacement.native("native_list", "ca-app-pub-XXXX/native_list")
    val BannerHome = AdPlacement.banner("banner_home", "ca-app-pub-XXXX/banner_home")
    val BannerCollapsible = AdPlacement.banner("banner_chat", "ca-app-pub-XXXX/banner_chat")
    val AppOpenResume = AdPlacement.appOpen("app_open_resume", "ca-app-pub-XXXX/app_open")

    val all = listOf(InterHome, InterResult, RewardCoins, NativeHome, NativeList, BannerHome, BannerCollapsible, AppOpenResume)
}

class SampleApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AdKit.initialize(
            application = this,
            config = AdConfig(
                // Debug: every placement uses Google's test unit of its format.
                useTestAds = BuildConfig.DEBUG,
                fullscreen = FullscreenOptions(interstitialIntervalMs = 0),
                appOpen = AppOpenOptions(placement = Ads.AppOpenResume),
            ),
            placements = Ads.all,
            listener = { event -> Log.d("AdKit", "${event.placementKey} ${event.type}") },
        )
        // A real app excludes its splash/paywall: AdKit.appOpen.exclude(SplashActivity::class.java)
    }
}
