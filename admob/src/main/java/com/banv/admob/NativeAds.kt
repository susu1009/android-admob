package com.banv.admob

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.RatingBar
import android.widget.TextView
import androidx.annotation.LayoutRes
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.findViewTreeLifecycleOwner
import com.google.android.gms.ads.AdListener
import com.google.android.gms.ads.AdLoader
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.nativead.MediaView
import com.google.android.gms.ads.nativead.NativeAd
import com.google.android.gms.ads.nativead.NativeAdOptions
import com.google.android.gms.ads.nativead.NativeAdView

/**
 * Native layouts. The built-in ones read their colors from `admob_native_*` color resources —
 * override those in the app to restyle them. [Custom] takes the app's own layout: its root must
 * be a [NativeAdView] and its asset views use the library ids (`@id/admob_headline`,
 * `admob_body`, `admob_call_to_action`, `admob_icon`, `admob_media`, `admob_advertiser`,
 * `admob_stars`, `admob_price`, `admob_store`); any of them except the headline may be left out.
 */
public sealed class NativeTemplate(@LayoutRes public val layoutRes: Int) {
    /** One row: icon, headline, CTA. No media — for lists and bottom bars. */
    public data object Small : NativeTemplate(R.layout.admob_native_small)
    /** Icon + headline, short media, body, full-width CTA. */
    public data object Medium : NativeTemplate(R.layout.admob_native_medium)
    /** Large 16:9 media on top — for feeds / result screens. */
    public data object Large : NativeTemplate(R.layout.admob_native_large)
    public class Custom(@LayoutRes layoutRes: Int) : NativeTemplate(layoutRes)
}

/** Fills a [NativeAdView] from a [NativeAd]. Replace it when your layout needs more than the id convention. */
public fun interface NativeAdBinder {
    public fun bind(view: NativeAdView, ad: NativeAd)

    public companion object {
        /** Binds by the `admob_*` ids; hides views whose asset is missing. */
        public val Default: NativeAdBinder = NativeAdBinder { view, ad ->
            fun <V : View> find(id: Int): V? = view.findViewById(id)

            find<TextView>(R.id.admob_headline)?.let { it.text = ad.headline; view.headlineView = it }
            find<TextView>(R.id.admob_body)?.let { view.bodyView = it.showText(ad.body) }
            find<TextView>(R.id.admob_call_to_action)?.let { view.callToActionView = it.showText(ad.callToAction) }
            find<TextView>(R.id.admob_advertiser)?.let { view.advertiserView = it.showText(ad.advertiser) }
            find<TextView>(R.id.admob_price)?.let { view.priceView = it.showText(ad.price) }
            find<TextView>(R.id.admob_store)?.let { view.storeView = it.showText(ad.store) }
            find<ImageView>(R.id.admob_icon)?.let {
                val icon = ad.icon?.drawable
                it.setImageDrawable(icon)
                it.visibility = if (icon == null) View.GONE else View.VISIBLE
                view.iconView = it
            }
            find<RatingBar>(R.id.admob_stars)?.let {
                val stars = ad.starRating
                if (stars != null && stars > 0) it.rating = stars.toFloat()
                it.visibility = if (stars != null && stars > 0) View.VISIBLE else View.GONE
                view.starRatingView = it
            }
            find<MediaView>(R.id.admob_media)?.let {
                view.mediaView = it
                ad.mediaContent?.let(it::setMediaContent)
            }
            view.setNativeAd(ad)
        }

        private fun TextView.showText(value: String?): TextView = apply {
            text = value
            visibility = if (value.isNullOrBlank()) View.GONE else View.VISIBLE
            if (this is Button) isAllCaps = false
        }
    }
}

/** Loads native ads per placement, with an optional preload pool. */
public class NativeAds internal constructor() {
    private class Cached(val ad: NativeAd, val adUnitId: String, val loadedAt: Long)

    private val pool = mutableMapOf<String, ArrayDeque<Cached>>()

    /** Load [count] ads ahead (e.g. before opening a list). */
    public fun preload(context: Context, placement: AdPlacement, count: Int = 1) {
        val unit = AdKit.config.value.resolve(placement) ?: return
        if (!AdKit.canRequestAds.value) return
        val missing = count - valid(placement.key, unit).size
        repeat(missing.coerceAtLeast(0)) {
            request(context.applicationContext, placement, unit) { ad ->
                if (ad != null) pool.getOrPut(placement.key) { ArrayDeque() }.addLast(Cached(ad, unit, AdKit.now()))
            }
        }
    }

