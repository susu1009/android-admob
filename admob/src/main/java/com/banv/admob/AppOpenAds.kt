package com.banv.admob

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.OnPaidEventListener
import com.google.android.gms.ads.ResponseInfo
import com.google.android.gms.ads.appopen.AppOpenAd
import java.lang.ref.WeakReference

/**
 * App-open ads. Shown automatically when the app returns from background, using
 * [AppOpenOptions.placement]; also usable by hand on the splash screen with [show].
 */
public class AppOpenAds internal constructor() : FullscreenAdManager<AppOpenAd>(4 * HOUR) {
    private var current: WeakReference<Activity>? = null
    private val excluded = mutableSetOf<Class<out Activity>>()
    private var coldStart = true
    private var skipNext = false
    private var lastShownAt = Long.MIN_VALUE / 2

    /** UI shown for [AppOpenOptions] resumes; the ad is already loaded then, so it only shows briefly. */
    public var resumeLoadingDialog: AdLoadingDialog = AdLoadingDialog.None

    /** Never show the resume ad over these screens (splash, paywall, checkout…). */
    public fun exclude(vararg activities: Class<out Activity>) {
        excluded += activities
    }

    /**
     * Skip the next resume ad. Call before leaving the app on purpose (purchase sheet, share,
     * camera, permission settings, sign-in browser) so coming back doesn't trigger an ad.
     */
    public fun skipNextResume() {
        skipNext = true
    }

    /** Splash usage: load (up to the timeout) and show; continue to the main screen in [onResult]. */
    public fun show(
        activity: Activity,
        placement: AdPlacement? = AdKit.config.value.appOpen.placement,
        loadingDialog: AdLoadingDialog = AdLoadingDialog.None,
        onResult: (AdShowResult) -> Unit = {},
    ) {
        if (placement == null) return onResult(AdShowResult.NotShown(SkipReason.DISABLED, "no app-open placement"))
        showInternal(activity, placement, loadingDialog, onlyIfReady = false, onReward = {}, onResult = onResult)
    }

    internal fun register(application: Application) {
        application.registerActivityLifecycleCallbacks(activityTracker)
        ProcessLifecycleOwner.get().lifecycle.addObserver(processObserver)
    }

    private val processObserver = object : DefaultLifecycleObserver {
        override fun onStart(owner: LifecycleOwner) {
            val wasCold = coldStart
            coldStart = false
            onForeground(wasCold)
        }
    }

    private fun onForeground(cold: Boolean) {
        val config = AdKit.config.value
        val placement = config.appOpen.placement ?: return
        val activity = current?.get()
        val reason = AppOpenPolicy.skipReason(
            now = AdKit.now(),
            coldStart = cold,
            skipNext = skipNext,
            excludedActivity = activity == null || activity.javaClass in excluded,
            fullscreenShowing = AdKit.fullscreenShowing,
            lastFullscreenDismissedAt = AdKit.lastFullscreenDismissedAt,
            lastAppOpenShownAt = lastShownAt,
            options = config.appOpen,
        )
        skipNext = false
        if (reason != null || activity == null) {
            AdKit.appContext()?.let { preload(it, placement) }
            return
        }
        showInternal(activity, placement, resumeLoadingDialog, onlyIfReady = true, onReward = {}) {}
    }

    override fun onShown(placement: AdPlacement) {
        lastShownAt = AdKit.now()
    }

    override fun blockReason(placement: AdPlacement): SkipReason? = SkipReason.FREQUENCY_CAP.takeIf {
        FrequencyCap.blocked(AdKit.now(), lastShownAt, AdKit.config.value.appOpen.intervalMs)
    }

    override fun reloadIfWanted(context: Context, placement: AdPlacement) {
        // Always keep one ready for the next resume.
        preload(context, placement)
    }

    override fun requestLoad(context: Context, adUnitId: String, onLoaded: (AppOpenAd) -> Unit, onFailed: (LoadAdError) -> Unit): Unit =
        loadAppOpen(context, adUnitId, onLoaded, onFailed)

    override fun AppOpenAd.bind(callback: FullScreenContentCallback, paid: OnPaidEventListener) {
        fullScreenContentCallback = callback
        onPaidEventListener = paid
    }

    override fun AppOpenAd.responseInfo(): ResponseInfo = responseInfo
    override fun present(ad: AppOpenAd, activity: Activity, onReward: (AdReward) -> Unit): Unit = ad.show(activity)

    private val activityTracker = object : Application.ActivityLifecycleCallbacks {
        private fun track(activity: Activity) {
            // The SDK's own AdActivity must not become the host of the next ad.
            if (!activity.javaClass.name.startsWith("com.google.android.gms.ads")) current = WeakReference(activity)
        }

        override fun onActivityStarted(activity: Activity) = track(activity)
        override fun onActivityResumed(activity: Activity) = track(activity)
        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
        override fun onActivityPaused(activity: Activity) {}
        override fun onActivityStopped(activity: Activity) {}
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
        override fun onActivityDestroyed(activity: Activity) {
            if (current?.get() === activity) current = null
        }
    }
}

internal object AppOpenPolicy {
    fun skipReason(
        now: Long,
        coldStart: Boolean,
        skipNext: Boolean,
        excludedActivity: Boolean,
        fullscreenShowing: Boolean,
        lastFullscreenDismissedAt: Long,
        lastAppOpenShownAt: Long,
        options: AppOpenOptions,
    ): SkipReason? = when {
        coldStart && !options.showOnColdStart -> SkipReason.DISABLED
        skipNext || excludedActivity -> SkipReason.DISABLED
        fullscreenShowing -> SkipReason.ALREADY_SHOWING
        now - lastFullscreenDismissedAt < options.graceAfterFullscreenMs -> SkipReason.FREQUENCY_CAP
        FrequencyCap.blocked(now, lastAppOpenShownAt, options.intervalMs) -> SkipReason.FREQUENCY_CAP
        else -> null
    }
}
