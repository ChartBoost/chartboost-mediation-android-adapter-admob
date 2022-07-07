package com.chartboost.heliumsdk.admobadapter

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.util.Size
import android.view.View.GONE
import android.view.View.VISIBLE
import com.chartboost.helium.admobadapter.BuildConfig
import com.chartboost.heliumsdk.domain.*
import com.chartboost.heliumsdk.domain.AdFormat
import com.chartboost.heliumsdk.utils.LogController
import com.google.ads.mediation.admob.AdMobAdapter
import com.google.android.gms.ads.*
import com.google.android.gms.ads.initialization.AdapterStatus
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback
import com.google.android.gms.ads.rewarded.RewardedAd
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers.Main
import kotlinx.coroutines.launch
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class AdMobAdapter : PartnerAdapter {
    /**
     * A map of Helium's listeners for the corresponding Helium placements.
     */
    private val listeners = mutableMapOf<String, PartnerAdListener>()

    /**
     * Indicate whether GDPR currently applies to the user.
     */
    private var gdprApplies: Boolean? = null

    /**
     * Indicate whether the user has consented to allowing personalized ads.
     */
    private var allowPersonalizedAds = false

    /**
     * Indicate whether the user has given consent per CCPA.
     */
    private var ccpaPrivacyString: String? = null

    override val partnerSdkVersion: String
        get() = MobileAds.getVersionString()

    override val adapterVersion: String
        get() = BuildConfig.VERSION_NAME

    override val partnerId: String
        get() = "AdMob"

    override val partnerDisplayName: String
        get() = "AdMob"

    /**
     * Initialize the Google Mobile Ads SDK so that it is ready to request ads.
     *
     * @param context The current [Context].
     * @param partnerConfiguration Configuration object containing relevant data to initialize AdMob.
     */
    override suspend fun setUp(
        context: Context,
        partnerConfiguration: PartnerConfiguration
    ): Result<Unit> {
        // Since Helium is the mediator, no need to initialize AdMob's partner SDKs.
        // https://developers.google.com/android/reference/com/google/android/gms/ads/MobileAds?hl=en#disableMediationAdapterInitialization(android.content.Context)
        MobileAds.disableMediationAdapterInitialization(context)

        return suspendCoroutine { continuation ->
            MobileAds.initialize(context) { status ->
                continuation.resume(getInitResult(status.adapterStatusMap[MobileAds::class.java.name]))
            }
        }
    }

    /**
     * Save the current GDPR applicability state for later use.
     *
     * @param gdprApplies The current GDPR applicability state.
     */
    override fun setGdprApplies(gdprApplies: Boolean) {
        this.gdprApplies = gdprApplies
    }

    /**
     * Get whether to allow personalized ads based on the user's GDPR consent status.
     *
     * @param gdprConsentStatus The user's current GDPR consent status.
     */
    override fun setGdprConsentStatus(gdprConsentStatus: GdprConsentStatus) {
        if (gdprApplies == true) {
            allowPersonalizedAds = gdprConsentStatus == GdprConsentStatus.GDPR_CONSENT_GRANTED
        }
    }

    /**
     * Save the current CCPA privacy String to be used later.
     *
     * @param privacyString The CCPA privacy String.
     */
    override fun setCcpaPrivacyString(privacyString: String?) {
        ccpaPrivacyString = privacyString
    }

    /**
     * Notify AdMob of the COPPA subjectivity.
     */
    override fun setUserSubjectToCoppa(isSubjectToCoppa: Boolean) {
        MobileAds.setRequestConfiguration(
            MobileAds.getRequestConfiguration().toBuilder()
                .setTagForChildDirectedTreatment(
                    if (isSubjectToCoppa) {
                        RequestConfiguration.TAG_FOR_CHILD_DIRECTED_TREATMENT_TRUE
                    } else {
                        RequestConfiguration.TAG_FOR_CHILD_DIRECTED_TREATMENT_FALSE
                    }
                ).build()
        )
    }

    /**
     * Get a bid token if network bidding is supported.
     *
     * @param context The current [Context].
     * @param request The [PreBidRequest] instance containing relevant data for the current bid request.
     *
     * @return A Map of biddable token Strings.
     */
    override suspend fun fetchBidderInformation(
        context: Context,
        request: PreBidRequest
    ): Map<String, String> = emptyMap()

    /**
     * Attempt to load an AdMob ad.
     *
     * @param context The current [Context].
     * @param request An [AdLoadRequest] instance containing relevant data for the current ad load call.
     * @param partnerAdListener A [PartnerAdListener] to notify Helium of ad events.
     *
     * @return Result.success(PartnerAd) if the ad was successfully loaded, Result.failure(Exception) otherwise.
     */
    override suspend fun load(
        context: Context,
        request: AdLoadRequest,
        partnerAdListener: PartnerAdListener
    ): Result<PartnerAd> {
        // Save the listener for later use.
        listeners[request.heliumPlacement] = partnerAdListener

        return when (request.format) {
            AdFormat.INTERSTITIAL -> loadInterstitial(
                context,
                request
            )
            AdFormat.REWARDED -> loadRewarded(
                context,
                request
            )
            AdFormat.BANNER -> loadBanner(
                context,
                request
            )
        }
    }

    /**
     * Attempt to show the currently loaded AdMob ad.
     *
     * @param context The current [Context]
     * @param partnerAd The [PartnerAd] object containing the AdMob ad to be shown.
     *
     * @return Result.success(PartnerAd) if the ad was successfully shown, Result.failure(Exception) otherwise.
     */
    override suspend fun show(context: Context, partnerAd: PartnerAd): Result<PartnerAd> {
        return when (partnerAd.request.format) {
            AdFormat.BANNER -> showBannerAd(partnerAd)
            AdFormat.INTERSTITIAL -> showInterstitialAd(context, partnerAd)
            AdFormat.REWARDED -> showRewardedAd(context, partnerAd)
        }
    }

    /**
     * Discard unnecessary AdMob ad objects and release resources.
     *
     * @param partnerAd The [PartnerAd] object containing the AdMob ad to be discarded.
     *
     * @return Result.success(PartnerAd) if the ad was successfully discarded, Result.failure(Exception) otherwise.
     */
    override suspend fun invalidate(partnerAd: PartnerAd): Result<PartnerAd> {
        listeners.remove(partnerAd.request.heliumPlacement)

        // Only invalidate banners as there are no explicit methods to invalidate the other formats.
        return when (partnerAd.request.format) {
            AdFormat.BANNER -> destroyBannerAd(partnerAd)
            else -> Result.success(partnerAd)
        }
    }

    /**
     * Get a [Result] containing the initialization result of the Google Mobile Ads SDK.
     *
     * @param status The initialization status of the Google Mobile Ads SDK.
     *
     * @return A [Result] object containing details about the initialization result.
     */
    private fun getInitResult(status: AdapterStatus?): Result<Unit> {
        return status?.let { it ->
            if (it.initializationState == AdapterStatus.State.READY) {
                Result.success(LogController.i("AdMob successfully initialized."))
            } else {
                Result.failure(
                    Error(
                        "AdMob failed to initialize. Initialization State: " +
                                "$it.initializationState. Description: " +
                                "$it.description"
                    )
                )
            }
        } ?: run {
            Result.failure(
                Error("AdMob failed to initialize. Initialization status is null.")
            )
        }
    }

    /**
     * Attempt to load an AdMob banner on the main thread.
     *
     * @param context The current [Context].
     * @param request An [AdLoadRequest] instance containing relevant data for the current ad load call.
     */
    private suspend fun loadBanner(
        context: Context,
        request: AdLoadRequest
    ): Result<PartnerAd> {
        return suspendCoroutine { continuation ->
            CoroutineScope(Main).launch {
                val listener = listeners[request.heliumPlacement]
                val adview = AdView(context)
                val partnerAd = PartnerAd(
                    ad = adview,
                    inlineView = null,
                    details = emptyMap(),
                    request = request,
                )

                adview.adSize = heliumToAdMobBannerSize(request.size)
                adview.adUnitId = request.partnerPlacement
                adview.loadAd(buildRequest(request.identifier))
                adview.adListener = object : AdListener() {
                    override fun onAdImpression() {
                        listener?.onPartnerAdImpression(partnerAd) ?: LogController.d(
                            "Unable to fire onPartnerAdImpression for AdMob adapter."
                        )

                        continuation.resume(Result.success(partnerAd))
                    }

                    override fun onAdLoaded() {
                        continuation.resume(Result.success(partnerAd))
                    }

                    override fun onAdFailedToLoad(adError: LoadAdError) {
                        continuation.resume(Result.failure(Exception("Failed to load banner ad: ${adError.message}")))
                    }

                    override fun onAdOpened() {
                        // NO-OP
                    }

                    override fun onAdClicked() {
                        listener?.onPartnerAdClicked(partnerAd) ?: LogController.d(
                            "Unable to fire onPartnerAdClicked for AdMob adapter."
                        )
                    }

                    override fun onAdClosed() {
                        // NO-OP. Ignore banner closes to help avoid auto-refresh issues.
                    }
                }
            }
        }
    }

    private fun heliumToAdMobBannerSize(size: Size?): AdSize {
        return when (size) {
            Size(320, 50) -> AdSize.BANNER
            Size(300, 250) -> AdSize.MEDIUM_RECTANGLE
            Size(728, 90) -> AdSize.LEADERBOARD
            else -> AdSize.BANNER
        }
    }

    /**
     * Attempt to load an AdMob interstitial on the main thread.
     *
     * @param context The current [Context].
     * @param request An [AdLoadRequest] instance containing data to load the ad with.
     */
    private suspend fun loadInterstitial(
        context: Context,
        request: AdLoadRequest
    ): Result<PartnerAd> {
        return suspendCoroutine { continuation ->
            CoroutineScope(Main).launch {
                InterstitialAd.load(context,
                    request.partnerPlacement,
                    buildRequest(request.identifier),
                    object : InterstitialAdLoadCallback() {
                        override fun onAdLoaded(interstitialAd: InterstitialAd) {
                            continuation.resume(
                                Result.success(
                                    PartnerAd(
                                        ad = interstitialAd,
                                        inlineView = null,
                                        details = emptyMap(),
                                        request = request
                                    )
                                )
                            )
                        }

                        override fun onAdFailedToLoad(loadAdError: LoadAdError) {
                            continuation.resume(
                                Result.failure(
                                    Exception(
                                        "Failed to load interstitial ad: ${loadAdError.message}"
                                    )
                                )
                            )
                        }
                    }
                )
            }
        }
    }

    /**
     * Attempt to load an AdMob rewarded ad on the main thread.
     *
     * @param context The current [Context].
     * @param request The [AdLoadRequest] containing relevant data for the current ad load call.
     *
     * @return Result.success(PartnerAd) if the ad was successfully loaded, Result.failure(Exception) otherwise.
     */
    private suspend fun loadRewarded(
        context: Context,
        request: AdLoadRequest
    ): Result<PartnerAd> {
        return suspendCoroutine { continuation ->
            CoroutineScope(Main).launch {
                RewardedAd.load(context, request.partnerPlacement, buildRequest(request.identifier),
                    object : RewardedAdLoadCallback() {
                        override fun onAdLoaded(rewardedAd: RewardedAd) {
                            continuation.resume(
                                Result.success(
                                    PartnerAd(
                                        ad = rewardedAd,
                                        inlineView = null,
                                        details = emptyMap(),
                                        request = request
                                    )
                                )
                            )
                        }

                        override fun onAdFailedToLoad(loadAdError: LoadAdError) {
                            continuation.resume(
                                Result.failure(Exception("Failed to load rewarded ad: ${loadAdError.message}"))
                            )
                        }
                    }
                )
            }
        }
    }

    /**
     * Attempted to show an AdMob banner ad on the main thread.
     *
     * @param partnerAd The [PartnerAd] object containing the AdMob ad to be shown.
     *
     * @return Result.success(PartnerAd) if the ad was successfully shown, Result.failure(Exception) otherwise.
     */
    private fun showBannerAd(partnerAd: PartnerAd): Result<PartnerAd> {
        return partnerAd.ad?.let {
            CoroutineScope(Main).launch {
                (it as AdView).visibility = VISIBLE
            }
            Result.success(partnerAd)
        } ?: run {
            Result.failure(Exception("Failed to show AdMob banner ad. Banner ad is null."))
        }
    }

    /**
     * Attempt to show an AdMob interstitial ad on the main thread.
     *
     * @param context The current [Context].
     * @param partnerAd The [PartnerAd] object containing the AdMob ad to be shown.
     *
     * @return Result.success(PartnerAd) if the ad was successfully shown, Result.failure(Exception) otherwise.
     */
    private suspend fun showInterstitialAd(
        context: Context,
        partnerAd: PartnerAd
    ): Result<PartnerAd> {
        if (context !is Activity) {
            return Result.failure(Exception("Failed to show AdMob interstitial ad. Context is not an Activity."))
        }

        return suspendCoroutine { continuation ->
            partnerAd.ad?.let { ad ->
                CoroutineScope(Main).launch {
                    val interstitial = ad as InterstitialAd
                    val listener = listeners[partnerAd.request.heliumPlacement]

                    interstitial.fullScreenContentCallback =
                        object : FullScreenContentCallback() {
                            override fun onAdImpression() {
                                listener?.onPartnerAdImpression(partnerAd) ?: LogController.d(
                                    "Unable to fire onPartnerAdImpression for AdMob adapter."
                                )
                                continuation.resume(Result.success(partnerAd))
                            }

                            override fun onAdFailedToShowFullScreenContent(adError: AdError) {
                                continuation.resume(
                                    Result.failure(
                                        Exception(
                                            "Failed to show AdMob " +
                                                    "interstitial ad. Error: ${adError.message}"
                                        )
                                    )
                                )
                            }

                            override fun onAdShowedFullScreenContent() {
                                continuation.resume(Result.success(partnerAd))
                            }

                            override fun onAdDismissedFullScreenContent() {
                                listener?.onPartnerAdDismissed(partnerAd, null)
                                    ?: LogController.d(
                                        "Unable to fire onPartnerAdDismissed for AdMob adapter."
                                    )
                            }
                        }
                    interstitial.show(context)
                }
            } ?: run {
                continuation.resume(Result.failure(Exception("Failed to show AdMob interstitial ad. Ad is null.")))
            }
        }
    }

    /**
     * Attempt to show an AdMob rewarded ad on the main thread.
     *
     * @param context The current [Context].
     * @param partnerAd The [PartnerAd] object containing the AdMob ad to be shown.
     *
     * @return Result.success(PartnerAd) if the ad was successfully shown, Result.failure(Exception) otherwise.
     */
    private suspend fun showRewardedAd(
        context: Context,
        partnerAd: PartnerAd
    ): Result<PartnerAd> {
        if (context !is Activity) {
            return Result.failure(Exception("Failed to show rewarded ad. Context is not an Activity."))
        }

        return suspendCoroutine { continuation ->
            partnerAd.ad?.let { ad ->
                CoroutineScope(Main).launch {
                    val rewardedAd = ad as RewardedAd
                    val listener = listeners[partnerAd.request.heliumPlacement]

                    rewardedAd.fullScreenContentCallback = object : FullScreenContentCallback() {
                        override fun onAdImpression() {
                            listener?.onPartnerAdImpression(partnerAd) ?: LogController.d(
                                "Unable to fire onPartnerAdImpression for AdMob adapter."
                            )
                            continuation.resume(Result.success(partnerAd))
                        }

                        override fun onAdFailedToShowFullScreenContent(adError: AdError) {
                            continuation.resume(
                                Result.failure(
                                    Exception(
                                        "Failed to show rewarded ad. Error: ${adError.message}"
                                    )
                                )
                            )
                        }

                        override fun onAdShowedFullScreenContent() {
                            continuation.resume(Result.success(partnerAd))
                        }

                        override fun onAdDismissedFullScreenContent() {
                            listener?.onPartnerAdDismissed(partnerAd, null) ?: LogController.d(
                                "Unable to fire onPartnerAdDismissed for AdMob adapter."
                            )
                        }
                    }

                    rewardedAd.show(context) { reward ->
                        listener?.onPartnerAdRewarded(partnerAd, Reward(reward.amount, reward.type))
                            ?: LogController.d(
                                "Unable to fire onPartnerAdRewarded for AdMob adapter."
                            )
                    }
                }
            } ?: run {
                continuation.resume(
                    Result.failure(Exception("Failed to show AdMob rewarded ad. Ad is null."))
                )
            }
        }
    }

    /**
     * Destroy the current AdMob banner ad.
     *
     * @param partnerAd The [PartnerAd] object containing the AdMob ad to be destroyed.
     *
     * @return Result.success(PartnerAd) if the ad was successfully destroyed, Result.failure(Exception) otherwise.
     */
    private fun destroyBannerAd(partnerAd: PartnerAd): Result<PartnerAd> {
        return partnerAd.ad?.let {
            if (it is AdView) {
                it.visibility = GONE
                it.destroy()
                Result.success(partnerAd)
            } else {
                Result.failure(
                    Exception("Failed to destroy AdMob banner ad. Ad is not an AdView.")
                )
            }
        } ?: Result.failure(
            Exception("Failed to destroy AdMob banner ad. Ad is null.")
        )
    }

    /**
     * Build an AdMob ad request.
     *
     * @param identifier The unique identifier associated with the current ad load call.
     *
     * @return An AdMob [AdRequest] object.
     */
    private fun buildRequest(identifier: String): AdRequest {
        val extras = buildPrivacyConsents()

        // Google expects this specific key to be set for debugging purposes.
        extras.putString("placement_req_id", identifier)

        return AdRequest.Builder()
            .addNetworkExtrasBundle(AdMobAdapter::class.java, extras)
            .build()
    }

    /**
     * Build a [Bundle] containing privacy settings for the current ad request for AdMob.
     *
     * @return A [Bundle] containing privacy settings for the current ad request for AdMob.
     */
    private fun buildPrivacyConsents(): Bundle {
        return Bundle().apply {
            if (!allowPersonalizedAds) {
                putString("npa", "1")
            }
            putString("IABUSPrivacy_String", ccpaPrivacyString)
        }
    }
}
