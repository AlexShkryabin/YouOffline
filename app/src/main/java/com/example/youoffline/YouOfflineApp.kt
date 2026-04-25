package com.example.youoffline

import android.app.Application
import android.util.Log
import com.yandex.mobile.ads.common.MobileAds
import com.yandex.mobile.ads.instream.MobileInstreamAds

class YouOfflineApp : Application() {

    override fun onCreate() {
        super.onCreate()

        if (!BuildConfig.ENABLE_YANDEX_ADS) {
            isYandexAdsReady = false
            Log.d(TAG, "Yandex Ads disabled by build config")
            return
        }

        runCatching {
            MobileAds.initialize(this) {}
            MobileInstreamAds.setAdGroupPreloading(true)
            MobileAds.enableLogging(BuildConfig.DEBUG)
            isYandexAdsReady = true
        }.onFailure { error ->
            isYandexAdsReady = false
            Log.e(TAG, "Yandex Ads initialization failed, keeping fallback banner", error)
        }
    }

    companion object {
        @Volatile
        var isYandexAdsReady: Boolean = false
            private set

        private const val TAG = "YouOfflineApp"
    }
}
