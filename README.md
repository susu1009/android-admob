# android-admob

Google AdMob (interstitial, rewarded, native, banner, app open) + UMP consent behind a small
Kotlin API, shared across apps. One ad unit per **placement** (for per-place revenue tracking),
on/off switches at every level, app-supplied loading dialogs, and Compose + View support.

Google Mobile Ads **24.8.0**, UMP **4.0.0**, minSdk 23, Kotlin 2.0+.

> Status: 0.1.0-SNAPSHOT — builds, config/policy unit-tested, sample runs with Google test ads.

### Why GMA 24.8.0 and not 25.5.0

Recent GMA releases ship Kotlin metadata that older Kotlin compilers refuse
(`Module was compiled with an incompatible version of Kotlin. The binary version of its metadata is 2.3.0, expected version is 2.0.0`):

| play-services-ads | metadata | app needs Kotlin |
|---|---|---|
| ≤ 24.8.0 | 2.1 | ≥ 2.0 |
| 24.9.0 – 25.2.0 | 2.2 | ≥ 2.1 |
| 25.3.0 – 25.5.0 | 2.3 | ≥ 2.2 |

The library stays on 24.8.0 so apps on Kotlin 2.0 (CharacterVerse) can use it. An app on a newer
Kotlin can add `implementation("com.google.android.gms:play-services-ads:25.5.0")` itself —
Gradle takes the higher version (not yet verified against 25.x at runtime).

## Install (JitPack)

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}

// app/build.gradle.kts
dependencies {
    implementation("com.github.susu1009.android-admob:admob-compose:<tag>") // Compose (includes :admob)
    // or View-only apps:
    // implementation("com.github.susu1009.android-admob:admob:<tag>")
}
```

`AndroidManifest.xml` of the app (required by the SDK, it crashes at start without it):

```xml
<meta-data
    android:name="com.google.android.gms.ads.APPLICATION_ID"
    android:value="ca-app-pub-xxxxxxxxxxxxxxxx~yyyyyyyyyy" />
```

Debug builds may use Google's sample app id `TestAdUnits.APP_ID` (`ca-app-pub-3940256099942544~3347511713`).

## Setup

```kotlin
object Ads {
    val InterHome   = AdPlacement.interstitial("inter_home", "ca-app-pub-…/111")
    val InterResult = AdPlacement.interstitial("inter_result", "ca-app-pub-…/222")
    val RewardCoins = AdPlacement.rewarded("reward_coins", "ca-app-pub-…/333")
    val NativeChat  = AdPlacement.native("native_chat", "ca-app-pub-…/444")
    val BannerHome  = AdPlacement.banner("banner_home", "ca-app-pub-…/555")
    val AppOpen     = AdPlacement.appOpen("app_open", "ca-app-pub-…/666")
    val all = listOf(InterHome, InterResult, RewardCoins, NativeChat, BannerHome, AppOpen)
}

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        AdKit.initialize(
            application = this,
            config = AdConfig(
                useTestAds = BuildConfig.DEBUG,          // every placement → Google test unit
                appOpen = AppOpenOptions(placement = Ads.AppOpen),
                fullscreen = FullscreenOptions(interstitialIntervalMs = 30_000),
            ),
            placements = Ads.all,
            listener = { e -> analytics.log("ad_${e.type}", e.placementKey) }, // tracking per placement
        )
        AdKit.appOpen.exclude(SplashActivity::class.java)
    }
}

// SplashActivity
AdKit.gatherConsent(this) { canRequestAds ->
    AdKit.appOpen.show(this) { goToMain() }        // optional splash app-open
}
```

`AdKit.gatherConsent` runs UMP (form only where the law requires it) and starts the SDK only
once ads may be requested. Show a "Privacy settings" item when
`AdKit.consent.isPrivacyOptionsRequired(context)` → `AdKit.consent.showPrivacyOptionsForm(activity)`.
Debug the EEA form with `AdKit.gatherConsent(this, AdConsent.debugEea(this, "<hashed id from logcat>"))`.
Set `AdConfig(requireConsent = false)` to skip UMP entirely.

## On / off and Remote Config

`AdConfig.resolve(placement)` decides, in order: master `enabled` → `disabledFormats` → `premium`
(all off except rewarded) → per-placement `enabled` / override → unit id (test unit when `useTestAds`).

```kotlin
AdKit.setPremium(true)                                   // after "remove ads" purchase
AdKit.updateConfig { it.copy(disabledFormats = setOf(AdFormat.NATIVE)) }

