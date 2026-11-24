package com.banv.admob

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.google.ads.mediation.admob.AdMobAdapter
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdValue
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.RequestConfiguration
import com.google.android.gms.ads.ResponseInfo
import com.google.android.ump.ConsentDebugSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Entry point. Call [initialize] once in `Application.onCreate`, then [gatherConsent] from the
 * first activity (splash). Everything here must be called on the main thread.
 */
public object AdKit {
    private var application: Application? = null
    private val _config = MutableStateFlow(AdConfig())
    private val _canRequestAds = MutableStateFlow(false)
    private val sdkStarted = AtomicBoolean(false)
    private var placements: List<AdPlacement> = emptyList()

    internal val handler: Handler by lazy { Handler(Looper.getMainLooper()) }
    internal var clock: () -> Long = { SystemClock.elapsedRealtime() }

    /** Shared state across full-screen formats (only one can be on screen). */
    internal var fullscreenShowing: Boolean = false
    internal var lastFullscreenDismissedAt: Long = Long.MIN_VALUE / 2
    internal var lastInterstitialShownAt: Long = Long.MIN_VALUE / 2

    /** Current configuration; collect it to react to premium / remote changes. */
    public val config: StateFlow<AdConfig> = _config.asStateFlow()

    /** True once ads may be requested (consent obtained or not required) and the SDK was started. */
    public val canRequestAds: StateFlow<Boolean> = _canRequestAds.asStateFlow()

    public var listener: AdEventListener? = null

    public val interstitial: InterstitialAds = InterstitialAds()
    public val rewarded: RewardedAds = RewardedAds()
    public val appOpen: AppOpenAds = AppOpenAds()
    public val native: NativeAds = NativeAds()
    public val consent: AdConsent = AdConsent()

    public val isInitialized: Boolean get() = application != null

    /**
     * @param placements every placement the app declares; needed for [applyRemoteValues] and to
     *   preload nothing by accident (nothing is loaded until asked).
     */
    public fun initialize(
        application: Application,
        config: AdConfig,
        placements: Collection<AdPlacement> = emptyList(),
        listener: AdEventListener? = null,
    ) {
        if (this.application != null) return
        this.application = application
        this.placements = placements.toList()
        this.listener = listener
        _config.value = config
        appOpen.register(application)
        // Consent from a previous session lets requests start before this session's update returns.
        if (!config.requireConsent || consent.info(application).canRequestAds()) startSdk()
    }

    public fun updateConfig(transform: (AdConfig) -> AdConfig) {
        _config.update(transform)
        val app = application ?: return
        if (!_config.value.requireConsent || consent.info(app).canRequestAds()) startSdk()
    }

    /** "Remove ads" purchased / restored. Rewarded ads keep working. */
    public fun setPremium(premium: Boolean): Unit = updateConfig { it.copy(premium = premium) }

    /** See [AdConfig.withRemoteValues] for the key names. */
    public fun applyRemoteValues(lookup: (String) -> String?): Unit =
        updateConfig { it.withRemoteValues(placements, lookup) }

    /**
     * Requests the UMP consent status and shows the form when required (EEA/UK/…), then starts the
     * SDK if ads may be requested. [onDone] always runs, with [canRequestAds]'s value.
     */
    public fun gatherConsent(
        activity: Activity,
        debugSettings: ConsentDebugSettings? = null,
        onDone: (canRequestAds: Boolean) -> Unit = {},
    ) {
        if (!_config.value.requireConsent) {
            startSdk()
            onDone(true)
            return
        }
        consent.gather(activity, debugSettings) { allowed ->
            if (allowed) startSdk()
            onDone(_canRequestAds.value)
        }
    }

    internal fun startSdk() {
        val app = application ?: return
        if (!sdkStarted.compareAndSet(false, true)) return
        MobileAds.setRequestConfiguration(
            RequestConfiguration.Builder().setTestDeviceIds(_config.value.testDeviceIds).build(),
        )
        // initialize() does disk/network work: keep it off the main thread.
        Thread({ MobileAds.initialize(app) {} }, "AdKit-init").start()
        _canRequestAds.value = true
    }

    internal fun appContext(): Context? = application

    internal fun now(): Long = clock()

    internal fun request(extras: Bundle? = null): AdRequest = AdRequest.Builder().apply {
        if (extras != null && !extras.isEmpty) addNetworkExtrasBundle(AdMobAdapter::class.java, extras)
    }.build()

    internal fun emit(placement: AdPlacement, adUnitId: String?, type: AdEvent.Type) {
        listener?.onAdEvent(AdEvent(placement.key, placement.format, adUnitId, type))
    }

    internal fun emitPaid(placement: AdPlacement, adUnitId: String, value: AdValue, info: ResponseInfo?) {
        emit(
            placement, adUnitId,
            AdEvent.Type.Paid(
                value.valueMicros, value.currencyCode, value.precisionType,
                info?.loadedAdapterResponseInfo?.adSourceName,
            ),
        )
    }
}

/** The Activity behind a (possibly wrapped) Context, e.g. `LocalContext.current.findActivity()`. */
public tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
