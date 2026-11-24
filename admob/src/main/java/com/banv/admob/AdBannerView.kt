package com.banv.admob

import android.content.Context
import android.os.Bundle
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.findViewTreeLifecycleOwner
import com.google.android.gms.ads.AdListener
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.LoadAdError
import java.util.UUID

/** Banner kinds. */
public sealed interface BannerType {
    /** Anchored adaptive banner: full width, height picked by AdMob for this device. */
    public data object Adaptive : BannerType

    /**
     * Collapsible banner: opens large over the content, then collapses to an adaptive banner.
     * Sent as the `collapsible` network extra; [anchor] must match where the view sits on screen.
     */
    public data class Collapsible(val anchor: Anchor = Anchor.BOTTOM) : BannerType

    /** A fixed size, e.g. `AdSize.MEDIUM_RECTANGLE` (300x250) inside a list. */
    public data class Fixed(val size: AdSize) : BannerType

    public enum class Anchor(internal val value: String) { TOP("top"), BOTTOM("bottom") }
}

public enum class AdViewState { IDLE, LOADING, LOADED, FAILED, DISABLED }

/**
 * Banner container. Call [load]; the view hides itself (GONE) when the placement is off or no
 * ad fills. Pauses/resumes/destroys with the lifecycle of the screen it is attached to.
 */
public class AdBannerView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {

    private var adView: AdView? = null
    private var pending: (() -> Unit)? = null
    private var lifecycle: Lifecycle? = null

    /** Keep the banner's height reserved while loading, so content doesn't jump. */
    public var reserveSpace: Boolean = true
    public var onStateChange: ((AdViewState) -> Unit)? = null
    public var state: AdViewState = AdViewState.IDLE
        private set(value) {
            field = value
            onStateChange?.invoke(value)
        }

    /**
     * @param extras extra AdMob network extras (merged with the collapsible ones), for anything
     *   else AdMob documents as a request extra.
     */
    public fun load(placement: AdPlacement, type: BannerType = BannerType.Adaptive, extras: Bundle? = null) {
        destroyAd()
        val unit = AdKit.config.value.resolve(placement)
        if (unit == null || !AdKit.canRequestAds.value) {
            visibility = GONE
            state = AdViewState.DISABLED
            AdKit.emit(placement, unit, AdEvent.Type.Skipped(if (unit == null) SkipReason.DISABLED else SkipReason.NO_CONSENT))
            return
        }
        visibility = VISIBLE
        state = AdViewState.LOADING
        val start = { startLoad(placement, unit, type, extras) }
        if (width > 0 || type is BannerType.Fixed) start() else pending = start.also { requestLayout() }
    }

    private fun startLoad(placement: AdPlacement, unit: String, type: BannerType, extras: Bundle?) {
        val size = when (type) {
            is BannerType.Fixed -> type.size
            else -> {
                val px = if (width > 0) width else resources.displayMetrics.widthPixels
                val dp = (px / resources.displayMetrics.density).toInt()
                AdSize.getCurrentOrientationAnchoredAdaptiveBannerAdSize(context, dp)
            }
        }
        val bundle = Bundle(extras ?: Bundle.EMPTY)
        if (type is BannerType.Collapsible) {
            bundle.putString("collapsible", type.anchor.value)
            // A fresh id per request: each new request may open again.
            bundle.putString("collapsible_request_id", UUID.randomUUID().toString())
        }
        if (reserveSpace) minimumHeight = size.getHeightInPixels(context)
        val view = AdView(context).apply {
            adUnitId = unit
            setAdSize(size)
            adListener = object : AdListener() {
                override fun onAdLoaded() {
                    state = AdViewState.LOADED
                    AdKit.emit(placement, unit, AdEvent.Type.Loaded)
                }

                override fun onAdFailedToLoad(error: LoadAdError) {
                    AdKit.emit(placement, unit, AdEvent.Type.LoadFailed(error.code, error.message))
                    // Auto-refresh may fill later; only collapse when nothing was ever shown.
                    if (state != AdViewState.LOADED) {
                        this@AdBannerView.visibility = GONE
                        state = AdViewState.FAILED
                    }
                }

                override fun onAdImpression() = AdKit.emit(placement, unit, AdEvent.Type.Impression)
                override fun onAdClicked() = AdKit.emit(placement, unit, AdEvent.Type.Clicked)
            }
            setOnPaidEventListener { value -> AdKit.emitPaid(placement, unit, value, responseInfo) }
        }
        addView(view, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        adView = view
        AdKit.emit(placement, unit, AdEvent.Type.Requested)
        view.loadAd(AdKit.request(bundle))
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        val run = pending
        if (run != null && width > 0) {
            pending = null
            post(run)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        lifecycle = findViewTreeLifecycleOwner()?.lifecycle?.also { it.addObserver(observer) }
    }

    override fun onDetachedFromWindow() {
        lifecycle?.removeObserver(observer)
        lifecycle = null
        super.onDetachedFromWindow()
    }

    private val observer = object : DefaultLifecycleObserver {
        override fun onResume(owner: LifecycleOwner) { adView?.resume() }
        override fun onPause(owner: LifecycleOwner) { adView?.pause() }
        override fun onDestroy(owner: LifecycleOwner) = destroy()
    }

    private fun destroyAd() {
        pending = null
        adView?.let {
            removeView(it)
            it.destroy()
        }
        adView = null
        minimumHeight = 0
    }

    /** Releases the ad. Called automatically when the hosting lifecycle is destroyed. */
    public fun destroy() {
        destroyAd()
    }

    override fun setVisibility(visibility: Int) {
        super.setVisibility(visibility)
        if (visibility == View.GONE) minimumHeight = 0
    }
}
