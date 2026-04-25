package com.example.youoffline.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

object DpiPresets {

    // argv[0] placeholder (byedpi's main() ignores it, but getopt needs it)
    private const val PROG = "ciadpi"
    private const val PORT = "1080"

    fun primaryArgs(context: Context): List<String> {
        val profile = runCatching {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val caps = cm.getNetworkCapabilities(cm.activeNetwork)
            if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true) {
                // mobile: OOB + auto-disorder on TLS/redirect triggers
                listOf("-X", "-o1", "-At,r,s", "-d1")
            } else {
                // wi-fi: OOB only for trigger events
                listOf("-X", "-o1", "-At,r,s", "-d1")
            }
        }.getOrDefault(listOf("-X", "-o1", "-At,r,s", "-d1"))

        return listOf(PROG, "--port", PORT) + profile
    }

    fun fallbackProfiles(context: Context): List<List<String>> {
        return listOf(
            primaryArgs(context),
            listOf(PROG, "--port", PORT, "-X", "-o1", "-d1"),
            listOf(PROG, "--port", PORT, "-X", "-d1")
        )
    }
}
