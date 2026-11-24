package com.banv.admob

import android.app.Activity
import android.content.Context
import com.google.android.ump.ConsentDebugSettings
import com.google.android.ump.ConsentInformation
import com.google.android.ump.ConsentRequestParameters
import com.google.android.ump.UserMessagingPlatform

/** Google UMP (GDPR / US-states) consent. Usually driven through [AdKit.gatherConsent]. */
public class AdConsent internal constructor() {

    internal fun info(context: Context): ConsentInformation =
        UserMessagingPlatform.getConsentInformation(context)

    public fun canRequestAds(context: Context): Boolean = info(context).canRequestAds()

    /** Show a "Privacy settings" entry in the app's settings when this is true. */
    public fun isPrivacyOptionsRequired(context: Context): Boolean =
        info(context).privacyOptionsRequirementStatus ==
            ConsentInformation.PrivacyOptionsRequirementStatus.REQUIRED

    public fun showPrivacyOptionsForm(activity: Activity, onDone: (errorMessage: String?) -> Unit = {}) {
        UserMessagingPlatform.showPrivacyOptionsForm(activity) { error -> onDone(error?.message) }
    }

    /** Debug only: forget the stored consent so the form shows again. */
    public fun reset(context: Context): Unit = info(context).reset()

    internal fun gather(
        activity: Activity,
        debugSettings: ConsentDebugSettings?,
        onDone: (canRequestAds: Boolean) -> Unit,
    ) {
        val info = info(activity)
        val params = ConsentRequestParameters.Builder()
            .apply { if (debugSettings != null) setConsentDebugSettings(debugSettings) }
            .build()
        info.requestConsentInfoUpdate(
            activity, params,
            {
                UserMessagingPlatform.loadAndShowConsentFormIfRequired(activity) {
                    onDone(info.canRequestAds())
                }
            },
            // Offline etc.: fall back to the consent stored from an earlier session.
            { onDone(info.canRequestAds()) },
        )
    }

    public companion object {
        /**
         * Debug settings that pretend the device is in the EEA so the form shows.
         * The hashed id is printed in logcat by UMP ("Use new ConsentDebugSettings.Builder().addTestDeviceHashedId(...)").
         */
        public fun debugEea(context: Context, vararg testDeviceHashedIds: String): ConsentDebugSettings =
            ConsentDebugSettings.Builder(context)
                .setDebugGeography(ConsentDebugSettings.DebugGeography.DEBUG_GEOGRAPHY_EEA)
                .apply { testDeviceHashedIds.forEach { addTestDeviceHashedId(it) } }
                .build()
    }
}
