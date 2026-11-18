package com.reianix.reianix_local.player.cast

import android.net.Uri
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Discovers Roku players through SSDP and sends ECP deep links over the local network.
 * Call on IO. Roku's ECP is plain HTTP on port 8060, so a raw socket is used to avoid
 * Android cleartext HTTP policy changing the rest of the app's network security.
 */
internal object RokuEcpClient {
    data class Device(val address: InetAddress, val name: String) {
        val id: String get() = address.hostAddress.orEmpty()
    }

    suspend fun discover(timeoutMs: Int = 2_500): List<Device> = withContext(Dispatchers.IO) {
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
            socket.send(DatagramPacket(request, request.size, InetAddress.getByName("239.255.255.250"), 1900))
            val deadline = System.currentTimeMillis() + timeoutMs
            val buffer = ByteArray(4096)
            while (System.currentTimeMillis() < deadline) {
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    socket.receive(packet)
                } catch (_: java.net.SocketTimeoutException) {
                    continue
                }
                val response = String(packet.data, packet.offset, packet.length, StandardCharsets.US_ASCII)
                val location = Regex("(?im)^LOCATION:\\s*http://([^/:]+)").find(response)?.groupValues?.get(1)
                    ?: packet.address.hostAddress
                val address = runCatching { InetAddress.getByName(location) }.getOrNull() ?: continue
                val name = Regex("(?im)^USN:\\s*roku:ecp:([^\\r\\n]+)").find(response)?.groupValues?.get(1)
                    ?.substringAfterLast(':')?.ifBlank { null } ?: "Roku"
                found[address.hostAddress.orEmpty()] = Device(address, name)
            }
        }
        found.values.toList()
    }

    suspend fun play(device: Device, mediaUrl: String, title: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                require(mediaUrl.startsWith("http://") || mediaUrl.startsWith("https://")) {
                    "O endereço do vídeo precisa ser HTTP ou HTTPS"
                }
                // The receiver channel is sideloaded as the Roku development app ("dev").
                val payload = Uri.encode("$mediaUrl|$title")
                val path = "/launch/dev?contentId=$payload&mediaType=movie"
                val request = "POST $path HTTP/1.1\r\nHost: ${device.id}:8060\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                Socket(device.address, 8060).use { socket ->
                    socket.soTimeout = 5_000
                    socket.getOutputStream().write(request.toByteArray(StandardCharsets.US_ASCII))
                    val status = BufferedReader(InputStreamReader(socket.getInputStream())).readLine().orEmpty()
                    check(status.contains("200") || status.contains("204")) {
                        "A Roku recusou o comando de reprodução ($status)"
                    }
                }
            }
        }
}
