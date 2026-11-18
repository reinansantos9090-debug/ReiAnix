package com.reianix.reianix_local.player.cast

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.provider.Settings
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Optional, on-demand Roku discovery for the screen mirroring entry in the player.
 *
 * Finding a Roku via SSDP does NOT mean it supports Google Cast or direct ECP
 * video playback. Screen mirroring is performed by Android/Smart View (Miracast).
 * No requests are sent to Roku's control port, and no Roku channel is needed.
 */
internal object RokuMirroringDiscovery {
    data class Device(val address: String) {
        val label: String get() = "Roku • $address"
    }

    internal fun parseResponse(response: String, sender: InetAddress): Device? {
        if (sender !is Inet4Address || !sender.isSiteLocalAddress) return null
        if (!response.startsWith("HTTP/1.1 200", ignoreCase = true) &&
            !response.startsWith("HTTP/1.0 200", ignoreCase = true)
        ) return null
        if (!response.contains("roku:ecp", ignoreCase = true)) return null
        return sender.hostAddress?.let { Device(it) }
    }

    /** Scans only when the user opens the TV chooser, never while browsing. */
    suspend fun discover(context: Context, timeoutMs: Int = 2_500): List<Device> =
        withContext(Dispatchers.IO) {
            val manager = context.applicationContext.getSystemService(Context.WIFI_SERVICE)
                as? WifiManager
            val lock = runCatching {
                manager?.createMulticastLock("ReiAnix-Roku-Mirroring")?.apply {
                    setReferenceCounted(false)
                    acquire()
                }
            }.getOrNull()
            try {
                val found = linkedMapOf<String, Device>()
                DatagramSocket().use { socket ->
                    socket.soTimeout = 250
                    val request = (
                        "M-SEARCH * HTTP/1.1\r\n" +
                            "HOST: 239.255.255.250:1900\r\n" +
                            "MAN: \"ssdp:discover\"\r\n" +
                            "MX: 2\r\n" +
                            "ST: roku:ecp\r\n\r\n"
                    ).toByteArray(StandardCharsets.US_ASCII)
                    socket.send(
                        DatagramPacket(
                            request, request.size,
                            InetAddress.getByName("239.255.255.250"), 1900,
                        )
                    )
                    val deadline = System.nanoTime() + timeoutMs.coerceIn(500, 5_000) * 1_000_000L
                    val buffer = ByteArray(4096)
                    while (System.nanoTime() < deadline) {
                        val response = DatagramPacket(buffer, buffer.size)
                        try {
                            socket.receive(response)
                        } catch (_: SocketTimeoutException) {
                            continue
                        }
                        val message = String(
                            response.data, response.offset, response.length,
                            StandardCharsets.US_ASCII,
                        )
                        parseResponse(message, response.address)?.let {
                            found[it.address] = it
                        }
                    }
                }
                found.values.toList()
            } finally {
                if (lock?.isHeld == true) lock.release()
            }
        }
}

/** Android, not ReiAnix, asks permission and connects to a wireless display. */
internal object RokuMirroringLauncher {
    fun openSystemCastSettings(context: Context): Boolean {
        // OEM implementations may route the public Cast settings to Smart View.
        // Other devices can use the Smart View quick-settings tile instead.
        for (action in listOf(Settings.ACTION_CAST_SETTINGS, Settings.ACTION_DISPLAY_SETTINGS)) {
            try {
                context.startActivity(
                    Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                return true
            } catch (_: ActivityNotFoundException) {
                // Try the documented display settings fallback.
            } catch (_: SecurityException) {
                // A manufacturer may restrict the settings Activity.
            }
        }
        return false
    }
}
