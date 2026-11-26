package io.github.d1ngtalk.iap2loc.app.ui

import java.net.Inet4Address
import java.net.NetworkInterface

object Network {
    /** IPv4 addresses other devices on the same network can reach this phone at. */
    fun lanAddresses(): List<String> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .filter { it.isSiteLocalAddress }
            .map { it.hostAddress.orEmpty() }
            .filter { it.isNotEmpty() }
            .distinct()
    }.getOrDefault(emptyList())
}
