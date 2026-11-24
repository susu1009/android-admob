package com.banv.admob.compose

import android.app.Activity
import android.os.Bundle
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionContext
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCompositionContext
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.banv.admob.AdBannerView
import com.banv.admob.AdKit
import com.banv.admob.AdLoadingDialog
import com.banv.admob.AdNativeView
import com.banv.admob.AdPlacement
import com.banv.admob.AdViewState
import com.banv.admob.BannerType
import com.banv.admob.NativeAdBinder
import com.banv.admob.NativeTemplate
import com.banv.admob.findActivity

/**
 * Banner. Renders nothing when the placement is off (incl. premium), before consent, or when no
 * ad fills. Put it where [type]'s anchor says (a collapsible BOTTOM banner at the screen bottom).
 */
@Composable
public fun BannerAd(
    placement: AdPlacement,
    modifier: Modifier = Modifier,
    type: BannerType = BannerType.Adaptive,
    extras: Bundle? = null,
) {
    val config by AdKit.config.collectAsStateWithLifecycle()
    val canRequest by AdKit.canRequestAds.collectAsStateWithLifecycle()
    val unit = config.resolve(placement)
    if (unit == null || !canRequest) return
    // Keyed on the unit/type so a Remote Config change reloads the banner.
    androidx.compose.runtime.key(unit, type) {
        AndroidView(
            factory = { context -> AdBannerView(context).also { it.load(placement, type, extras) } },
            modifier = modifier.fillMaxWidth(),
            onRelease = { it.destroy() },
        )
    }
}

/**
 * Native ad using a built-in or custom [template]. Shows [placeholder] while loading and renders
 * nothing when off / no fill.
 */
@Composable
public fun NativeAd(
    placement: AdPlacement,
    modifier: Modifier = Modifier,
    template: NativeTemplate = NativeTemplate.Medium,
    binder: NativeAdBinder = NativeAdBinder.Default,
    placeholder: (@Composable () -> Unit)? = null,
) {
    val config by AdKit.config.collectAsStateWithLifecycle()
    val canRequest by AdKit.canRequestAds.collectAsStateWithLifecycle()
    val unit = config.resolve(placement)
    if (unit == null || !canRequest) return
    androidx.compose.runtime.key(unit, template) {
        var state by remember { mutableStateOf(AdViewState.LOADING) }
        val empty = state == AdViewState.FAILED || state == AdViewState.DISABLED
        if (!empty) Box(modifier) {
            AndroidView(
                factory = { context ->
                    AdNativeView(context).also {
                        it.onStateChange = { s -> state = s }
                        it.load(placement, template, binder)
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                onRelease = {
                    it.onStateChange = null
                    it.destroy()
                },
            )
            if (state == AdViewState.LOADING && placeholder != null) placeholder()
        }
    }
}

/**
 * A loading dialog whose content is Compose. Pass the result as `loadingDialog` to
 * `AdKit.interstitial.show` / `AdKit.rewarded.show`. The content gets the whole window: draw your
 * own scrim / card. Call it inside composition so it inherits the app's theme (CompositionLocals).
 */
@Composable
public fun rememberComposeLoadingDialog(content: @Composable () -> Unit): AdLoadingDialog {
    val parent = rememberCompositionContext()
    return remember(parent, content) { composeLoadingDialog(parent, content) }
}

/** Same as [rememberComposeLoadingDialog], usable outside composition (no theme inheritance unless [parent] given). */
public fun composeLoadingDialog(
    parent: CompositionContext? = null,
    content: @Composable () -> Unit,
): AdLoadingDialog = AdLoadingDialog.view { activity, _ ->
    ComposeView(activity).apply {
        if (parent != null) setParentCompositionContext(parent)
        setContent(content)
    }
}

/** The hosting Activity, for `AdKit.interstitial.show(activity, …)` from a click handler. */
@Composable
public fun rememberActivity(): Activity {
    val context = LocalContext.current
    return remember(context) { requireNotNull(context.findActivity()) { "No Activity in this composition" } }
}
