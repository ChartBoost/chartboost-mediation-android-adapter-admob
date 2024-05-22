/*
 * Copyright 2023-2024 Chartboost, Inc.
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
import com.chartboost.chartboostmediationsdk.domain.*
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.BIDDER_INFO_FETCH_STARTED
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.BIDDER_INFO_FETCH_SUCCEEDED
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.CUSTOM
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.DID_CLICK
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.DID_DISMISS
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.DID_REWARD
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.DID_TRACK_IMPRESSION
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.INVALIDATE_FAILED
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.INVALIDATE_STARTED
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.INVALIDATE_SUCCEEDED
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.LOAD_FAILED
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.LOAD_STARTED
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.LOAD_SUCCEEDED
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.SETUP_FAILED
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.SETUP_STARTED
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.SETUP_SUCCEEDED
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.SHOW_FAILED
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.SHOW_STARTED
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.SHOW_SUCCEEDED
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.USER_IS_NOT_UNDERAGE
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.USER_IS_UNDERAGE
import com.chartboost.core.consent.ConsentKey
import com.chartboost.core.consent.ConsentValue
import com.chartboost.mediation.admobadapter.AdMobAdapter.Companion.getChartboostMediationError
import com.google.ads.mediation.admob.AdMobAdapter
import com.google.android.gms.ads.*
import com.google.android.gms.ads.initialization.AdapterStatus
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback
import com.google.android.gms.ads.rewarded.RewardedAd
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback
import com.google.android.gms.ads.rewardedinterstitial.RewardedInterstitialAd
import com.google.android.gms.ads.rewardedinterstitial.RewardedInterstitialAdLoadCallback
import kotlinx.coroutines.*
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Dispatchers.Main
import java.lang.ref.WeakReference
import kotlin.coroutines.resume

class AdMobAdapter : PartnerAdapter {
    companion object {
        /**
         * Convert a given AdMob error code into a [ChartboostMediationError].
         *
         * @param error The AdMob error code as an [Int].
         *
         * @return The corresponding [ChartboostMediationError].
         */
        internal fun getChartboostMediationError(error: Int) =
            when (error) {
                AdRequest.ERROR_CODE_APP_ID_MISSING -> ChartboostMediationError.LoadError.PartnerNotInitialized
                AdRequest.ERROR_CODE_INTERNAL_ERROR -> ChartboostMediationError.OtherError.InternalError
                AdRequest.ERROR_CODE_INVALID_AD_STRING -> ChartboostMediationError.LoadError.InvalidAdMarkup
                AdRequest.ERROR_CODE_INVALID_REQUEST, AdRequest.ERROR_CODE_REQUEST_ID_MISMATCH -> ChartboostMediationError.LoadError.InvalidAdRequest
                AdRequest.ERROR_CODE_NETWORK_ERROR -> ChartboostMediationError.OtherError.NoConnectivity
                AdRequest.ERROR_CODE_NO_FILL -> ChartboostMediationError.LoadError.NoFill
                else -> ChartboostMediationError.OtherError.PartnerError
            }

        /**
         * Key for parsing whether the waterfall is hybrid (i.e. containing both AdMob and Google Bidding).
         */
        private const val IS_HYBRID_SETUP = "is_hybrid_setup"
    }

    /**
     * The AdMob adapter configuration.
     */
    override var configuration: PartnerAdapterConfiguration = AdMobAdapterConfiguration

    /**
     * A map of Chartboost Mediation's listeners for the corresponding load identifier.
     */
    private val listeners = mutableMapOf<String, PartnerAdListener>()

    /**
     * Initialize the Google Mobile Ads SDK so that it is ready to request ads.
     *
     * @param context The current [Context].
     * @param partnerConfiguration Configuration object containing relevant data to initialize AdMob.
     */
    override suspend fun setUp(
        context: Context,
        partnerConfiguration: PartnerConfiguration,
    ): Result<Map<String, Any>> = withContext(IO) {
        PartnerLogController.log(SETUP_STARTED)

            // Since Chartboost Mediation is the mediator, no need to initialize AdMob's partner SDKs.
            // https://developers.google.com/android/reference/com/google/android/gms/ads/MobileAds?hl=en#disableMediationAdapterInitialization(android.content.Context)
            //
            // There have been known ANRs when calling disableMediationAdapterInitialization() on the main thread.
            MobileAds.disableMediationAdapterInitialization(context)

        return@withContext suspendCancellableCoroutine { continuation ->
            fun resumeOnce(result: Result<Map<String, Any>>) {
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
     * Notify AdMob of the COPPA subjectivity.
     *
     * @param context The current [Context].
     * @param isUserUnderage True if the user is subject to COPPA, false otherwise.
     */
    override fun setIsUserUnderage(
        context: Context,
        isUserUnderage: Boolean,
    ) {
        PartnerLogController.log(
            if (isUserUnderage) {
                USER_IS_UNDERAGE
            } else {
                USER_IS_NOT_UNDERAGE
            },
        )

        // There have been known ANRs when calling setRequestConfiguration() on the main thread.
        CoroutineScope(IO).launch {
            MobileAds.setRequestConfiguration(
                MobileAds.getRequestConfiguration().toBuilder()
                    .setTagForChildDirectedTreatment(
                        if (isUserUnderage) {
                            RequestConfiguration.TAG_FOR_CHILD_DIRECTED_TREATMENT_TRUE
                        } else {
                            RequestConfiguration.TAG_FOR_CHILD_DIRECTED_TREATMENT_FALSE
                        },
                    ).build(),
            )
        }
    }

    /**
     * Get a bid token if network bidding is supported.
     *
     * @param context The current [Context].
     * @param request The [PartnerAdPreBidRequest] instance containing relevant data for the current bid request.
     *
     * @return A Map of biddable token Strings.
     */
    override suspend fun fetchBidderInformation(
        context: Context,
        request: PartnerAdPreBidRequest,
    ): Result<Map<String, String>> {
        PartnerLogController.log(BIDDER_INFO_FETCH_STARTED)
        PartnerLogController.log(BIDDER_INFO_FETCH_SUCCEEDED)
        return Result.success(emptyMap())
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
        partnerAdListener: PartnerAdListener,
    ): Result<PartnerAd> {
        PartnerLogController.log(LOAD_STARTED)

        return when (request.format) {
            PartnerAdFormats.INTERSTITIAL ->
                loadInterstitialAd(
                    context,
                    request,
                    partnerAdListener,
                )
            PartnerAdFormats.REWARDED ->
                loadRewardedAd(
                    context,
                    request,
                    partnerAdListener,
                )
            PartnerAdFormats.BANNER ->
                loadBannerAd(
                    context,
                    request,
                    partnerAdListener,
                )
            PartnerAdFormats.REWARDED_INTERSTITIAL ->
                loadRewardedInterstitialAd(
                    context,
                    request,
                    partnerAdListener,
                )
            else -> {
                PartnerLogController.log(LOAD_FAILED)
                Result.failure(ChartboostMediationAdException(ChartboostMediationError.LoadError.UnsupportedAdFormat))
            }
        }
    }

    /**
     * Attempt to show the currently loaded AdMob ad.
     *
     * @param activity The current [Activity]
     * @param partnerAd The [PartnerAd] object containing the AdMob ad to be shown.
     *
     * @return Result.success(PartnerAd) if the ad was successfully shown, Result.failure(Exception) otherwise.
     */
    override suspend fun show(
        activity: Activity,
        partnerAd: PartnerAd,
    ): Result<PartnerAd> {
        PartnerLogController.log(SHOW_STARTED)
        val listener = listeners.remove(partnerAd.request.identifier)

        return when (partnerAd.request.format) {
            PartnerAdFormats.BANNER -> showBannerAd(partnerAd)
            PartnerAdFormats.INTERSTITIAL -> showInterstitialAd(activity, partnerAd, listener)
            PartnerAdFormats.REWARDED -> showRewardedAd(activity, partnerAd, listener)
            PartnerAdFormats.REWARDED_INTERSTITIAL -> showRewardedInterstitialAd(activity, partnerAd, listener)
            else -> {
                PartnerLogController.log(SHOW_FAILED)
                Result.failure(ChartboostMediationAdException(ChartboostMediationError.ShowError.UnsupportedAdFormat))
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
        return when (partnerAd.request.format) {
            PartnerAdFormats.BANNER -> destroyBannerAd(partnerAd)
            else -> {
                PartnerLogController.log(INVALIDATE_SUCCEEDED)
                Result.success(partnerAd)
            }
        }
    }

    override fun setConsents(
        context: Context,
        consents: Map<ConsentKey, ConsentValue>,
        modifiedKeys: Set<ConsentKey>
    ) {
        // AdMob reads the TCF String directly.
    }

    /**
     * Get a [Result] containing the initialization result of the Google Mobile Ads SDK.
     *
     * @param status The initialization status of the Google Mobile Ads SDK.
     *
     * @return A [Result] object containing details about the initialization result.
     */
    private fun getInitResult(status: AdapterStatus?): Result<Map<String, Any>> {
        return status?.let {
            if (it.initializationState == AdapterStatus.State.READY) {
                PartnerLogController.log(SETUP_SUCCEEDED)
                Result.success(emptyMap())
            } else {
                PartnerLogController.log(
                    SETUP_FAILED,
                    "Initialization state: ${it.initializationState}. Description: ${it.description}",
                )
                Result.failure(ChartboostMediationAdException(ChartboostMediationError.InitializationError.Unknown))
            }
        } ?: run {
            PartnerLogController.log(SETUP_FAILED, "Initialization status is null.")
            Result.failure(ChartboostMediationAdException(ChartboostMediationError.InitializationError.Unknown))
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
        listener: PartnerAdListener,
    ): Result<PartnerAd> {
        return suspendCancellableCoroutine { continuation ->
            fun resumeOnce(result: Result<PartnerAd>) {
                if (continuation.isActive) {
                    continuation.resume(result)
                }
            }

            CoroutineScope(Main).launch {
                val adview = AdView(context)
                val adSize = getAdMobAdSize(context, request.bannerSize?.size, request.bannerSize?.type == BannerTypes.ADAPTIVE_BANNER)

                val details =
                    if (request.bannerSize?.type == BannerTypes.ADAPTIVE_BANNER) {
                        mapOf(
                            "banner_width_dips" to "${adSize.width}",
                            "banner_height_dips" to "${adSize.height}",
                        )
                    } else {
                        emptyMap()
                    }

                val partnerAd =
                    PartnerAd(
                        ad = adview,
                        details = details,
                        request = request,
                    )

                adview.setAdSize(adSize)
                adview.adUnitId = request.partnerPlacement
                adview.loadAd(
                    buildRequest(
                        request.identifier,
                        getIsHybridSetup(request.partnerSettings),
                    ),
                )
                adview.adListener =
                    object : AdListener() {
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
                                Result.failure(ChartboostMediationAdException(getChartboostMediationError(adError.code))),
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
     * @param context The current [Context].
     * @param size The [Size] to parse for conversion.
     * @param isAdaptive whether or not the placement is for an adaptive banner.
     *
     * @return The AdMob ad size that best matches the given [Size].
     */
    private fun getAdMobAdSize(
        context: Context,
        size: Size?,
        isAdaptive: Boolean = false,
    ): AdSize {
        if (isAdaptive) {
            return AdSize.getCurrentOrientationAnchoredAdaptiveBannerAdSize(
                context,
                size?.width ?: AdSize.BANNER.width,
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
        listener: PartnerAdListener,
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
                InterstitialAd.load(
                    context,
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
                                        request = request,
                                    ),
                                ),
                            )
                        }

                        override fun onAdFailedToLoad(loadAdError: LoadAdError) {
                            PartnerLogController.log(LOAD_FAILED, loadAdError.message)
                            resumeOnce(
                                Result.failure(
                                    ChartboostMediationAdException(
                                        getChartboostMediationError(
                                            loadAdError.code,
                                        ),
                                    ),
                                ),
                            )
                        }
                    },
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
        listener: PartnerAdListener,
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
                RewardedAd.load(
                    context,
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
                                        request = request,
                                    ),
                                ),
                            )
                        }

                        override fun onAdFailedToLoad(loadAdError: LoadAdError) {
                            PartnerLogController.log(LOAD_FAILED, loadAdError.message)
                            resumeOnce(
                                Result.failure(
                                    ChartboostMediationAdException(getChartboostMediationError(loadAdError.code)),
                                ),
                            )
                        }
                    },
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
        listener: PartnerAdListener,
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
                RewardedInterstitialAd.load(
                    context,
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
                                        request = request,
                                    ),
                                ),
                            )
                        }

                        override fun onAdFailedToLoad(loadAdError: LoadAdError) {
                            PartnerLogController.log(LOAD_FAILED, loadAdError.message)
                            resumeOnce(
                                Result.failure(
                                    ChartboostMediationAdException(getChartboostMediationError(loadAdError.code)),
                                ),
                            )
                        }
                    },
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
            Result.failure(ChartboostMediationAdException(ChartboostMediationError.ShowError.AdNotFound))
        }
    }

    /**
     * Attempt to show an AdMob interstitial ad on the main thread.
     *
     * @param activity The current [Activity].
     * @param partnerAd The [PartnerAd] object containing the AdMob ad to be shown.
     * @param listener A [PartnerAdListener] to notify Chartboost Mediation of ad events.
     *
     * @return Result.success(PartnerAd) if the ad was successfully shown, Result.failure(Exception) otherwise.
     */
    private suspend fun showInterstitialAd(
        activity: Activity,
        partnerAd: PartnerAd,
        listener: PartnerAdListener?,
    ): Result<PartnerAd> {
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
                        InterstitialAdShowCallback(
                            listener,
                            partnerAd,
                            WeakReference(continuation),
                        )
                    interstitial.show(activity)
                }
            } ?: run {
                PartnerLogController.log(SHOW_FAILED, "Ad is null.")
                resumeOnce(
                    Result.failure(
                        ChartboostMediationAdException(
                            ChartboostMediationError.ShowError.AdNotFound,
                        ),
                    ),
                )
            }
        }
    }

    /**
     * Attempt to show an AdMob rewarded ad on the main thread.
     *
     * @param activity The current [Activity].
     * @param partnerAd The [PartnerAd] object containing the AdMob ad to be shown.
     * @param listener A [PartnerAdListener] to notify Chartboost Mediation of ad events.
     *
     * @return Result.success(PartnerAd) if the ad was successfully shown, Result.failure(Exception) otherwise.
     */
    private suspend fun showRewardedAd(
        activity: Activity,
        partnerAd: PartnerAd,
        listener: PartnerAdListener?,
    ): Result<PartnerAd> {
        return suspendCancellableCoroutine { continuation ->
            fun resumeOnce(result: Result<PartnerAd>) {
                if (continuation.isActive) {
                    continuation.resume(result)
                }
            }

            partnerAd.ad?.let { ad ->
                CoroutineScope(Main).launch {
                    val rewardedAd = ad as RewardedAd

                    rewardedAd.fullScreenContentCallback =
                        RewardedAdShowCallback(
                            listener,
                            partnerAd,
                            WeakReference(continuation),
                        )

                    rewardedAd.show(activity) {
                        PartnerLogController.log(DID_REWARD)
                        listener?.onPartnerAdRewarded(partnerAd)
                            ?: PartnerLogController.log(
                                CUSTOM,
                                "Unable to fire onPartnerAdRewarded for AdMob adapter.",
                            )
                    }
                }
            } ?: run {
                PartnerLogController.log(SHOW_FAILED, "Ad is null.")
                resumeOnce(
                    Result.failure(
                        ChartboostMediationAdException(
                            ChartboostMediationError.ShowError.AdNotFound,
                        ),
                    ),
                )
            }
        }
    }

    /**
     * Attempt to show an AdMob rewarded interstitial ad on the main thread.
     *
     * @param activity The current [Activity].
     * @param partnerAd The [PartnerAd] object containing the AdMob ad to be shown.
     * @param listener A [PartnerAdListener] to notify Chartboost Mediation of ad events.
     *
     * @return Result.success(PartnerAd) if the ad was successfully shown, Result.failure(Exception) otherwise.
     */
    private suspend fun showRewardedInterstitialAd(
        activity: Activity,
        partnerAd: PartnerAd,
        listener: PartnerAdListener?,
    ): Result<PartnerAd> {
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
                        RewardedInterstitialAdShowCallback(
                            listener,
                            partnerAd,
                            WeakReference(continuation),
                        )

                    rewardedInterstitialAd.show(activity) {
                        PartnerLogController.log(DID_REWARD)
                        listener?.onPartnerAdRewarded(partnerAd)
                            ?: PartnerLogController.log(
                                CUSTOM,
                                "Unable to fire onPartnerAdRewarded for AdMob adapter.",
                            )
                    }
                }
            } ?: run {
                PartnerLogController.log(SHOW_FAILED, "Ad is null.")
                resumeOnce(
                    Result.failure(
                        ChartboostMediationAdException(
                            ChartboostMediationError.ShowError.AdNotFound,
                        ),
                    ),
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
                Result.failure(ChartboostMediationAdException(ChartboostMediationError.InvalidateError.WrongResourceType))
            }
        } ?: run {
            PartnerLogController.log(INVALIDATE_FAILED, "Ad is null.")
            Result.failure(ChartboostMediationAdException(ChartboostMediationError.InvalidateError.AdNotFound))
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
    private fun buildRequest(
        identifier: String,
        isHybridSetup: Boolean,
    ): AdRequest {
        val extras = Bundle()

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
     * Parse partner-specific settings for whether this waterfall is a hybrid setup.
     *
     * @param settings The [PartnerAdLoadRequest.partnerSettings] map containing partner-specific settings.
     *
     * @return True if this waterfall is a hybrid setup, false otherwise.
     */
    private fun getIsHybridSetup(settings: Map<String, Any>): Boolean {
        return (settings[IS_HYBRID_SETUP] as? String?)?.toBoolean() ?: false
    }
}

/**
 * Callback class for interstitial ads.
 *
 * @param listener A [PartnerAdListener] to be notified of ad events.
 * @param partnerAd A [PartnerAd] object containing the AdMob ad to be shown.
 * @param continuationRef A [WeakReference] to the [CancellableContinuation] to be resumed once the ad is shown.
 */
private class InterstitialAdShowCallback(
    private val listener: PartnerAdListener?,
    private val partnerAd: PartnerAd,
    private val continuationRef: WeakReference<CancellableContinuation<Result<PartnerAd>>>,
) : FullScreenContentCallback() {
    override fun onAdImpression() {
        PartnerLogController.log(DID_TRACK_IMPRESSION)
        listener?.onPartnerAdImpression(partnerAd) ?: PartnerLogController.log(
            CUSTOM,
            "Unable to fire onPartnerAdImpression for AdMob adapter. Listener is null",
        )
    }

    override fun onAdFailedToShowFullScreenContent(adError: AdError) {
        PartnerLogController.log(SHOW_FAILED, adError.message)
        continuationRef.get()?.let {
            if (it.isActive) {
                it.resume(
                    Result.failure(
                        ChartboostMediationAdException(
                            getChartboostMediationError(adError.code),
                        ),
                    ),
                )
            }
        } ?: PartnerLogController.log(
            CUSTOM,
            "Unable to resume continuation in onAdFailedToShowFullScreenContent(). Continuation is null.",
        )
    }

    override fun onAdShowedFullScreenContent() {
        PartnerLogController.log(SHOW_SUCCEEDED)

        continuationRef.get()?.let { continuation ->
            if (continuation.isActive) {
                continuation.resume(Result.success(partnerAd))
            }
        } ?: PartnerLogController.log(
            CUSTOM,
            "Unable to resume continuation in onAdShowedFullScreenContent(). Continuation is null.",
        )
    }

    override fun onAdClicked() {
        PartnerLogController.log(DID_CLICK)
        listener?.onPartnerAdClicked(partnerAd) ?: PartnerLogController.log(
            CUSTOM,
            "Unable to fire onPartnerAdClicked for AdMob adapter. Listener is null",
        )
    }

    override fun onAdDismissedFullScreenContent() {
        PartnerLogController.log(DID_DISMISS)
        listener?.onPartnerAdDismissed(partnerAd, null) ?: PartnerLogController.log(
            CUSTOM,
            "Unable to fire onPartnerAdDismissed for AdMob adapter. Listener is null",
        )
    }
}

/**
 * Callback class for rewarded ads.
 *
 * @param listener A [PartnerAdListener] to be notified of ad events.
 * @param partnerAd A [PartnerAd] object containing the AdMob ad to be shown.
 * @param continuationRef A [WeakReference] to the [CancellableContinuation] to be resumed once the ad is shown.
 */
private class RewardedAdShowCallback(
    private val listener: PartnerAdListener?,
    private val partnerAd: PartnerAd,
    private val continuationRef: WeakReference<CancellableContinuation<Result<PartnerAd>>>,
) : FullScreenContentCallback() {
    override fun onAdImpression() {
        PartnerLogController.log(DID_TRACK_IMPRESSION)

        listener?.onPartnerAdImpression(partnerAd) ?: PartnerLogController.log(
            CUSTOM,
            "Unable to fire onPartnerAdImpression for AdMob adapter. Listener is null",
        )
    }

    override fun onAdFailedToShowFullScreenContent(adError: AdError) {
        PartnerLogController.log(SHOW_FAILED, adError.message)
        continuationRef.get()?.let {
            if (it.isActive) {
                it.resume(
                    Result.failure(
                        ChartboostMediationAdException(
                            getChartboostMediationError(adError.code),
                        ),
                    ),
                )
            }
        } ?: PartnerLogController.log(
            CUSTOM,
            "Unable to resume continuation in onAdFailedToShowFullScreenContent(). Continuation is null.",
        )
    }

    override fun onAdShowedFullScreenContent() {
        PartnerLogController.log(SHOW_SUCCEEDED)

        continuationRef.get()?.let { continuation ->
            if (continuation.isActive) {
                continuation.resume(Result.success(partnerAd))
            }
        } ?: PartnerLogController.log(
            CUSTOM,
            "Unable to resume continuation in onAdShowedFullScreenContent(). Continuation is null.",
        )
    }

    override fun onAdClicked() {
        PartnerLogController.log(DID_CLICK)

        listener?.onPartnerAdClicked(partnerAd) ?: PartnerLogController.log(
            CUSTOM,
            "Unable to fire onPartnerAdClicked for AdMob adapter. Listener is null",
        )
    }

    override fun onAdDismissedFullScreenContent() {
        PartnerLogController.log(DID_DISMISS)

        listener?.onPartnerAdDismissed(partnerAd, null) ?: PartnerLogController.log(
            CUSTOM,
            "Unable to fire onPartnerAdDismissed for AdMob adapter. Listener is null",
        )
    }
}

/**
 * Callback class for rewarded interstitial ads.
 *
 * @param listener A [PartnerAdListener] to be notified of ad events.
 * @param partnerAd A [PartnerAd] object containing the AdMob ad to be shown.
 * @param continuationRef A [WeakReference] to the [CancellableContinuation] to be resumed once the ad is shown.
 */
private class RewardedInterstitialAdShowCallback(
    private val listener: PartnerAdListener?,
    private val partnerAd: PartnerAd,
    private val continuationRef: WeakReference<CancellableContinuation<Result<PartnerAd>>>,
) : FullScreenContentCallback() {
    override fun onAdImpression() {
        PartnerLogController.log(DID_TRACK_IMPRESSION)
        listener?.onPartnerAdImpression(partnerAd) ?: PartnerLogController.log(
            CUSTOM,
            "Unable to fire onPartnerAdImpression for AdMob adapter. Listener is null",
        )
    }

    override fun onAdFailedToShowFullScreenContent(adError: AdError) {
        PartnerLogController.log(SHOW_FAILED, adError.message)
        continuationRef.get()?.let {
            if (it.isActive) {
                it.resume(
                    Result.failure(
                        ChartboostMediationAdException(
                            getChartboostMediationError(adError.code),
                        ),
                    ),
                )
            }
        } ?: PartnerLogController.log(
            CUSTOM,
            "Unable to resume continuation in onAdFailedToShowFullScreenContent(). Continuation is null.",
        )
    }

    override fun onAdShowedFullScreenContent() {
        PartnerLogController.log(SHOW_SUCCEEDED)
        continuationRef.get()?.let { continuation ->
            if (continuation.isActive) {
                continuation.resume(Result.success(partnerAd))
            }
        } ?: PartnerLogController.log(
            CUSTOM,
            "Unable to resume continuation in onAdShowedFullScreenContent(). Continuation is null.",
        )
    }

    override fun onAdClicked() {
        PartnerLogController.log(DID_CLICK)

        listener?.onPartnerAdClicked(partnerAd) ?: PartnerLogController.log(
            CUSTOM,
            "Unable to fire onPartnerAdClicked for AdMob adapter. Listener is null",
        )
    }

    override fun onAdDismissedFullScreenContent() {
        PartnerLogController.log(DID_DISMISS)

        listener?.onPartnerAdDismissed(partnerAd, null) ?: PartnerLogController.log(
            CUSTOM,
            "Unable to fire onPartnerAdDismissed for AdMob adapter. Listener is null",
        )
    }
}
