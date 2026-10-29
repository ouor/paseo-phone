package io.github.ouor.paseophone.runtime.system

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import java.net.Inet4Address
import java.net.NetworkInterface

/** Wi-Fi and Tailscale IPv4 addresses shown for SSH access (architecture §7.4, design §5). */
object NetworkAddresses {
  data class Address(val kind: String, val address: String) // kind: "wifi" | "tailscale"

  /** Wi-Fi first, then Tailscale. */
  fun read(): List<Address> {
    val result = mutableListOf<Address>()
    val interfaces =
      try {
        NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
      } catch (_: Exception) {
        emptyList()
      }
    for (iface in interfaces) {
      val up = try { iface.isUp && !iface.isLoopback } catch (_: Exception) { false }
      if (!up) continue
      for (address in iface.inetAddresses.toList().filterIsInstance<Inet4Address>()) {
        val kind =
          when {
            isTailscale(address) -> "tailscale"
            iface.name.startsWith("wlan") -> "wifi"
            else -> null
          } ?: continue
        result += Address(kind, address.hostAddress ?: continue)
      }
    }
    return result.sortedBy { if (it.kind == "wifi") 0 else 1 }
  }

  // Tailscale hands out CGNAT addresses (100.64.0.0/10).
  private fun isTailscale(address: Inet4Address): Boolean {
    val bytes = address.address
    return (bytes[0].toInt() and 0xff) == 100 && (bytes[1].toInt() and 0xc0) == 64
  }

  /** Calls [onChange] on any network change, VPNs included. Returns the unregister function. */
  fun watch(context: Context, onChange: () -> Unit): () -> Unit {
    val connectivity = context.getSystemService(ConnectivityManager::class.java) ?: return {}
    val callback =
      object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = onChange()
        override fun onLost(network: Network) = onChange()
        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) = onChange()
      }
    val request = NetworkRequest.Builder().removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN).build()
    try {
      connectivity.registerNetworkCallback(request, callback)
    } catch (_: Exception) {
      return {}
    }
    return { runCatching { connectivity.unregisterNetworkCallback(callback) } }
  }
}
