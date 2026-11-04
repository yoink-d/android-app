package com.maynkudu.yoinkd.download

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first

class NetworkMonitor(context: Context) {
    private val manager = context.getSystemService(ConnectivityManager::class.java)
    private val mutableOnline = MutableStateFlow(available())
    val online = mutableOnline.asStateFlow()

    init {
        manager.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { mutableOnline.value = available() }
            override fun onLost(network: Network) { mutableOnline.value = available() }
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                mutableOnline.value = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            }
        })
    }

    private fun available(): Boolean = manager.getNetworkCapabilities(manager.activeNetwork)?.let {
        it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            it.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    } == true

    suspend fun awaitOnline() { online.filter { it }.first() }
}
