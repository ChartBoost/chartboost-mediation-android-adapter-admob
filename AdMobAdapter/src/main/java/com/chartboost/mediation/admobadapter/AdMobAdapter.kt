/*
 * Copyright 2022-2023 Chartboost, Inc.
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE file.
 */

package com.chartboost.mediation.admobadapter

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.util.Size
import android.view.View.GONE
import android.view.View.VISIBLE
import com.chartboost.heliumsdk.domain.*
import com.chartboost.heliumsdk.domain.AdFormat
import com.chartboost.heliumsdk.utils.PartnerLogController
import com.chartboost.heliumsdk.utils.PartnerLogController.PartnerAdapterEvents.*
import com.google.ads.mediation.admob.AdMobAdapter
import com.google.android.gms.ads.*
import com.google.android.gms.ads.initialization.AdapterStatus
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback
import com.google.android.gms.ads.rewarded.RewardedAd
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback
import com.google.android.gms.ads.rewardedinterstitial.RewardedInterstitialAd
import com.google.android.gms.ads.rewardedinterstitial.RewardedInterstitialAdLoadCallback
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers.Main
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

class AdMobAdapter : PartnerAdapter {
    companion object {
        /**
         * List containing device IDs to be set for enabling AdMob test ads. It can be populated at
         * any time and will take effect for the next ad request. Remember to empty this list or
         * stop setting it before releasing your app.
         */
        public var testDeviceIds = listOf<String>()
            set(value) {
                field = value
                PartnerLogController.log(
                    CUSTOM,
                    "AdMob test device ID(s) to be set: ${
                        if (value.isEmpty()) "none"
                        else value.joinToString()
                    }"
                )
                MobileAds.setRequestConfiguration(
                    RequestConfiguration.Builder().setTestDeviceIds(value).build()
                )
            }

