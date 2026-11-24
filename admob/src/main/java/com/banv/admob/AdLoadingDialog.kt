package com.banv.admob

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.annotation.LayoutRes
import androidx.activity.ComponentDialog
import androidx.annotation.StyleRes

/**
 * The screen shown while a full-screen ad is fetched or about to appear. The app supplies its own
 * UI: an XML layout ([layout]), any View ([view]), or Compose (`composeLoadingDialog` in admob-compose).
 * Implement the interface directly for full control (e.g. a DialogFragment).
 */
public fun interface AdLoadingDialog {
    /** Put the UI on screen; the library calls [Handle.dismiss] right before the ad appears or on failure. */
    public fun show(activity: Activity): Handle

    public fun interface Handle {
        public fun dismiss()
    }

    public companion object {
        /** No loading UI: the ad appears when ready, the app stays interactive meanwhile. */
        public val None: AdLoadingDialog = AdLoadingDialog { Handle {} }

        /** The library's own spinner + "Loading ad…" text (strings/colors overridable, prefix `admob_`). */
        public val Default: AdLoadingDialog get() = layout(R.layout.admob_loading_dialog)

        /** Full-screen, non-cancelable dialog inflating [layoutRes]. */
        public fun layout(
            @LayoutRes layoutRes: Int,
            @StyleRes theme: Int = R.style.AdMob_LoadingDialog,
        ): AdLoadingDialog = view(theme) { activity, parent ->
            LayoutInflater.from(activity).inflate(layoutRes, parent, false)
        }

        /** Full-screen, non-cancelable dialog hosting the view [factory] builds. */
        public fun view(
            @StyleRes theme: Int = R.style.AdMob_LoadingDialog,
            factory: (Activity, ViewGroup?) -> View,
        ): AdLoadingDialog = AdLoadingDialog { activity ->
            // ComponentDialog sets ViewTree owners, so a ComposeView works as content.
            val dialog = ComponentDialog(activity, theme).apply {
                setCancelable(false)
                setContentView(factory(activity, null))
                window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            }
            dialog.show()
            Handle { if (dialog.isShowing && !activity.isDestroyed) dialog.dismiss() }
        }
    }
}
