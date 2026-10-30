package com.mydigitaldevil.musicplayer.ads

import android.app.Activity
import android.content.Context
import com.google.android.ump.ConsentInformation
import com.google.android.ump.ConsentRequestParameters
import com.google.android.ump.UserMessagingPlatform
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.suspendCancellableCoroutine
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * Google UMP wrapper (GDPR / US state privacy messages). Ads are never requested before
 * [gather] reports that `canRequestAds()` is true.
 */
@Singleton
class ConsentManager @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val info: ConsentInformation = UserMessagingPlatform.getConsentInformation(context)

    val canRequestAds: Boolean get() = info.canRequestAds()

    /** Settings screen must show a "Privacy options" entry when this is true (policy requirement). */
    val isPrivacyOptionsRequired: Boolean
        get() = info.privacyOptionsRequirementStatus == ConsentInformation.PrivacyOptionsRequirementStatus.REQUIRED

    /** Refreshes consent status and shows the form if needed. Returns whether ads may be requested. */
    suspend fun gather(activity: Activity): Boolean = suspendCancellableCoroutine { cont ->
        val params = ConsentRequestParameters.Builder()
            .setTagForUnderAgeOfConsent(false)
            .build()
        info.requestConsentInfoUpdate(
            activity,
            params,
            {
                UserMessagingPlatform.loadAndShowConsentFormIfRequired(activity) {
                    if (cont.isActive) cont.resume(info.canRequestAds())
                }
            },
            {
                // Network failure: fall back to the previous session's consent, if any.
                if (cont.isActive) cont.resume(info.canRequestAds())
            },
        )
    }

    fun showPrivacyOptions(activity: Activity, onDismissed: () -> Unit = {}) {
        UserMessagingPlatform.showPrivacyOptionsForm(activity) { onDismissed() }
    }
}
