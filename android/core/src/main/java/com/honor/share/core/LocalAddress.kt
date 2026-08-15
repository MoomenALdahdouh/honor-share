package com.honor.share.core

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.Inet4Address
import java.net.NetworkInterface

object LocalAddress {
    fun ipv4(context: Context): String? {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        for (network in cm.allNetworks) {
            val caps = cm.getNetworkCapabilities(network) ?: continue
            if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                !caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
            ) {
                continue
            }
            val properties = cm.getLinkProperties(network) ?: continue
            properties.linkAddresses.forEach { address ->
                val inet = address.address
                if (inet is Inet4Address && !inet.isLoopbackAddress && !inet.isLinkLocalAddress) {
                    return inet.hostAddress
                }
            }
        }
        return interfaceFallback()
    }

    private fun interfaceFallback(): String? {
        val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
        for (item in interfaces) {
            if (!item.isUp || item.isLoopback) continue
            for (address in item.inetAddresses) {
                if (address is Inet4Address && !address.isLoopbackAddress && !address.isLinkLocalAddress) {
                    return address.hostAddress
                }
            }
        }
        return null
    }
}