        /**
         * Key for parsing whether the waterfall is hybrid (i.e. containing both AdMob and Google Bidding).
         */
        private const val IS_HYBRID_SETUP = "is_hybrid_setup"
    }

    /**
     * A map of Chartboost Mediation's listeners for the corresponding load identifier.
     */
    private val listeners = mutableMapOf<String, PartnerAdListener>()

    /**
     * Indicate whether GDPR currently applies to the user.
     */
    private var gdprApplies: Boolean? = null

    /**
     * Indicate whether the user has consented to allowing personalized ads when GDPR applies.
     */
    private var allowPersonalizedAds = false

    /**
     * Indicate whether the user has given consent per CCPA.
     */
    private var ccpaPrivacyString: String? = null

    /**
     * Get the Google Mobile Ads SDK version.
     *
     * Note that the version string will be in the format of afma-sdk-a-v221908999.214106000.1.
     */
    override val partnerSdkVersion: String
        get() = MobileAds.getVersion().toString()

    /**
     * Get the AdMob adapter version.
     *
     * You may version the adapter using any preferred convention, but it is recommended to apply the
     * following format if the adapter will be published by Chartboost Mediation:
     *
     * Chartboost Mediation.Partner.Adapter
     *
     * "Chartboost Mediation" represents the Chartboost Mediation SDK’s major version that is compatible with this adapter. This must be 1 digit.
     * "Partner" represents the partner SDK’s major.minor.patch.x (where x is optional) version that is compatible with this adapter. This can be 3-4 digits.
     * "Adapter" represents this adapter’s version (starting with 0), which resets to 0 when the partner SDK’s version changes. This must be 1 digit.
     */
    override val adapterVersion: String
        get() = BuildConfig.CHARTBOOST_MEDIATION_ADMOB_ADAPTER_VERSION

    /**
     * Get the partner name for internal uses.
     */
    override val partnerId: String
        get() = "admob"

    /**
     * Get the partner name for external uses.
     */
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
        PartnerLogController.log(SETUP_STARTED)

        // Since Chartboost Mediation is the mediator, no need to initialize AdMob's partner SDKs.
        // https://developers.google.com/android/reference/com/google/android/gms/ads/MobileAds?hl=en#disableMediationAdapterInitialization(android.content.Context)
        MobileAds.disableMediationAdapterInitialization(context)

        return suspendCancellableCoroutine { continuation ->
            fun resumeOnce(result: Result<Unit>) {
                if (continuation.isActive) {
                    continuation.resume(result)
                }
            }

            MobileAds.initialize(context) { status ->
                resumeOnce(getInitResult(status.adapterStatusMap[MobileAds::class.java.name]))
            }
        }
    }

    /**
     * Notify the Google Mobile Ads SDK of the GDPR applicability and consent status.
     *
     * @param context The current [Context].
     * @param applies True if GDPR applies, false otherwise.
     * @param gdprConsentStatus The user's GDPR consent status.
     */
    override fun setGdpr(
        context: Context,
        applies: Boolean?,
        gdprConsentStatus: GdprConsentStatus
    ) {
        PartnerLogController.log(
            when (applies) {
                true -> GDPR_APPLICABLE
                false -> GDPR_NOT_APPLICABLE
                else -> GDPR_UNKNOWN
            }
        )

        PartnerLogController.log(
            when (gdprConsentStatus) {
                GdprConsentStatus.GDPR_CONSENT_UNKNOWN -> GDPR_CONSENT_UNKNOWN
                GdprConsentStatus.GDPR_CONSENT_GRANTED -> GDPR_CONSENT_GRANTED
                GdprConsentStatus.GDPR_CONSENT_DENIED -> GDPR_CONSENT_DENIED
            }
        )

        this.gdprApplies = applies

        if (applies == true) {
            allowPersonalizedAds = gdprConsentStatus == GdprConsentStatus.GDPR_CONSENT_GRANTED
        }
    }

    /**
     * Save the current CCPA privacy String to be used later.
     *
     * @param context The current [Context].
     * @param hasGrantedCcpaConsent True if the user has granted CCPA consent, false otherwise.
     * @param privacyString The CCPA privacy String.
     */
    override fun setCcpaConsent(
        context: Context,
        hasGrantedCcpaConsent: Boolean,
        privacyString: String
    ) {
        PartnerLogController.log(
            if (hasGrantedCcpaConsent) CCPA_CONSENT_GRANTED
            else CCPA_CONSENT_DENIED
        )

        ccpaPrivacyString = privacyString
    }

    /**
     * Notify AdMob of the COPPA subjectivity.
     *
     * @param context The current [Context].
     * @param isSubjectToCoppa True if the user is subject to COPPA, false otherwise.
     */
    override fun setUserSubjectToCoppa(context: Context, isSubjectToCoppa: Boolean) {
        PartnerLogController.log(
            if (isSubjectToCoppa) COPPA_SUBJECT
            else COPPA_NOT_SUBJECT
        )

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
    ): Map<String, String> {
        PartnerLogController.log(BIDDER_INFO_FETCH_STARTED)
        PartnerLogController.log(BIDDER_INFO_FETCH_SUCCEEDED)
        return emptyMap()
    }

    /**
     * Attempt to load an AdMob ad.
     *
     * @param context The current [Context].
     * @param request An [PartnerAdLoadRequest] instance containing relevant data for the current ad load call.
     * @param partnerAdListener A [PartnerAdListener] to notify Chartboost Mediation of ad events.
     *
     * @return Result.success(PartnerAd) if the ad was successfully loaded, Result.failure(Exception) otherwise.
     */
    override suspend fun load(
        context: Context,
        request: PartnerAdLoadRequest,
        partnerAdListener: PartnerAdListener
    ): Result<PartnerAd> {
        PartnerLogController.log(LOAD_STARTED)

        return when (request.format.key) {
            AdFormat.INTERSTITIAL.key -> loadInterstitialAd(
                context,
                request,
                partnerAdListener
            )
            AdFormat.REWARDED.key -> loadRewardedAd(
                context,
                request,
                partnerAdListener
            )
            AdFormat.BANNER.key, "adaptive_banner" -> loadBannerAd(
                context,
                request,
                partnerAdListener
            )
            else -> {
                if (request.format.key == "rewarded_interstitial") {
                    loadRewardedInterstitialAd(
                        context,
                        request,
                        partnerAdListener
                    )
                } else {
                    PartnerLogController.log(LOAD_FAILED)
                    Result.failure(ChartboostMediationAdException(ChartboostMediationError.CM_LOAD_FAILURE_UNSUPPORTED_AD_FORMAT))
                }
            }
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
        PartnerLogController.log(SHOW_STARTED)
        val listener = listeners.remove(partnerAd.request.identifier)

        return when (partnerAd.request.format.key) {
            AdFormat.BANNER.key, "adaptive_banner" -> showBannerAd(partnerAd)
            AdFormat.INTERSTITIAL.key -> showInterstitialAd(context, partnerAd, listener)
            AdFormat.REWARDED.key -> showRewardedAd(context, partnerAd, listener)
            else -> {
                if (partnerAd.request.format.key == "rewarded_interstitial") {
                    showRewardedInterstitialAd(context, partnerAd, listener)
                } else {
                    PartnerLogController.log(SHOW_FAILED)
                    Result.failure(ChartboostMediationAdException(ChartboostMediationError.CM_SHOW_FAILURE_UNSUPPORTED_AD_FORMAT))
                }
            }
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
        PartnerLogController.log(INVALIDATE_STARTED)
        listeners.remove(partnerAd.request.identifier)

        // Only invalidate banners as there are no explicit methods to invalidate the other formats.
        return when (partnerAd.request.format.key) {
            AdFormat.BANNER.key, "adaptive_banner" -> destroyBannerAd(partnerAd)
            else -> {
                PartnerLogController.log(INVALIDATE_SUCCEEDED)
                Result.success(partnerAd)
            }
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
                Result.success(PartnerLogController.log(SETUP_SUCCEEDED))
            } else {
                PartnerLogController.log(
                    SETUP_FAILED,
                    "Initialization state: ${it.initializationState}. Description: ${it.description}"
                )
                Result.failure(ChartboostMediationAdException(ChartboostMediationError.CM_INITIALIZATION_FAILURE_UNKNOWN))
            }
        } ?: run {
            PartnerLogController.log(SETUP_FAILED, "Initialization status is null.")
            Result.failure(ChartboostMediationAdException(ChartboostMediationError.CM_INITIALIZATION_FAILURE_UNKNOWN))
        }
    }

    /**
     * Attempt to load an AdMob banner on the main thread.
     *
     * @param context The current [Context].
     * @param request An [PartnerAdLoadRequest] instance containing relevant data for the current ad load call.
     */
    private suspend fun loadBannerAd(
        context: Context,
        request: PartnerAdLoadRequest,
        listener: PartnerAdListener
    ): Result<PartnerAd> {
        return suspendCancellableCoroutine { continuation ->
            fun resumeOnce(result: Result<PartnerAd>) {
                if (continuation.isActive) {
                    continuation.resume(result)
                }
            }

            CoroutineScope(Main).launch {
                val adview = AdView(context)
                val partnerAd = PartnerAd(
                    ad = adview,
                    details = mutableMapOf(),
                    request = request,
                )

                val adSize = getAdMobAdSize(context, request.size, request.format.key == "adaptive_banner")

                if(request.format.key == "adaptive_banner") {
                    (partnerAd.details as MutableMap).let {
                        it["banner_width_dips"] = "${adSize.width}"
                        it["banner_height_dips"] = "${adSize.height}"
                    }
                }

                adview.setAdSize(adSize)
                adview.adUnitId = request.partnerPlacement
                adview.loadAd(
                    buildRequest(
                        request.identifier,
                        getIsHybridSetup(request.partnerSettings)
                    )
                )
                adview.adListener = object : AdListener() {
                    override fun onAdImpression() {
                        PartnerLogController.log(DID_TRACK_IMPRESSION)
                        listener.onPartnerAdImpression(partnerAd)
                    }

                    override fun onAdLoaded() {
                        PartnerLogController.log(LOAD_SUCCEEDED)
                        resumeOnce(Result.success(partnerAd))
                    }

                    override fun onAdFailedToLoad(adError: LoadAdError) {
                        PartnerLogController.log(LOAD_FAILED, adError.message)
                        resumeOnce(
                            Result.failure(ChartboostMediationAdException(getHeliumError(adError.code)))
                        )
                    }

                    override fun onAdOpened() {
                        // NO-OP
                    }

                    override fun onAdClicked() {
                        PartnerLogController.log(DID_CLICK)
                        listener.onPartnerAdClicked(partnerAd)
                    }

                    override fun onAdClosed() {
                        // NO-OP. Ignore banner closes to help avoid auto-refresh issues.
                    }
                }
            }
        }
    }

    /**
     * Find the most appropriate AdMob ad size for the given screen area based on height.
     *
     * @param size The [Size] to parse for conversion.
     *
     * @return The AdMob ad size that best matches the given [Size].
     */
    private fun getAdMobAdSize(context: Context, size: Size?, isAdaptive: Boolean = false): AdSize {
        if(isAdaptive) {
            return AdSize.getCurrentOrientationAnchoredAdaptiveBannerAdSize(
                context, size?.width ?: AdSize.BANNER.width
            )
        }

        return size?.height?.let {
            when {
                it in 50 until 90 -> AdSize.BANNER
                it in 90 until 250 -> AdSize.LEADERBOARD
                it >= 250 -> AdSize.MEDIUM_RECTANGLE
                else -> AdSize.BANNER
            }
        } ?: AdSize.BANNER
    }

    /**
     * Attempt to load an AdMob interstitial on the main thread.
     *
     * @param context The current [Context].
     * @param request An [PartnerAdLoadRequest] instance containing data to load the ad with.
     * @param listener A [PartnerAdListener] to notify Chartboost Mediation of ad events.
     *
     * @return Result.success(PartnerAd) if the ad was successfully loaded, Result.failure(Exception) otherwise.
     */
    private suspend fun loadInterstitialAd(
        context: Context,
        request: PartnerAdLoadRequest,
        listener: PartnerAdListener
    ): Result<PartnerAd> {
        // Save the listener for later use.
        listeners[request.identifier] = listener

        return suspendCancellableCoroutine { continuation ->
            fun resumeOnce(result: Result<PartnerAd>) {
                if (continuation.isActive) {
                    continuation.resume(result)
                }
            }

            CoroutineScope(Main).launch {
                InterstitialAd.load(context,
                    request.partnerPlacement,
                    buildRequest(request.identifier, getIsHybridSetup(request.partnerSettings)),
                    object : InterstitialAdLoadCallback() {
                        override fun onAdLoaded(interstitialAd: InterstitialAd) {
                            PartnerLogController.log(LOAD_SUCCEEDED)
                            resumeOnce(
                                Result.success(
                                    PartnerAd(
                                        ad = interstitialAd,
                                        details = emptyMap(),
                                        request = request
                                    )
                                )
                            )
                        }

                        override fun onAdFailedToLoad(loadAdError: LoadAdError) {
                            PartnerLogController.log(LOAD_FAILED, loadAdError.message)
                            resumeOnce(
                                Result.failure(
                                    ChartboostMediationAdException(
                                        getHeliumError(
                                            loadAdError.code
                                        )
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
     * @param request The [PartnerAdLoadRequest] containing relevant data for the current ad load call.
     * @param listener A [PartnerAdListener] to notify Chartboost Mediation of ad events.
     *
     * @return Result.success(PartnerAd) if the ad was successfully loaded, Result.failure(Exception) otherwise.
     */
    private suspend fun loadRewardedAd(
        context: Context,
        request: PartnerAdLoadRequest,
        listener: PartnerAdListener
    ): Result<PartnerAd> {
        // Save the listener for later use.
        listeners[request.identifier] = listener

        return suspendCancellableCoroutine { continuation ->
            fun resumeOnce(result: Result<PartnerAd>) {
                if (continuation.isActive) {
                    continuation.resume(result)
                }
            }

            CoroutineScope(Main).launch {
                RewardedAd.load(context,
                    request.partnerPlacement,
                    buildRequest(request.identifier, getIsHybridSetup(request.partnerSettings)),
                    object : RewardedAdLoadCallback() {
                        override fun onAdLoaded(rewardedAd: RewardedAd) {
                            PartnerLogController.log(LOAD_SUCCEEDED)
                            resumeOnce(
                                Result.success(
                                    PartnerAd(
                                        ad = rewardedAd,
                                        details = emptyMap(),
                                        request = request
                                    )
                                )
                            )
                        }

                        override fun onAdFailedToLoad(loadAdError: LoadAdError) {
                            PartnerLogController.log(LOAD_FAILED, loadAdError.message)
                            resumeOnce(
                                Result.failure(
                                    ChartboostMediationAdException(getHeliumError(loadAdError.code))
                                )
                            )
                        }
                    }
                )
            }
        }
    }

    /**
     * Attempt to load an AdMob rewarded interstitial ad on the main thread.
     *
     * @param context The current [Context].
     * @param request The [PartnerAdLoadRequest] containing relevant data for the current ad load call.
     * @param listener A [PartnerAdListener] to notify Chartboost Mediation of ad events.
     *
     * @return Result.success(PartnerAd) if the ad was successfully loaded, Result.failure(Exception) otherwise.
     */
    private suspend fun loadRewardedInterstitialAd(
        context: Context,
        request: PartnerAdLoadRequest,
        listener: PartnerAdListener
    ): Result<PartnerAd> {
        // Save the listener for later use.
        listeners[request.identifier] = listener

        return suspendCancellableCoroutine { continuation ->
            fun resumeOnce(result: Result<PartnerAd>) {
                if (continuation.isActive) {
                    continuation.resume(result)
                }
            }

            CoroutineScope(Main).launch {
                RewardedInterstitialAd.load(context,
                    request.partnerPlacement,
                    buildRequest(request.identifier, getIsHybridSetup(request.partnerSettings)),
                    object : RewardedInterstitialAdLoadCallback() {
                        override fun onAdLoaded(rewardedInterstitialAd: RewardedInterstitialAd) {
                            PartnerLogController.log(LOAD_SUCCEEDED)
                            resumeOnce(
                                Result.success(
                                    PartnerAd(
                                        ad = rewardedInterstitialAd,
                                        details = emptyMap(),
                                        request = request
                                    )
                                )
                            )
                        }

                        override fun onAdFailedToLoad(loadAdError: LoadAdError) {
                            PartnerLogController.log(LOAD_FAILED, loadAdError.message)
                            resumeOnce(
                                Result.failure(
                                    ChartboostMediationAdException(getHeliumError(loadAdError.code))
                                )
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

            PartnerLogController.log(SHOW_SUCCEEDED)
            Result.success(partnerAd)
        } ?: run {
            PartnerLogController.log(SHOW_FAILED, "Banner ad is null.")
            Result.failure(ChartboostMediationAdException(ChartboostMediationError.CM_SHOW_FAILURE_AD_NOT_FOUND))
        }
    }

    /**
     * Attempt to show an AdMob interstitial ad on the main thread.
     *
     * @param context The current [Context].
     * @param partnerAd The [PartnerAd] object containing the AdMob ad to be shown.
     * @param listener A [PartnerAdListener] to notify Helium of ad events.
     *
     * @return Result.success(PartnerAd) if the ad was successfully shown, Result.failure(Exception) otherwise.
     */
    private suspend fun showInterstitialAd(
        context: Context,
        partnerAd: PartnerAd,
        listener: PartnerAdListener?
    ): Result<PartnerAd> {
        if (context !is Activity) {
            PartnerLogController.log(SHOW_FAILED, "Context is not an Activity.")
            return Result.failure(ChartboostMediationAdException(ChartboostMediationError.CM_SHOW_FAILURE_ACTIVITY_NOT_FOUND))
        }

        return suspendCancellableCoroutine { continuation ->
            fun resumeOnce(result: Result<PartnerAd>) {
                if (continuation.isActive) {
                    continuation.resume(result)
                }
            }

            partnerAd.ad?.let { ad ->
                CoroutineScope(Main).launch {
                    val interstitial = ad as InterstitialAd

                    interstitial.fullScreenContentCallback =
                        object : FullScreenContentCallback() {
                            override fun onAdImpression() {
                                PartnerLogController.log(DID_TRACK_IMPRESSION)
                                listener?.onPartnerAdImpression(partnerAd)
                                    ?: PartnerLogController.log(
                                        CUSTOM,
                                        "Unable to fire onPartnerAdImpression for AdMob adapter."
                                    )
                            }

                            override fun onAdFailedToShowFullScreenContent(adError: AdError) {
                                PartnerLogController.log(SHOW_FAILED, adError.message)
                                resumeOnce(
                                    Result.failure(
                                        ChartboostMediationAdException(
                                            getHeliumError(
                                                adError.code
                                            )
                                        )
                                    )
                                )
                            }

                            override fun onAdShowedFullScreenContent() {
                                PartnerLogController.log(SHOW_SUCCEEDED)
                                resumeOnce(Result.success(partnerAd))
                            }

                            override fun onAdClicked() {
                                PartnerLogController.log(DID_CLICK)
                                listener?.onPartnerAdClicked(partnerAd)
                                    ?: PartnerLogController.log(
                                        CUSTOM,
                                        "Unable to fire onPartnerAdClicked for AdMob adapter."
                                    )
                            }

                            override fun onAdDismissedFullScreenContent() {
                                PartnerLogController.log(DID_DISMISS)
                                listener?.onPartnerAdDismissed(partnerAd, null)
                                    ?: PartnerLogController.log(
                                        CUSTOM,
                                        "Unable to fire onPartnerAdDismissed for AdMob adapter."
                                    )
                            }
                        }
                    interstitial.show(context)
                }
            } ?: run {
                PartnerLogController.log(SHOW_FAILED, "Ad is null.")
                resumeOnce(
                    Result.failure(
                        ChartboostMediationAdException(
                            ChartboostMediationError.CM_SHOW_FAILURE_AD_NOT_FOUND
                        )
                    )
                )
            }
        }
    }

    /**
     * Attempt to show an AdMob rewarded ad on the main thread.
     *
     * @param context The current [Context].
     * @param partnerAd The [PartnerAd] object containing the AdMob ad to be shown.
     * @param listener A [PartnerAdListener] to notify Chartboost Mediation of ad events.
     *
     * @return Result.success(PartnerAd) if the ad was successfully shown, Result.failure(Exception) otherwise.
     */
    private suspend fun showRewardedAd(
        context: Context,
        partnerAd: PartnerAd,
        listener: PartnerAdListener?
    ): Result<PartnerAd> {
        if (context !is Activity) {
            PartnerLogController.log(SHOW_FAILED, "Context is not an Activity.")
            return Result.failure(ChartboostMediationAdException(ChartboostMediationError.CM_SHOW_FAILURE_ACTIVITY_NOT_FOUND))
        }

        return suspendCancellableCoroutine { continuation ->
            fun resumeOnce(result: Result<PartnerAd>) {
                if (continuation.isActive) {
                    continuation.resume(result)
                }
            }

            partnerAd.ad?.let { ad ->
                CoroutineScope(Main).launch {
                    val rewardedAd = ad as RewardedAd

                    rewardedAd.fullScreenContentCallback = object : FullScreenContentCallback() {
                        override fun onAdImpression() {
                            PartnerLogController.log(DID_TRACK_IMPRESSION)
                            listener?.onPartnerAdImpression(partnerAd) ?: PartnerLogController.log(
                                CUSTOM,
                                "Unable to fire onPartnerAdImpression for AdMob adapter."
                            )
                        }

                        override fun onAdFailedToShowFullScreenContent(adError: AdError) {
                            PartnerLogController.log(SHOW_FAILED, adError.message)
                            resumeOnce(
                                Result.failure(ChartboostMediationAdException(getHeliumError(adError.code)))
                            )
                        }

                        override fun onAdShowedFullScreenContent() {
                            PartnerLogController.log(SHOW_SUCCEEDED)
                            resumeOnce(Result.success(partnerAd))
                        }

                        override fun onAdClicked() {
                            PartnerLogController.log(DID_CLICK)
                            listener?.onPartnerAdClicked(partnerAd)
                                ?: PartnerLogController.log(
                                    CUSTOM,
                                    "Unable to fire onPartnerAdClicked for AdMob adapter."
                                )
                        }

                        override fun onAdDismissedFullScreenContent() {
                            PartnerLogController.log(DID_DISMISS)
                            listener?.onPartnerAdDismissed(partnerAd, null)
                                ?: PartnerLogController.log(
                                    CUSTOM,
                                    "Unable to fire onPartnerAdDismissed for AdMob adapter."
                                )
                        }
                    }

                    rewardedAd.show(context) {
                        PartnerLogController.log(DID_REWARD)
                        listener?.onPartnerAdRewarded(partnerAd)
                            ?: PartnerLogController.log(
                                CUSTOM,
                                "Unable to fire onPartnerAdRewarded for AdMob adapter."
                            )
                    }
                }
            } ?: run {
                PartnerLogController.log(SHOW_FAILED, "Ad is null.")
                resumeOnce(
                    Result.failure(
                        ChartboostMediationAdException(
                            ChartboostMediationError.CM_SHOW_FAILURE_AD_NOT_FOUND
                        )
                    )
                )
            }
        }
    }

    /**
     * Attempt to show an AdMob rewarded interstitial ad on the main thread.
     *
     * @param context The current [Context].
     * @param partnerAd The [PartnerAd] object containing the AdMob ad to be shown.
     * @param listener A [PartnerAdListener] to notify Chartboost Mediation of ad events.
     *
     * @return Result.success(PartnerAd) if the ad was successfully shown, Result.failure(Exception) otherwise.
     */
    private suspend fun showRewardedInterstitialAd(
        context: Context,
        partnerAd: PartnerAd,
        listener: PartnerAdListener?
    ): Result<PartnerAd> {
        if (context !is Activity) {
            PartnerLogController.log(SHOW_FAILED, "Context is not an Activity.")
            return Result.failure(ChartboostMediationAdException(ChartboostMediationError.CM_SHOW_FAILURE_ACTIVITY_NOT_FOUND))
        }

        return suspendCancellableCoroutine { continuation ->
            fun resumeOnce(result: Result<PartnerAd>) {
                if (continuation.isActive) {
                    continuation.resume(result)
                }
            }

            partnerAd.ad?.let { ad ->
                CoroutineScope(Main).launch {
                    val rewardedInterstitialAd = ad as RewardedInterstitialAd

                    rewardedInterstitialAd.fullScreenContentCallback =
                        object : FullScreenContentCallback() {
                            override fun onAdImpression() {
                                PartnerLogController.log(DID_TRACK_IMPRESSION)
                                listener?.onPartnerAdImpression(partnerAd)
                                    ?: PartnerLogController.log(
                                        CUSTOM,
                                        "Unable to fire onPartnerAdImpression for AdMob adapter."
                                    )
                            }

                            override fun onAdFailedToShowFullScreenContent(adError: AdError) {
                                PartnerLogController.log(SHOW_FAILED, adError.message)
                                resumeOnce(
                                    Result.failure(
                                        ChartboostMediationAdException(
                                            getHeliumError(
                                                adError.code
                                            )
                                        )
                                    )
                                )
                            }

                            override fun onAdShowedFullScreenContent() {
                                PartnerLogController.log(SHOW_SUCCEEDED)
                                resumeOnce(Result.success(partnerAd))
                            }

                            override fun onAdClicked() {
                                PartnerLogController.log(DID_CLICK)
                                listener?.onPartnerAdClicked(partnerAd)
                                    ?: PartnerLogController.log(
                                        CUSTOM,
                                        "Unable to fire onPartnerAdClicked for AdMob adapter."
                                    )
                            }

                            override fun onAdDismissedFullScreenContent() {
                                PartnerLogController.log(DID_DISMISS)
                                listener?.onPartnerAdDismissed(partnerAd, null)
                                    ?: PartnerLogController.log(
                                        CUSTOM,
                                        "Unable to fire onPartnerAdDismissed for AdMob adapter."
                                    )
                            }
                        }

                    rewardedInterstitialAd.show(context) {
                        PartnerLogController.log(DID_REWARD)
                        listener?.onPartnerAdRewarded(partnerAd)
                            ?: PartnerLogController.log(
                                CUSTOM,
                                "Unable to fire onPartnerAdRewarded for AdMob adapter."
                            )
                    }
                }
            } ?: run {
                PartnerLogController.log(SHOW_FAILED, "Ad is null.")
                resumeOnce(
                    Result.failure(
                        ChartboostMediationAdException(
                            ChartboostMediationError.CM_SHOW_FAILURE_AD_NOT_FOUND
                        )
                    )
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

                PartnerLogController.log(INVALIDATE_SUCCEEDED)
                Result.success(partnerAd)
            } else {
                PartnerLogController.log(INVALIDATE_FAILED, "Ad is not an AdView.")
                Result.failure(ChartboostMediationAdException(ChartboostMediationError.CM_INVALIDATE_FAILURE_WRONG_RESOURCE_TYPE))
            }
        } ?: run {
            PartnerLogController.log(INVALIDATE_FAILED, "Ad is null.")
            Result.failure(ChartboostMediationAdException(ChartboostMediationError.CM_INVALIDATE_FAILURE_AD_NOT_FOUND))
        }
    }

    /**
     * Build an AdMob ad request.
     *
     * @param identifier The unique identifier associated with the current ad load call.
     * @param isHybridSetup Whether the current waterfall contains both AdMob and Google Bidding.
     *
     * @return An AdMob [AdRequest] object.
     */
    private fun buildRequest(identifier: String, isHybridSetup: Boolean): AdRequest {
        val extras = buildPrivacyConsents()

        if (isHybridSetup) {
            // Requirement by Google for their debugging purposes
            extras.putString("placement_req_id", identifier)
            extras.putBoolean(IS_HYBRID_SETUP, isHybridSetup)
        }

        extras.putString("platform_name", "chartboost")

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
            if (gdprApplies == true && !allowPersonalizedAds) {
                putString("npa", "1")
            }

            ccpaPrivacyString?.let {
                if (it.isNotEmpty()) {
                    putString("IABUSPrivacy_String", it)
                }
            }
        }
    }

    /**
     * Parse partner-specific settings for whether this waterfall is a hybrid setup.
     *
     * @param settings The [PartnerAdLoadRequest.partnerSettings] map containing partner-specific settings.
     *
     * @return True if this waterfall is a hybrid setup, false otherwise.
     */
    private fun getIsHybridSetup(settings: Map<String, String>): Boolean {
        return settings[IS_HYBRID_SETUP]?.toBoolean() ?: false
    }

    /**
     * Convert a given AdMob error code into a [ChartboostMediationError].
     *
     * @param error The AdMob error code as an [Int].
     *
     * @return The corresponding [ChartboostMediationError].
     */
    private fun getHeliumError(error: Int) = when (error) {
        AdRequest.ERROR_CODE_APP_ID_MISSING -> ChartboostMediationError.CM_LOAD_FAILURE_PARTNER_NOT_INITIALIZED
        AdRequest.ERROR_CODE_INTERNAL_ERROR -> ChartboostMediationError.CM_INTERNAL_ERROR
        AdRequest.ERROR_CODE_INVALID_AD_STRING -> ChartboostMediationError.CM_LOAD_FAILURE_INVALID_AD_MARKUP
        AdRequest.ERROR_CODE_INVALID_REQUEST, AdRequest.ERROR_CODE_REQUEST_ID_MISMATCH -> ChartboostMediationError.CM_LOAD_FAILURE_INVALID_AD_REQUEST
        AdRequest.ERROR_CODE_NETWORK_ERROR -> ChartboostMediationError.CM_NO_CONNECTIVITY
        AdRequest.ERROR_CODE_NO_FILL -> ChartboostMediationError.CM_LOAD_FAILURE_NO_FILL
        else -> ChartboostMediationError.CM_PARTNER_ERROR
    }
}
