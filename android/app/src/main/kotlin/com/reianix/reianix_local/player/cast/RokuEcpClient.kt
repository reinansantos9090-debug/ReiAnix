package com.reianix.reianix_local.player.cast

import android.content.Context
import android.net.Uri
import android.net.wifi.WifiManager
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Roku OS uses SSDP + External Control Protocol (ECP), NOT Google Cast.
 * The Roku must have the ReiAnix Receiver sideloaded as the development app.
 * Restrict commands to IPs discovered on the LAN; no Internet video discovery.
 */
internal object RokuEcpClient {
    data class Device(val address: InetAddress, val name: String) {
        val id: String get() = address.hostAddress.orEmpty()
    }

    private const val ECP_PORT = 8060
    private const val CONNECT_TIMEOUT_MS = 3_000
    private const val READ_TIMEOUT_MS = 4_000
    private const val RECEIVER_NAME = "ReiAnix Receiver"

    suspend fun discover(context: Context, timeoutMs: Int = 3_000): List<Device> =
        withContext(Dispatchers.IO) {
            val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val multicastLock = runCatching {
                wifi?.createMulticastLock("ReiAnix-Roku-SSDP")?.apply {
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
                    socket.send(DatagramPacket(
                        request, request.size, InetAddress.getByName("239.255.255.250"), 1900,
                    ))
                    val deadline = System.nanoTime() + timeoutMs.coerceIn(500, 8_000) * 1_000_000L
                    val bytes = ByteArray(4_096)
                    while (System.nanoTime() < deadline) {
                        val packet = DatagramPacket(bytes, bytes.size)
                        try {
                            socket.receive(packet)
                        } catch (_: SocketTimeoutException) {
                            continue
                        }
                        // Ignore arbitrary UPnP devices and advertised LOCATION hosts.
                        val response = String(
                            packet.data, packet.offset, packet.length, StandardCharsets.US_ASCII,
                        )
                        if (!response.startsWith("HTTP/1.1 200") ||
                            !response.contains("roku:ecp", ignoreCase = true)
                        ) continue
                        val address = packet.address
                        if (address !is Inet4Address || !address.isSiteLocalAddress) continue
                        val id = address.hostAddress.orEmpty()
                        found[id] = Device(address, "Roku ($id)")
                    }
                }
                found.values.toList()
            } finally {
                if (multicastLock?.isHeld == true) multicastLock.release()
            }
        }

