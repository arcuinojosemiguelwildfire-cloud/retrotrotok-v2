package com.example.cloud

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

interface ConnectivityObserver {
    val isOnline: StateFlow<Boolean>
    fun checkIsOnlineNow(): Boolean
}

/**
 * Monitors internet connectivity on Android 6.0.1+ (API 23+) using ConnectivityManager
 * NetworkCallback, degrading gracefully if system services are restricted in tests.
 */
class AndroidConnectivityObserver(
    context: Context
) : ConnectivityObserver {

    companion object {
        private const val TAG = "ConnectivityObserver"
    }

    private val connectivityManager =
        context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    private val _isOnline = MutableStateFlow(queryNetworkAvailable())
    override val isOnline: StateFlow<Boolean> = _isOnline.asStateFlow()

    init {
        registerCallbackSafely()
    }

    override fun checkIsOnlineNow(): Boolean {
        val current = queryNetworkAvailable()
        _isOnline.value = current
        return current
    }

    private fun registerCallbackSafely() {
        val cm = connectivityManager ?: return
        try {
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            cm.registerNetworkCallback(
                request,
                object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        _isOnline.value = true
                    }

                    override fun onLost(network: Network) {
                        _isOnline.value = queryNetworkAvailable()
                    }

                    override fun onCapabilitiesChanged(
                        network: Network,
                        networkCapabilities: NetworkCapabilities
                    ) {
                        val hasNet = networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                        if (hasNet) {
                            _isOnline.value = true
                        } else {
                            _isOnline.value = queryNetworkAvailable()
                        }
                    }
                }
            )
        } catch (e: Exception) {
            Log.w(TAG, "Unable to register NetworkCallback (${e.javaClass.simpleName}); using polling check")
        }
    }

    private fun queryNetworkAvailable(): Boolean {
        val cm = connectivityManager ?: return false
        return try {
            val active = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(active) ?: return false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } catch (_: Exception) {
            false
        }
    }
}
