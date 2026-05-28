package br.com.player.player

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.telephony.TelephonyManager

class NetworkQualityProvider(private val context: Context) {

    enum class NetworkTier { WIFI, CELLULAR_FAST, CELLULAR_SLOW, OFFLINE }

    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    fun currentTier(): NetworkTier {
        val network = connectivityManager.activeNetwork ?: return NetworkTier.OFFLINE
        val caps = connectivityManager.getNetworkCapabilities(network) ?: return NetworkTier.OFFLINE
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetworkTier.WIFI

            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    if (caps.linkDownstreamBandwidthKbps >= 2_000) NetworkTier.CELLULAR_FAST
                    else NetworkTier.CELLULAR_SLOW
                } else {
                    @Suppress("DEPRECATION")
                    when ((context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager).networkType) {
                        TelephonyManager.NETWORK_TYPE_LTE,
                        TelephonyManager.NETWORK_TYPE_HSPAP -> NetworkTier.CELLULAR_FAST
                        else -> NetworkTier.CELLULAR_SLOW
                    }
                }
            }

            else -> NetworkTier.OFFLINE
        }
    }
}
