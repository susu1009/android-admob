package com.banv.admob

import android.app.Activity
import android.content.Context
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.OnPaidEventListener
import com.google.android.gms.ads.ResponseInfo
import com.google.android.gms.ads.appopen.AppOpenAd
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback
import com.google.android.gms.ads.rewarded.RewardedAd
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback

/**
 * Load cache + show flow shared by interstitial, rewarded and app-open ads: one ad per placement,
 * loads de-duplicated, expired ads dropped, loading dialog + timeout, result delivered once.
 */
public abstract class FullscreenAdManager<T : Any> internal constructor(
    private val expiryMs: Long,
) {
    private class Slot<T> {
        var ad: T? = null
        var adUnitId: String? = null
        var loadedAt: Long = 0
        var loading = false
        val waiters = mutableListOf<(LoadAdError?) -> Unit>()
    }

    private val slots = mutableMapOf<String, Slot<T>>()

    internal abstract fun requestLoad(
        context: Context, adUnitId: String,
        onLoaded: (T) -> Unit, onFailed: (LoadAdError) -> Unit,
    )

    internal abstract fun T.bind(callback: FullScreenContentCallback, paid: OnPaidEventListener)
    internal abstract fun T.responseInfo(): ResponseInfo?
    internal abstract fun present(ad: T, activity: Activity, onReward: (AdReward) -> Unit)

    /** Extra reason not to show (frequency caps). */
    internal open fun blockReason(placement: AdPlacement): SkipReason? = null
    internal open fun onShown(placement: AdPlacement) {}

    /** Start loading [placement] in the background so a later show is instant. No-op if off or loaded. */
    public fun preload(context: Context, placement: AdPlacement) {
        load(context.applicationContext, placement, null)
    }

    public fun isReady(placement: AdPlacement): Boolean {
        val unit = AdKit.config.value.resolve(placement) ?: return false
        return validAd(placement.key, unit) != null
    }

    /** Drops cached ads (e.g. after the user goes premium). */
    public fun clear() {
        slots.values.forEach { it.ad = null }
    }

    private fun validAd(key: String, unit: String): T? {
        val slot = slots[key] ?: return null
        val ad = slot.ad ?: return null
        if (slot.adUnitId != unit || AdKit.now() - slot.loadedAt > expiryMs) {
            slot.ad = null
            return null
        }
        return ad
    }

    private fun take(key: String, unit: String): T? = validAd(key, unit)?.also { slots[key]?.ad = null }

    private fun putBack(key: String, unit: String, ad: T) {
        val slot = slots.getOrPut(key) { Slot() }
        if (slot.ad == null) {
            slot.ad = ad
            slot.adUnitId = unit
        }
    }

    internal fun load(context: Context, placement: AdPlacement, onDone: ((LoadAdError?) -> Unit)?) {
        val unit = AdKit.config.value.resolve(placement)
        if (unit == null || !AdKit.canRequestAds.value) {
            onDone?.invoke(null)
            return
        }
        val slot = slots.getOrPut(placement.key) { Slot() }
        if (validAd(placement.key, unit) != null) {
            onDone?.invoke(null)
            return
        }
        if (onDone != null) slot.waiters += onDone
        if (slot.loading) return
        slot.loading = true
        AdKit.emit(placement, unit, AdEvent.Type.Requested)
        requestLoad(
            context, unit,
            onLoaded = { ad ->
                slot.loading = false
                slot.ad = ad
                slot.adUnitId = unit
                slot.loadedAt = AdKit.now()
                AdKit.emit(placement, unit, AdEvent.Type.Loaded)
                slot.drain(null)
            },
            onFailed = { error ->
                slot.loading = false
                AdKit.emit(placement, unit, AdEvent.Type.LoadFailed(error.code, error.message))
                slot.drain(error)
            },
        )
    }

    private fun Slot<T>.drain(error: LoadAdError?) {
        val list = waiters.toList()
        waiters.clear()
        list.forEach { it(error) }
    }

    internal fun showInternal(
        activity: Activity,
        placement: AdPlacement,
        loadingDialog: AdLoadingDialog,
        onlyIfReady: Boolean,
        onReward: (AdReward) -> Unit,
        onResult: (AdShowResult) -> Unit,
    ) {
        val config = AdKit.config.value
        val unit = config.resolve(placement)
        fun skip(reason: SkipReason, message: String? = null) {
            AdKit.emit(placement, unit, AdEvent.Type.Skipped(reason))
            onResult(AdShowResult.NotShown(reason, message))
        }
        when {
            !AdKit.isInitialized -> return skip(SkipReason.NOT_INITIALIZED)
            unit == null -> return skip(SkipReason.DISABLED)
            !AdKit.canRequestAds.value -> return skip(SkipReason.NO_CONSENT)
            AdKit.fullscreenShowing -> return skip(SkipReason.ALREADY_SHOWING)
            activity.isFinishing || activity.isDestroyed -> return skip(SkipReason.ACTIVITY_GONE)
        }
        blockReason(placement)?.let { return skip(it) }
        val ready = validAd(placement.key, unit!!) != null
        if (onlyIfReady && !ready) {
            preload(activity, placement)
            return skip(SkipReason.LOAD_FAILED, "not loaded yet")
        }

        val handle = loadingDialog.show(activity)
        var finished = false
        fun finish(result: AdShowResult) {
            if (finished) return
            finished = true
            handle.dismiss()
            if (result is AdShowResult.NotShown) AdKit.emit(placement, unit, AdEvent.Type.Skipped(result.reason))
            onResult(result)
        }

        fun presentNow() {
            if (finished) return
            val ad = take(placement.key, unit) ?: return finish(AdShowResult.NotShown(SkipReason.LOAD_FAILED))
            val owner = activity as? LifecycleOwner
            val visible = owner?.lifecycle?.currentState?.isAtLeast(Lifecycle.State.STARTED) ?: true
            if (!visible || activity.isFinishing || activity.isDestroyed) {
                putBack(placement.key, unit, ad)
                return finish(AdShowResult.NotShown(SkipReason.ACTIVITY_GONE))
            }
            var reward: AdReward? = null
            AdKit.fullscreenShowing = true
            ad.bind(
                object : FullScreenContentCallback() {
                    override fun onAdShowedFullScreenContent() {
                        handle.dismiss()
                        onShown(placement)
                        AdKit.emit(placement, unit, AdEvent.Type.Shown)
                    }

                    override fun onAdFailedToShowFullScreenContent(error: AdError) {
                        AdKit.fullscreenShowing = false
                        AdKit.emit(placement, unit, AdEvent.Type.ShowFailed(error.code, error.message))
                        finish(AdShowResult.NotShown(SkipReason.SHOW_FAILED, error.message))
                        reloadIfWanted(activity, placement)
                    }

                    override fun onAdDismissedFullScreenContent() {
                        AdKit.fullscreenShowing = false
                        AdKit.lastFullscreenDismissedAt = AdKit.now()
                        AdKit.emit(placement, unit, AdEvent.Type.Dismissed)
                        finish(AdShowResult.Shown(reward))
                        reloadIfWanted(activity, placement)
                    }

                    override fun onAdImpression() = AdKit.emit(placement, unit, AdEvent.Type.Impression)
                    override fun onAdClicked() = AdKit.emit(placement, unit, AdEvent.Type.Clicked)
                },
                { value -> AdKit.emitPaid(placement, unit, value, ad.responseInfo()) },
            )
            present(ad, activity) { earned ->
                reward = earned
                AdKit.emit(placement, unit, AdEvent.Type.RewardEarned(earned))
                onReward(earned)
            }
        }

        if (ready) {
            val delay = if (loadingDialog === AdLoadingDialog.None) 0 else config.fullscreen.minLoadingDialogMs
            AdKit.handler.postDelayed({ presentNow() }, delay)
        } else {
            val timeout = Runnable { finish(AdShowResult.NotShown(SkipReason.TIMEOUT)) }
            AdKit.handler.postDelayed(timeout, config.fullscreen.loadTimeoutMs)
            // A late load stays cached for the next show.
            load(activity.applicationContext, placement) { error ->
                AdKit.handler.removeCallbacks(timeout)
                if (error != null) finish(AdShowResult.NotShown(SkipReason.LOAD_FAILED, error.message))
                else presentNow()
            }
        }
    }

    internal open fun reloadIfWanted(context: Context, placement: AdPlacement) {
        if (AdKit.config.value.fullscreen.reloadAfterShow) preload(context, placement)
    }
}