    /**
     * Gives a preloaded ad if there is one, otherwise loads one. `null` = off, no consent or no
     * fill. The caller owns the ad: call [NativeAd.destroy] when done (the views here do it).
     */
    public fun load(context: Context, placement: AdPlacement, onResult: (NativeAd?) -> Unit) {
        val unit = AdKit.config.value.resolve(placement)
        if (unit == null || !AdKit.canRequestAds.value) {
            AdKit.emit(placement, unit, AdEvent.Type.Skipped(if (unit == null) SkipReason.DISABLED else SkipReason.NO_CONSENT))
            return onResult(null)
        }
        valid(placement.key, unit).removeFirstOrNull()?.let { return onResult(it.ad) }
        request(context.applicationContext, placement, unit, onResult)
    }

    private fun valid(key: String, unit: String): ArrayDeque<Cached> {
        val queue = pool.getOrPut(key) { ArrayDeque() }
        val now = AdKit.now()
        queue.removeAll { stale ->
            (stale.adUnitId != unit || now - stale.loadedAt > HOUR).also { if (it) stale.ad.destroy() }
        }
        return queue
    }

    private fun request(context: Context, placement: AdPlacement, unit: String, onResult: (NativeAd?) -> Unit) {
        AdKit.emit(placement, unit, AdEvent.Type.Requested)
        AdLoader.Builder(context, unit)
            .forNativeAd { ad ->
                ad.setOnPaidEventListener { value -> AdKit.emitPaid(placement, unit, value, ad.responseInfo) }
                AdKit.emit(placement, unit, AdEvent.Type.Loaded)
                onResult(ad)
            }
            .withAdListener(object : AdListener() {
                override fun onAdFailedToLoad(error: LoadAdError) {
                    AdKit.emit(placement, unit, AdEvent.Type.LoadFailed(error.code, error.message))
                    onResult(null)
                }

                override fun onAdImpression() = AdKit.emit(placement, unit, AdEvent.Type.Impression)
                override fun onAdClicked() = AdKit.emit(placement, unit, AdEvent.Type.Clicked)
            })
            .withNativeAdOptions(
                NativeAdOptions.Builder().setAdChoicesPlacement(NativeAdOptions.ADCHOICES_TOP_RIGHT).build(),
            )
            .build()
            .loadAd(AdKit.request())
    }
}

/**
 * Native ad container: [load] fetches and inflates [template]. Hidden (GONE) when off or no fill;
 * [placeholder] (e.g. a shimmer) is shown meanwhile. The ad is destroyed with the hosting lifecycle
 * or by [destroy].
 */
public class AdNativeView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {

    private var ad: NativeAd? = null
    private var lifecycle: Lifecycle? = null
    private var generation = 0

    public var placeholder: View? = null
    public var onStateChange: ((AdViewState) -> Unit)? = null
    public var state: AdViewState = AdViewState.IDLE
        private set(value) {
            field = value
            onStateChange?.invoke(value)
        }

    public fun load(
        placement: AdPlacement,
        template: NativeTemplate = NativeTemplate.Medium,
        binder: NativeAdBinder = NativeAdBinder.Default,
    ) {
        destroy()
        val token = ++generation
        visibility = VISIBLE
        state = AdViewState.LOADING
        placeholder?.let { addView(it.also { p -> (p.parent as? FrameLayout)?.removeView(p) }) }
        AdKit.native.load(context, placement) { loaded ->
            if (token != generation) {
                loaded?.destroy()
                return@load
            }
            if (loaded == null) {
                removeAllViews()
                visibility = GONE
                state = if (AdKit.config.value.isEnabled(placement)) AdViewState.FAILED else AdViewState.DISABLED
            } else {
                show(loaded, template, binder)
            }
        }
    }

    /** Binds an ad loaded elsewhere (e.g. from [NativeAds.load]); this view then owns it. */
    public fun show(ad: NativeAd, template: NativeTemplate = NativeTemplate.Medium, binder: NativeAdBinder = NativeAdBinder.Default) {
        this.ad?.takeIf { it !== ad }?.destroy()
        this.ad = ad
        removeAllViews()
        val inflated = LayoutInflater.from(context).inflate(template.layoutRes, this, false)
        require(inflated is NativeAdView) { "Native layout root must be com.google.android.gms.ads.nativead.NativeAdView" }
        binder.bind(inflated, ad)
        addView(inflated)
        visibility = VISIBLE
        state = AdViewState.LOADED
    }

    public fun destroy() {
        generation++
        ad?.destroy()
        ad = null
        removeAllViews()
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
        override fun onDestroy(owner: LifecycleOwner) = destroy()
    }
}
