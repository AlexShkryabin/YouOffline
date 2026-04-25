package com.example.youoffline.ads

import android.app.Activity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.yandex.mobile.ads.common.AdError
import com.yandex.mobile.ads.common.AdRequestConfiguration
import com.yandex.mobile.ads.common.AdRequestError
import com.yandex.mobile.ads.common.ImpressionData
import com.yandex.mobile.ads.interstitial.InterstitialAd
import com.yandex.mobile.ads.interstitial.InterstitialAdEventListener
import com.yandex.mobile.ads.interstitial.InterstitialAdLoadListener
import com.yandex.mobile.ads.interstitial.InterstitialAdLoader
import com.example.youoffline.BuildConfig

class YandexInterstitialAdManager(
    private val activity: Activity,
    private val adUnitId: String = BuildConfig.AD_UNIT_INTERSTITIAL
) {
    private var interstitialAd: InterstitialAd? = null
    private var interstitialAdLoader: InterstitialAdLoader? = null

    init {
        loadAd()
    }

    private fun loadAd() {
        interstitialAdLoader = InterstitialAdLoader(activity).apply {
            setAdLoadListener(object : InterstitialAdLoadListener {
                override fun onAdLoaded(ad: InterstitialAd) {
                    interstitialAd = ad
                }

                override fun onAdFailedToLoad(error: AdRequestError) {
                    interstitialAd = null
                }
            })
        }

        val adRequestConfiguration = AdRequestConfiguration.Builder(adUnitId).build()
        interstitialAdLoader?.loadAd(adRequestConfiguration)
    }

    fun showAd(onAdDismissed: () -> Unit) {
        interstitialAd?.apply {
            setAdEventListener(object : InterstitialAdEventListener {
                override fun onAdShown() {}

                override fun onAdFailedToShow(adError: AdError) {
                    onAdDismissed()
                    loadAd()
                }

                override fun onAdDismissed() {
                    onAdDismissed()
                    loadAd()
                }

                override fun onAdClicked() {}

                override fun onAdImpression(impressionData: ImpressionData?) {}
            })
            show(activity)
        } ?: run {
            onAdDismissed()
            loadAd()
        }
    }

    fun destroy() {
        interstitialAd?.setAdEventListener(null)
        interstitialAd = null
        interstitialAdLoader = null
    }
}

@Composable
fun rememberYandexInterstitialAd(adUnitId: String = BuildConfig.AD_UNIT_INTERSTITIAL): YandexInterstitialAdManager {
    val context = LocalContext.current
    val activity = context as? Activity ?: error("Yandex interstitial requires Activity context")

    return remember(activity, adUnitId) {
        YandexInterstitialAdManager(activity, adUnitId)
    }
}