public class InterstitialAds internal constructor() : FullscreenAdManager<InterstitialAd>(HOUR) {

    /**
     * Shows the interstitial for [placement]: loads it first if needed (with [loadingDialog] on
     * screen, up to [FullscreenOptions.loadTimeoutMs]). Continue your navigation in [onResult] —
     * it is called exactly once, whether or not the ad was shown.
     */
    public fun show(
        activity: Activity,
        placement: AdPlacement,
        loadingDialog: AdLoadingDialog = AdLoadingDialog.Default,
        onResult: (AdShowResult) -> Unit = {},
    ): Unit = showInternal(activity, placement, loadingDialog, onlyIfReady = false, onReward = {}, onResult = onResult)

    /** Shows only an already-loaded ad (no wait); otherwise starts loading for next time. */
    public fun showIfReady(
        activity: Activity,
        placement: AdPlacement,
        loadingDialog: AdLoadingDialog = AdLoadingDialog.None,
        onResult: (AdShowResult) -> Unit = {},
    ): Unit = showInternal(activity, placement, loadingDialog, onlyIfReady = true, onReward = {}, onResult = onResult)

    override fun blockReason(placement: AdPlacement): SkipReason? = SkipReason.FREQUENCY_CAP.takeIf {
        FrequencyCap.blocked(AdKit.now(), AdKit.lastInterstitialShownAt, AdKit.config.value.fullscreen.interstitialIntervalMs)
    }

