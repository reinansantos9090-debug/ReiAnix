package com.reianix.reianix_local.player.tv

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.net.wifi.WifiManager
import android.provider.OpenableColumns
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.FileInputStream
import java.io.InputStream
import java.net.DatagramPacket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.nio.charset.StandardCharsets
import java.util.UUID
import kotlin.concurrent.thread

/**
 * Share ONLY the current SAF video on the active private LAN.
 *
 * The same Range-enabled HTTP endpoint serves Cast and DLNA renderers.
 * Roku Media Player can browse the small UPnP MediaServer exposed here;
 * Roku is not an UPnP renderer and cannot be auto-launched by third-party ECP.
 */
class ReiAnixTvMediaServer(
    private val context: Context,
    private val mediaUri: Uri,
    private val mediaTitle: String,
) : Closeable {
    private val token = UUID.randomUUID().toString().replace("-", "")
    private val deviceId = UUID.nameUUIDFromBytes(
        ("reianix-player-tv:" + context.packageName).toByteArray(StandardCharsets.UTF_8)
    ).toString()
    private val ssdpGroup = InetAddress.getByName("239.255.255.250")
    private val host: Inet4Address = privateLanAddress(context)
        ?: throw IllegalStateException("Conecte o celular e a TV à mesma rede Wi-Fi.")
    private val size = fileSize(context, mediaUri).also {
        require(it > 0L) { "O arquivo selecionado não permite transmissão pela rede." }
    }
    private val mime = mimeType(context, mediaUri)
    private val server = ServerSocket(0, 12, host)
    private val path = "/media/" + token
    private val baseUrl = "http://" + host.hostAddress + ":" + server.localPort
    val mediaUrl: String get() = baseUrl + path
    val friendlyName: String = "ReiAnix"
    @Volatile private var closed = false
    private var ssdpSocket: MulticastSocket? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    init {
        server.soTimeout = 1500
        thread(name = "reianix-tv-http", isDaemon = true) {
            while (!closed) {
                try {
                    val socket = server.accept()
                    thread(name = "reianix-tv-client", isDaemon = true) {
                        socket.use { client ->
                            if (client.inetAddress.isSiteLocalAddress || client.inetAddress.isLoopbackAddress) {
                                runCatching { serve(client) }
                            }
                        }
                    }
                } catch (_: java.net.SocketTimeoutException) {
                    // Check whether this session has ended.
                } catch (_: SocketException) {
                    if (closed) return@thread
                } catch (_: Exception) {
                    if (closed) return@thread
                }
            }
        }
        startSsdp()
    }

    private fun serve(client: Socket) {
        client.soTimeout = 20_000
        val input = BufferedInputStream(client.getInputStream())
        val request = readAsciiLine(input) ?: return
        val parts = request.split(' ')
        if (parts.size < 2) return
        val verb = parts[0]
        if (verb !in setOf("GET", "HEAD", "POST")) return
        val target = parts[1].substringBefore('?')
        var range: String? = null
        var length = 0
        var soapAction = ""
        while (true) {
            val line = readAsciiLine(input) ?: return
            if (line.isEmpty()) break
            val sep = line.indexOf(':')
            if (sep < 0) continue
            val key = line.substring(0, sep).trim().lowercase()
            val value = line.substring(sep + 1).trim()
            when (key) {
                "range" -> range = value
                "content-length" -> length = value.toIntOrNull()?.coerceIn(0, 65536) ?: 0
                "soapaction" -> soapAction = value.trim('"')
            }
        }
        val output = BufferedOutputStream(client.getOutputStream())
        when {
            target == path && (verb == "GET" || verb == "HEAD") ->
                writeVideo(output, verb == "HEAD", range)
            target == "/device.xml" && (verb == "GET" || verb == "HEAD") ->
                writeText(output, deviceDescription(), "text/xml; charset=utf-8", verb == "HEAD")
            target in setOf("/upnp/content-desc.xml", "/upnp/connection-desc.xml") &&
                (verb == "GET" || verb == "HEAD") ->
                writeText(output, serviceDescription(target), "text/xml; charset=utf-8", verb == "HEAD")
            target in setOf("/upnp/content", "/upnp/connection") && verb == "POST" -> {
                val bodyBytes = ByteArray(length)
                var position = 0
                while (position < bodyBytes.size) {
                    val n = input.read(bodyBytes, position, bodyBytes.size - position)
                    if (n < 0) return
                    position += n
                }
                writeText(
                    output,
                    soapResponse(target, soapAction, String(bodyBytes, StandardCharsets.UTF_8)),
                    "text/xml; charset=utf-8",
                    false
                )
            }
            else -> writeStatus(output, 404, "Not Found")
        }
        output.flush()
    }

    private fun writeVideo(out: BufferedOutputStream, head: Boolean, rangeHeader: String?) {
        val range = parseRange(rangeHeader, size)
        if (range == null) {
            writeStatus(out, 416, "Range Not Satisfiable", "Content-Range: bytes */" + size + "\r\n")
            return
        }
        val (start, end) = range
        val remaining = end - start + 1
        val partial = rangeHeader != null
        val code = if (partial) 206 else 200
        val reason = if (partial) "Partial Content" else "OK"
        val headers = "Content-Type: " + mime + "\r\n" +
            "Content-Length: " + remaining + "\r\n" +
            "Accept-Ranges: bytes\r\n" +
            "Access-Control-Allow-Origin: *\r\n" +
            "transferMode.dlna.org: Streaming\r\n" +
            (if (partial) "Content-Range: bytes " + start + "-" + end + "/" + size + "\r\n" else "")
        out.write(("HTTP/1.1 " + code + " " + reason + "\r\n" + headers +
            "Connection: close\r\n\r\n").toByteArray(StandardCharsets.US_ASCII))
        if (head) return
        context.contentResolver.openFileDescriptor(mediaUri, "r")?.use { descriptor ->
            FileInputStream(descriptor.fileDescriptor).use { stream ->
                skipFully(stream, start)
                val buffer = ByteArray(64 * 1024)
                var left = remaining
                while (left > 0 && !closed) {
                    val count = stream.read(buffer, 0, minOf(buffer.size.toLong(), left).toInt())
                    if (count < 0) break
                    out.write(buffer, 0, count)
                    left -= count
                }
            }
        }
    }

    private fun writeText(out: BufferedOutputStream, body: String, contentType: String, head: Boolean) {
        val data = body.toByteArray(StandardCharsets.UTF_8)
        out.write(("HTTP/1.1 200 OK\r\nContent-Type: " + contentType +
            "\r\nContent-Length: " + data.size + "\r\nConnection: close\r\n\r\n")
            .toByteArray(StandardCharsets.US_ASCII))
        if (!head) out.write(data)
    }

    private fun writeStatus(out: BufferedOutputStream, code: Int, reason: String, extra: String = "") {
        out.write(("HTTP/1.1 " + code + " " + reason + "\r\n" + extra +
            "Content-Length: 0\r\nConnection: close\r\n\r\n")
            .toByteArray(StandardCharsets.US_ASCII))
    }

    private fun deviceDescription(): String = """
        <?xml version="1.0" encoding="utf-8"?>
        <root xmlns="urn:schemas-upnp-org:device-1-0">
          <specVersion><major>1</major><minor>0</minor></specVersion>
          <URLBase>$baseUrl/</URLBase>
          <device>
            <deviceType>urn:schemas-upnp-org:device:MediaServer:1</deviceType>
            <friendlyName>ReiAnix</friendlyName><manufacturer>ReiAnix</manufacturer>
            <modelName>ReiAnix Player</modelName><UDN>uuid:$deviceId</UDN>
            <serviceList>
              <service><serviceType>urn:schemas-upnp-org:service:ContentDirectory:1</serviceType>
                <serviceId>urn:upnp-org:serviceId:ContentDirectory</serviceId>
                <controlURL>/upnp/content</controlURL><eventSubURL>/upnp/events</eventSubURL>
                <SCPDURL>/upnp/content-desc.xml</SCPDURL></service>
              <service><serviceType>urn:schemas-upnp-org:service:ConnectionManager:1</serviceType>
                <serviceId>urn:upnp-org:serviceId:ConnectionManager</serviceId>
                <controlURL>/upnp/connection</controlURL><eventSubURL>/upnp/events</eventSubURL>
                <SCPDURL>/upnp/connection-desc.xml</SCPDURL></service>
            </serviceList>
          </device>
        </root>
    """.trimIndent()

    private fun serviceDescription(path: String): String {
        fun variable(name: String, type: String, event: Boolean = false) =
            "<stateVariable sendEvents=\"" + (if (event) "yes" else "no") + "\">" +
                "<name>" + name + "</name><dataType>" + type + "</dataType></stateVariable>"
        fun arg(name: String, direction: String, variable: String) =
            "<argument><name>" + name + "</name><direction>" + direction +
                "</direction><relatedStateVariable>" + variable +
                "</relatedStateVariable></argument>"
        fun action(name: String, vararg arguments: String) =
            "<action><name>" + name + "</name><argumentList>" +
                arguments.joinToString("") + "</argumentList></action>"
        val actions: String
        val state: String
        if (path.contains("content")) {
            actions = action("GetSearchCapabilities", arg("SearchCaps", "out", "SearchCapabilities")) +
                action("GetSortCapabilities", arg("SortCaps", "out", "SortCapabilities")) +
                action("GetSystemUpdateID", arg("Id", "out", "SystemUpdateID")) +
                action("Browse",
                    arg("ObjectID", "in", "A_ARG_TYPE_ObjectID"),
                    arg("BrowseFlag", "in", "A_ARG_TYPE_BrowseFlag"),
                    arg("Filter", "in", "A_ARG_TYPE_Filter"),
                    arg("StartingIndex", "in", "A_ARG_TYPE_Index"),
                    arg("RequestedCount", "in", "A_ARG_TYPE_Count"),
                    arg("SortCriteria", "in", "A_ARG_TYPE_SortCriteria"),
                    arg("Result", "out", "A_ARG_TYPE_Result"),
                    arg("NumberReturned", "out", "A_ARG_TYPE_Count"),
                    arg("TotalMatches", "out", "A_ARG_TYPE_Count"),
                    arg("UpdateID", "out", "A_ARG_TYPE_UpdateID"))
            state = listOf(variable("SearchCapabilities", "string"),
                variable("SortCapabilities", "string"),
                variable("SystemUpdateID", "ui4", true),
                variable("A_ARG_TYPE_ObjectID", "string"),
                variable("A_ARG_TYPE_BrowseFlag", "string"),
                variable("A_ARG_TYPE_Filter", "string"),
                variable("A_ARG_TYPE_Index", "ui4"),
                variable("A_ARG_TYPE_Count", "ui4"),
                variable("A_ARG_TYPE_SortCriteria", "string"),
                variable("A_ARG_TYPE_Result", "string"),
                variable("A_ARG_TYPE_UpdateID", "ui4")).joinToString("")
        } else {
            actions = action("GetProtocolInfo",
                    arg("Source", "out", "SourceProtocolInfo"),
                    arg("Sink", "out", "SinkProtocolInfo")) +
                action("GetCurrentConnectionIDs",
                    arg("ConnectionIDs", "out", "CurrentConnectionIDs"))
            state = listOf(variable("SourceProtocolInfo", "string", true),
                variable("SinkProtocolInfo", "string", true),
                variable("CurrentConnectionIDs", "string", true)).joinToString("")
        }
        return "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
            "<scpd xmlns=\"urn:schemas-upnp-org:service-1-0\">" +
            "<specVersion><major>1</major><minor>0</minor></specVersion>" +
            "<actionList>" + actions + "</actionList>" +
            "<serviceStateTable>" + state + "</serviceStateTable></scpd>"
    }

    private fun soapResponse(path: String, soapAction: String, request: String): String {
        val action = soapAction.substringAfter('#', "").trim()
        val directory = path == "/upnp/content"
        val ns = if (directory) "urn:schemas-upnp-org:service:ContentDirectory:1"
            else "urn:schemas-upnp-org:service:ConnectionManager:1"
        val inside = if (!directory && action == "GetProtocolInfo") {
            "<Source>http-get:*:" + xml(mime) + ":*</Source><Sink></Sink>"
        } else if (!directory && action == "GetCurrentConnectionIDs") {
            "<ConnectionIDs>0</ConnectionIDs>"
        } else if (directory && action == "GetSystemUpdateID") {
            "<Id>1</Id>"
        } else if (directory && action == "GetSearchCapabilities") {
            "<SearchCaps></SearchCaps>"
        } else if (directory && action == "GetSortCapabilities") {
            "<SortCaps></SortCaps>"
        } else if (directory && action == "Browse") {
            val metadata = request.contains("BrowseMetadata")
            val rootRequested = request.contains("<ObjectID>0</ObjectID>") ||
                request.contains(":ObjectID>0</")
            val didl = if (metadata && rootRequested) rootItem()
                else if (metadata || rootRequested) videoItem()
                else ""
            val result = "<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\" " +
                "xmlns:dc=\"http://purl.org/dc/elements/1.1/\" " +
                "xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\">" +
                didl + "</DIDL-Lite>"
            "<Result>" + xml(result) + "</Result><NumberReturned>" +
                (if (didl.isNotEmpty()) 1 else 0) +
                "</NumberReturned><TotalMatches>" + (if (didl.isNotEmpty()) 1 else 0) +
                "</TotalMatches><UpdateID>1</UpdateID>"
        } else ""
        return "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
            "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" " +
            "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">" +
            "<s:Body><u:" + action + "Response xmlns:u=\"" + ns + "\">" +
            inside + "</u:" + action + "Response></s:Body></s:Envelope>"
    }

    private fun rootItem(): String = "<container id=\"0\" parentID=\"-1\" restricted=\"1\" " +
        "childCount=\"1\"><dc:title>ReiAnix</dc:title>" +
        "<upnp:class>object.container.storageFolder</upnp:class></container>"

    private fun videoItem(): String = "<item id=\"1\" parentID=\"0\" restricted=\"1\">" +
        "<dc:title>" + xml(mediaTitle) + "</dc:title>" +
        "<upnp:class>object.item.videoItem</upnp:class>" +
        "<res protocolInfo=\"http-get:*:" + xml(mime) + ":*\" size=\"" + size + "\">" +
        xml(mediaUrl) + "</res></item>"

    private fun startSsdp() {
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        multicastLock = wifi?.createMulticastLock("ReiAnixTvMediaServer")?.apply {
            setReferenceCounted(false)
            acquire()
        }
        thread(name = "reianix-tv-ssdp", isDaemon = true) {
            try {
                val socket = MulticastSocket(null).apply {
                    reuseAddress = true
                    bind(InetSocketAddress(1900))
                    soTimeout = 2500
                    joinGroup(ssdpGroup)
                }
                ssdpSocket = socket
                advertise(socket, "ssdp:alive")
                val bytes = ByteArray(8192)
                while (!closed) {
                    try {
                        val request = DatagramPacket(bytes, bytes.size)
                        socket.receive(request)
                        val payload = String(request.data, request.offset, request.length,
                            StandardCharsets.UTF_8)
                        if (!payload.startsWith("M-SEARCH", ignoreCase = true)) continue
                        val st = payload.lineSequence().firstOrNull {
                            it.startsWith("ST:", ignoreCase = true)
                        }?.substringAfter(':')?.trim() ?: continue
                        for (target in targets()) {
                            if (st == "ssdp:all" || st.equals(target, ignoreCase = true)) {
                                val usn = "uuid:" + deviceId +
                                    if (target.startsWith("uuid:")) "" else "::" + target
                                val reply = "HTTP/1.1 200 OK\r\nCACHE-CONTROL: max-age=1800\r\n" +
                                    "EXT:\r\nLOCATION: " + baseUrl + "/device.xml\r\n" +
                                    "SERVER: Android/1.0 UPnP/1.0 ReiAnix/1.0\r\n" +
                                    "ST: " + target + "\r\nUSN: " + usn + "\r\n\r\n"
                                val data = reply.toByteArray(StandardCharsets.UTF_8)
                                socket.send(DatagramPacket(data, data.size, request.address, request.port))
                            }
                        }
                    } catch (_: java.net.SocketTimeoutException) {
                        advertise(socket, "ssdp:alive")
                    }
                }
                advertise(socket, "ssdp:byebye")
            } catch (_: Exception) {
                // Local media remains available to direct Cast/renderers even
                // when a router blocks SSDP multicast.
            } finally {
                runCatching { ssdpSocket?.close() }
            }
        }
    }

    private fun targets() = listOf(
        "upnp:rootdevice",
        "uuid:" + deviceId,
        "urn:schemas-upnp-org:device:MediaServer:1",
        "urn:schemas-upnp-org:service:ContentDirectory:1",
        "urn:schemas-upnp-org:service:ConnectionManager:1",
    )

    private fun advertise(socket: MulticastSocket, notice: String) {
        for (target in targets()) {
            val usn = "uuid:" + deviceId + if (target.startsWith("uuid:")) "" else "::" + target
            val body = "NOTIFY * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\n" +
                "NT: " + target + "\r\nNTS: " + notice + "\r\n" +
                "USN: " + usn + "\r\nLOCATION: " + baseUrl + "/device.xml\r\n" +
                "CACHE-CONTROL: max-age=1800\r\n" +
                "SERVER: Android/1.0 UPnP/1.0 ReiAnix/1.0\r\n\r\n"
            val data = body.toByteArray(StandardCharsets.UTF_8)
            runCatching { socket.send(DatagramPacket(data, data.size, ssdpGroup, 1900)) }
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        runCatching { server.close() }
        runCatching { ssdpSocket?.close() }
        runCatching { multicastLock?.release() }
        multicastLock = null
    }

    companion object {
        internal fun parseRange(header: String?, size: Long): Pair<Long, Long>? {
            if (header == null) return 0L to (size - 1)
            val match = Regex("^bytes=(\\d*)-(\\d*)$").matchEntire(header.trim()) ?: return null
            val rawStart = match.groupValues[1]
            val rawEnd = match.groupValues[2]
            if (rawStart.isEmpty() && rawEnd.isEmpty()) return null
            val start: Long
            val end: Long
            if (rawStart.isEmpty()) {
                val suffix = rawEnd.toLongOrNull()?.takeIf { it > 0 } ?: return null
                start = (size - suffix).coerceAtLeast(0)
                end = size - 1
            } else {
                start = rawStart.toLongOrNull()?.takeIf { it < size } ?: return null
                end = rawEnd.toLongOrNull()?.coerceAtMost(size - 1) ?: (size - 1)
            }
            return if (start <= end) start to end else null
        }

        private fun skipFully(stream: InputStream, size: Long) {
            var remaining = size
            while (remaining > 0) {
                val n = stream.skip(remaining)
                if (n > 0) remaining -= n
                else if (stream.read() >= 0) remaining--
                else throw java.io.EOFException("Video ended before requested byte offset")
            }
        }

        private fun readAsciiLine(stream: InputStream): String? {
            val data = java.io.ByteArrayOutputStream()
            while (data.size() < 8192) {
                val b = stream.read()
                if (b < 0) return if (data.size() == 0) null else data.toString("UTF-8")
                if (b == 10) return data.toString("UTF-8").trimEnd('\r')
                data.write(b)
            }
            return null
        }

        private fun xml(value: String) = value.replace("&", "&amp;").replace("<", "&lt;")
            .replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;")

        private fun fileSize(context: Context, uri: Uri): Long {
            require(uri.scheme == "content" || uri.scheme == "file") {
                "Este vídeo não está disponível como arquivo local para a TV."
            }
            context.contentResolver.openAssetFileDescriptor(uri, "r")?.use {
                if (it.length > 0) return it.length
            }
            context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use {
                if (it.moveToFirst()) return it.getLong(0)
            }
            return -1
        }

        private fun mimeType(context: Context, uri: Uri): String {
            val provided = context.contentResolver.getType(uri)?.lowercase()
            if (provided != null && provided.startsWith("video/")) return provided
            return when (uri.lastPathSegment?.substringAfterLast('.')?.lowercase()) {
                "mp4", "m4v" -> "video/mp4"
                "mkv" -> "video/x-matroska"
                "webm" -> "video/webm"
                "ts" -> "video/mp2t"
                "mov" -> "video/quicktime"
                else -> "application/octet-stream"
            }
        }

        private fun privateLanAddress(context: Context): Inet4Address? {
            val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE)
                as? ConnectivityManager ?: return null
            // Android may use cellular as the default route even while Wi-Fi
            // is connected to a TV on an unvalidated/offline local network.
            // Only advertise a Wi-Fi/Ethernet address reachable from the TV.
            val local = connectivity.allNetworks.filter { network ->
                val caps = connectivity.getNetworkCapabilities(network)
                caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true ||
                    caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true
            }
            return local.asSequence().flatMap { network ->
                connectivity.getLinkProperties(network)?.linkAddresses.orEmpty()
                    .asSequence().mapNotNull { it.address as? Inet4Address }
            }.firstOrNull { it.isSiteLocalAddress }
        }
    }
}
