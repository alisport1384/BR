package com.bigrocket.service

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest

class NetworkMonitor(
    context: Context,
    private val listener: NetworkStateListener
) {
    interface NetworkStateListener {
        fun onNetworksUpdated(wifi: Network?, cellular: Network?)
    }

    private val connectivityManager =
        context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private var wifiNetwork: Network? = null
    private var cellularNetwork: Network? = null

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            refreshNetwork(network)
        }

        override fun onLost(network: Network) {
            if (network == wifiNetwork) wifiNetwork = null
            if (network == cellularNetwork) cellularNetwork = null
            refreshAllPhysicalNetworks(notify = true)
        }

        override fun onCapabilitiesChanged(
            network: Network,
            networkCapabilities: NetworkCapabilities
        ) {
            applyCapabilities(network, networkCapabilities)
        }
    }

    private fun refreshNetwork(network: Network) {
        val caps = connectivityManager.getNetworkCapabilities(network) ?: return
        applyCapabilities(network, caps)
    }

    private fun applyCapabilities(network: Network, caps: NetworkCapabilities) {
        // A VPN/TUN network is never an uplink candidate. Do not require INTERNET here:
        // Android/OEMs may temporarily omit that capability during validation/reconnect.
        if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)) {
            if (network == wifiNetwork) wifiNetwork = null
            if (network == cellularNetwork) cellularNetwork = null
            listener.onNetworksUpdated(wifiNetwork, cellularNetwork)
            return
        }

        when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> wifiNetwork = network
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> cellularNetwork = network
        }

        listener.onNetworksUpdated(wifiNetwork, cellularNetwork)
    }

    private fun refreshAllPhysicalNetworks(notify: Boolean) {
        var foundWifi: Network? = null
        var foundCellular: Network? = null

        for (network in connectivityManager.allNetworks) {
            val caps = connectivityManager.getNetworkCapabilities(network) ?: continue
            if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)) continue

            when {
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> foundWifi = network
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> foundCellular = network
            }
        }

        wifiNetwork = foundWifi
        cellularNetwork = foundCellular

        if (notify) listener.onNetworksUpdated(wifiNetwork, cellularNetwork)
    }

    fun startMonitoring() {
        // Observe physical transports, not only networks that Android currently considers
        // validated. This remains stable while a VPN is being established and during handoff.
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()

        try {
            connectivityManager.registerNetworkCallback(request, networkCallback)
        } catch (_: Exception) {
            return
        }

        refreshAllPhysicalNetworks(notify = true)
    }

    fun stopMonitoring() {
        try {
            connectivityManager.unregisterNetworkCallback(networkCallback)
        } catch (_: Exception) {
        }
        wifiNetwork = null
        cellularNetwork = null
    }
}