    override fun onShown(placement: AdPlacement) {
        AdKit.lastInterstitialShownAt = AdKit.now()
    }

    override fun requestLoad(context: Context, adUnitId: String, onLoaded: (InterstitialAd) -> Unit, onFailed: (LoadAdError) -> Unit) {
        InterstitialAd.load(context, adUnitId, AdKit.request(), object : InterstitialAdLoadCallback() {
            override fun onAdLoaded(ad: InterstitialAd) = onLoaded(ad)
            override fun onAdFailedToLoad(error: LoadAdError) = onFailed(error)
        })
    }

    override fun InterstitialAd.bind(callback: FullScreenContentCallback, paid: OnPaidEventListener) {
        fullScreenContentCallback = callback
        onPaidEventListener = paid
    }

    override fun InterstitialAd.responseInfo(): ResponseInfo = responseInfo
    override fun present(ad: InterstitialAd, activity: Activity, onReward: (AdReward) -> Unit): Unit = ad.show(activity)
}

public class RewardedAds internal constructor() : FullscreenAdManager<RewardedAd>(HOUR) {

    /**
     * Shows the rewarded ad for [placement]. Grant the reward in [onReward] (called as soon as it
     * is earned, before the ad closes); [onResult] follows once the ad is gone, with
     * `Shown(reward)` / `Shown(null)` if the user closed early, or `NotShown`.
     */
    public fun show(
        activity: Activity,
        placement: AdPlacement,
        loadingDialog: AdLoadingDialog = AdLoadingDialog.Default,
        onReward: (AdReward) -> Unit,
        onResult: (AdShowResult) -> Unit = {},
    ): Unit = showInternal(activity, placement, loadingDialog, onlyIfReady = false, onReward = onReward, onResult = onResult)

    override fun requestLoad(context: Context, adUnitId: String, onLoaded: (RewardedAd) -> Unit, onFailed: (LoadAdError) -> Unit) {
        RewardedAd.load(context, adUnitId, AdKit.request(), object : RewardedAdLoadCallback() {
            override fun onAdLoaded(ad: RewardedAd) = onLoaded(ad)
            override fun onAdFailedToLoad(error: LoadAdError) = onFailed(error)
        })
    }

    override fun RewardedAd.bind(callback: FullScreenContentCallback, paid: OnPaidEventListener) {
        fullScreenContentCallback = callback
        onPaidEventListener = paid
    }

    override fun RewardedAd.responseInfo(): ResponseInfo = responseInfo
    override fun present(ad: RewardedAd, activity: Activity, onReward: (AdReward) -> Unit) {
        ad.show(activity) { item -> onReward(AdReward(item.type, item.amount)) }
    }
}

internal const val HOUR: Long = 60 * 60 * 1000L

internal object FrequencyCap {
    fun blocked(now: Long, lastShownAt: Long, intervalMs: Long): Boolean =
        intervalMs > 0 && now - lastShownAt < intervalMs
}

/** App-open loader; the resume trigger lives in [AppOpenAds]. */
internal fun loadAppOpen(context: Context, adUnitId: String, onLoaded: (AppOpenAd) -> Unit, onFailed: (LoadAdError) -> Unit) {
    AppOpenAd.load(context, adUnitId, AdKit.request(), object : AppOpenAd.AppOpenAdLoadCallback() {
        override fun onAdLoaded(ad: AppOpenAd) = onLoaded(ad)
        override fun onAdFailedToLoad(error: LoadAdError) = onFailed(error)
    })
}