    /** ECP HTTP 200 means only that a launch was accepted, not that video decoded. */
    suspend fun play(
        device: Device,
        mediaUrl: String,
        title: String,
        mimeType: String,
        positionMs: Long,
        shouldPlay: Boolean,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            require(mediaUrl.startsWith("http://") || mediaUrl.startsWith("https://")) {
                "O vídeo precisa de um endereço HTTP local"
            }
            val format = streamFormat(mimeType)
            checkReceiver(device)
            val path = buildLaunchPath(mediaUrl, title, format, positionMs, shouldPlay)
            val response = request(device, "POST", path)
            check(response.status in 200..299) {
                "A Roku recusou o envio do vídeo (HTTP ${response.status})"
            }
        }
    }

    suspend fun command(device: Device, name: String, seconds: Long? = null): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                require(name in setOf("play", "pause", "seek", "stop"))
                val path = buildString {
                    append("/input?reianix_command=")
                    append(name)
                    if (seconds != null) append("&seconds=${seconds.coerceAtLeast(0L)}")
                }
                val response = request(device, "POST", path)
                check(response.status in 200..299) {
                    "A Roku recusou o controle remoto (HTTP ${response.status})"
                }
            }
        }

    /** Authoritative playback state from Roku OS, when ECP reporting is available. */
    internal data class PlaybackStatus(
        val state: String,
        val positionMs: Long?,
        val durationMs: Long?,
        val receiverActive: Boolean,
        val error: Boolean,
    )

    suspend fun playbackStatus(device: Device): PlaybackStatus? =
        withContext(Dispatchers.IO) {
            runCatching {
                val response = request(device, "GET", "/query/media-player")
                if (response.status != 200) return@runCatching null
                parsePlaybackStatus(response.body)
            }.getOrNull()
        }

    internal fun parsePlaybackStatus(xml: String): PlaybackStatus? {
        val player = Regex("""(?is)<player\b([^>]*)>""").find(xml) ?: return null
        val attrs = player.groupValues[1]
        val state = Regex("""(?i)\bstate=["']([^"']+)["']""")
            .find(attrs)?.groupValues?.get(1)?.lowercase() ?: return null
        val pluginId = Regex("""(?is)<plugin\b[^>]*\bid=["']([^"']+)["']""")
            .find(xml)?.groupValues?.get(1)
        fun milliseconds(tag: String): Long? =
            Regex("""(?is)<$tag>\s*(\d+)\s*(?:ms)?\s*</$tag>""")
                .find(xml)?.groupValues?.get(1)?.toLongOrNull()
        return PlaybackStatus(
            state = state,
            positionMs = milliseconds("position"),
            durationMs = milliseconds("duration"),
            receiverActive = pluginId == "dev",
            error = Regex("""(?i)\berror=["']true["']""").containsMatchIn(attrs),
        )
    }

    suspend fun volumeKey(device: Device, up: Boolean): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val response = request(device, "POST", "/keypress/" + (if (up) "VolumeUp" else "VolumeDown"))
            check(response.status in 200..299) {
                "O volume da Roku não pôde ser ajustado"
            }
        }
    }

    private fun checkReceiver(device: Device) {
        val response = request(device, "GET", "/query/apps")
        check(response.status in 200..299) {
            "A Roku não permite controle pela rede. Verifique 'Controle por apps móveis'."
        }
        check(hasReiAnixReceiver(response.body)) {
            "Instale o canal ReiAnix Receiver na Roku (modo desenvolvedor)."
        }
    }

    /** /query/apps must contain our receiver, not merely any unrelated sideloaded app. */
    internal fun hasReiAnixReceiver(xml: String): Boolean =
        Regex("""(?is)<app\b(?=[^>]*\bid=["']dev["'])[^>]*>(.*?)</app>""")
            .find(xml)?.groupValues?.get(1)?.contains(RECEIVER_NAME, ignoreCase = true) == true

    internal fun streamFormat(mime: String): String = when (mime.lowercase().substringBefore(';')) {
        "video/mp4", "video/x-m4v" -> "mp4"
        "video/x-matroska", "video/mkv" -> "mkv"
        else -> throw IOException("A Roku aceita nesta integração arquivos MP4/MKV compatíveis; sem conversão automática.")
    }

    internal fun buildLaunchPath(
        mediaUrl: String, title: String, format: String, positionMs: Long, shouldPlay: Boolean,
    ): String {
        require(mediaUrl.length <= 240) { "Endereço de vídeo longo demais para o Roku ECP" }
        require(format == "mp4" || format == "mkv")
        return "/launch/dev?contentId=${Uri.encode(mediaUrl)}" +
            "&mediaType=episode&title=${Uri.encode(title.take(80))}" +
            "&format=$format&position=${positionMs.coerceAtLeast(0) / 1000}" +
            "&autoplay=${if (shouldPlay) 1 else 0}"
    }

    private data class Response(val status: Int, val body: String)

    private fun request(device: Device, method: String, path: String): Response {
        require(device.address is Inet4Address && device.address.isSiteLocalAddress)
        require(method == "GET" || method == "POST")
        require(path.startsWith("/") && !path.contains('\r') && !path.contains('\n'))
        Socket().use { socket ->
            socket.connect(InetSocketAddress(device.address, ECP_PORT), CONNECT_TIMEOUT_MS)
            socket.soTimeout = READ_TIMEOUT_MS
            val request = "$method $path HTTP/1.1\r\nHost: ${device.id}:$ECP_PORT\r\n" +
                "Content-Length: 0\r\nConnection: close\r\n\r\n"
            socket.getOutputStream().write(request.toByteArray(StandardCharsets.US_ASCII))
            socket.getOutputStream().flush()
            val input = socket.getInputStream()
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(2_048)
            while (output.size() < 128 * 1_024) {
                val count = try { input.read(buffer) } catch (_: SocketTimeoutException) { break }
                if (count < 0) break
                output.write(buffer, 0, count)
            }
            val raw = output.toString("UTF-8")
            val code = Regex("""^HTTP/\d(?:\.\d)?\s+(\d{3})""")
                .find(raw)?.groupValues?.get(1)?.toIntOrNull()
                ?: throw IOException("A Roku não respondeu corretamente (ECP)")
            return Response(code, raw.substringAfter("\r\n\r\n", ""))
        }
    }
}