// Firebase Remote Config (or anything key → string)
AdKit.applyRemoteValues { key -> remoteConfig.getString(key).ifEmpty { null } }
```

Remote keys: `ads_enabled`, `ads_<format>_enabled` (`ads_interstitial_enabled`, `ads_rewarded_enabled`,
`ads_native_enabled`, `ads_banner_enabled`, `ads_app_open_enabled`), `<placementKey>_enabled`,
`<placementKey>_id`. Missing keys keep the current value. Compose banners/natives react immediately.

## Interstitial

```kotlin
AdKit.interstitial.preload(context, Ads.InterResult)       // optional, makes show instant
AdKit.interstitial.show(activity, Ads.InterResult) { result -> navigateNext() }
```

`onResult` is called exactly once — shown, off, capped, timed out (`FullscreenOptions.loadTimeoutMs`),
or failed — so put navigation there. `showIfReady(...)` never waits.

### Custom loading dialog

```kotlin
AdLoadingDialog.Default                          // built-in (strings/colors admob_* overridable)
AdLoadingDialog.None                             // no UI
AdLoadingDialog.layout(R.layout.my_loading)      // your XML
AdLoadingDialog.view { activity, _ -> MyView(activity) }
val dialog = rememberComposeLoadingDialog { MyComposeLoading() }   // Compose, inherits your theme
AdLoadingDialog { activity -> /* your own */ AdLoadingDialog.Handle { /* dismiss */ } }
```

The dialog stays at least `minLoadingDialogMs` when the ad is already loaded (no flash) and is
dismissed when the ad appears.

## Rewarded

```kotlin
AdKit.rewarded.show(activity, Ads.RewardCoins, loadingDialog = dialog,
    onReward = { reward -> wallet.add(reward.amount) },      // grant here
) { result -> /* Shown(reward) / Shown(null) = closed early / NotShown(reason) */ }
```

## Native

Built-in templates `NativeTemplate.Small` (one row, no media), `Medium`, `Large` (16:9 media).
Restyle by overriding colors in the app: `admob_native_background`, `admob_native_title`,
`admob_native_body`, `admob_native_cta_background`, `admob_native_cta_text`,
`admob_native_badge_background`, `admob_native_badge_text`, `admob_native_stroke`
(+ `values-night`); strings `admob_badge`, `admob_loading`.

Own layout: root `com.google.android.gms.ads.nativead.NativeAdView` with ids `@id/admob_headline`
(required), `admob_body`, `admob_call_to_action`, `admob_icon`, `admob_media` (MediaView),
`admob_advertiser`, `admob_stars`, `admob_price`, `admob_store` — then
`NativeTemplate.Custom(R.layout.my_native)`. For anything else pass a `NativeAdBinder`.

```kotlin
// Compose
NativeAd(Ads.NativeChat, template = NativeTemplate.Small, placeholder = { Shimmer() })
// View
adNativeView.load(Ads.NativeChat, NativeTemplate.Custom(R.layout.my_native))
// Lists: preload, then bind
AdKit.native.preload(context, Ads.NativeChat, count = 2)
```

## Banner

```kotlin
BannerAd(Ads.BannerHome)                                              // anchored adaptive
BannerAd(Ads.BannerChat, type = BannerType.Collapsible(BannerType.Anchor.BOTTOM)) // collapsible
BannerAd(Ads.BannerList, type = BannerType.Fixed(AdSize.MEDIUM_RECTANGLE))
BannerAd(Ads.BannerHome, extras = bundleOf("key" to "value"))         // any AdMob network extra
```

View: `AdBannerView.load(placement, type, extras)`. Views pause/resume/destroy with the
screen's lifecycle; they collapse (GONE / nothing rendered) when off or unfilled.

## App open

With `AppOpenOptions(placement = …)` set, an app-open ad shows when the app returns from
background — not on cold start (`showOnColdStart`), not over excluded activities, not while
another full-screen ad is up or within `graceAfterFullscreenMs` after one, and respecting
`intervalMs`. Before leaving the app on purpose (purchase, share, camera, sign-in browser) call
`AdKit.appOpen.skipNextResume()`.

## Events

`AdEventListener` receives `AdEvent(placementKey, format, adUnitId, type)` with `Requested`,
`Loaded`, `LoadFailed`, `Shown`, `ShowFailed`, `Impression`, `Clicked`, `Dismissed`,
`RewardEarned`, `Paid` (impression-level revenue: micros, currency, precision, ad source),
`Skipped(reason)`.

## Sample

`:sample` uses Google test units for every format:

```bash
./gradlew :sample:installDebug
```

## Releasing

```bash
./gradlew :admob:testDebugUnitTest :admob:publishToMavenLocal :admob-compose:publishToMavenLocal
git tag v0.1.0 && git push origin v0.1.0
```

Then build the tag on `https://jitpack.io/#susu1009/android-admob`.
