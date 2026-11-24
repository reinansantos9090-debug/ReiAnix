package com.reianix.reianix_local.player.tv

import android.app.Activity
import android.net.Uri
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.mediarouter.media.MediaRouteSelector
import androidx.mediarouter.media.MediaRouter
import com.google.android.gms.cast.CastMediaControlIntent
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.SessionManagerListener
import com.google.android.gms.cast.MediaLoadRequestData
import java.io.Closeable
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

internal enum class ReiAnixTvKind { CAST, RENDERER, ROKU }

internal data class ReiAnixTvDevice(
    val id: String,
    val name: String,
    val kind: ReiAnixTvKind,
    val controlUrl: String = "",
    val serviceType: String = "",
)

internal sealed class ReiAnixTvConnection {
    data class Connected(val name: String, val device: ReiAnixTvDevice) : ReiAnixTvConnection()
    data class RokuReady(val name: String, val title: String) : ReiAnixTvConnection()
    data class Failure(val message: String) : ReiAnixTvConnection()
}

/**
 * One device list for Cast, UPnP AVTransport renderers and Roku Media Player.
 * Roku's official local-network support is DLNA *browsing*, not push rendering.
 */
internal class ReiAnixTvSender(
    private val activity: Activity,
    private val sourceUri: Uri,
    private val mediaTitle: String,
    private val playPositionMs: Long,
    private val onDevicesChanged: (List<ReiAnixTvDevice>) -> Unit,
    private val onConnectionChanged: (ReiAnixTvConnection) -> Unit,
) : Closeable {
    private val ui = Handler(Looper.getMainLooper())
    private val devices = linkedMapOf<String, ReiAnixTvDevice>()
    private val routes = mutableMapOf<String, MediaRouter.RouteInfo>()
    private var router: MediaRouter? = null
    private var selector: MediaRouteSelector? = null
    private var cast: CastContext? = null
    private var callback: MediaRouter.Callback? = null
    private var mediaServer: ReiAnixTvMediaServer? = null
    private var connected: ReiAnixTvDevice? = null
    private var connecting: String? = null
    @Volatile private var searchGeneration = 0
    @Volatile private var closed = false
    private var sessionListener: SessionManagerListener<CastSession>? = null

    val activeDevice: ReiAnixTvDevice? get() = connected

    fun discover() {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (closed) return
        val generation = ++searchGeneration
        devices.clear()
        addCastRoutes()
        publish()
        Thread({
            discoverSsdp(generation)
        }, "reianix-tv-discovery").apply { isDaemon = true; start() }
    }

    private fun addCastRoutes() {
        if (router == null) {
            runCatching {
                val context = CastContext.getSharedInstance(activity.applicationContext)
                cast = context
                val selector = MediaRouteSelector.Builder()
                    .addControlCategory(
                        CastMediaControlIntent.categoryForCast(
                            CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID
                        )
                    ).build()
                this.selector = selector
                val mediaRouter = MediaRouter.getInstance(activity)
                router = mediaRouter
                val listener = object : MediaRouter.Callback() {
                    override fun onRouteAdded(router: MediaRouter, route: MediaRouter.RouteInfo) = refreshCastRoutes()
                    override fun onRouteRemoved(router: MediaRouter, route: MediaRouter.RouteInfo) = refreshCastRoutes()
                    override fun onRouteChanged(router: MediaRouter, route: MediaRouter.RouteInfo) = refreshCastRoutes()
                }
                callback = listener
                mediaRouter.addCallback(selector, listener, MediaRouter.CALLBACK_FLAG_REQUEST_DISCOVERY)
                val castListener = object : SessionManagerListener<CastSession> {
                    override fun onSessionStarting(session: CastSession) {}
                    override fun onSessionStarted(session: CastSession, sessionId: String) {
                        val pending = connecting ?: return
                        if (pending.startsWith("cast:")) loadCastMedia(session, pending)
                    }
                    override fun onSessionStartFailed(session: CastSession, error: Int) {
                        if (connecting?.startsWith("cast:") == true) {
                            connecting = null
                            onConnectionChanged(ReiAnixTvConnection.Failure("Não foi possível conectar à TV."))
                        }
                    }
                    override fun onSessionEnding(session: CastSession) {}
                    override fun onSessionEnded(session: CastSession, error: Int) {
                        if (connected?.kind == ReiAnixTvKind.CAST) connected = null
                    }
                    override fun onSessionResuming(session: CastSession, sessionId: String) {}
                    override fun onSessionResumed(session: CastSession, wasSuspended: Boolean) {
                        val pending = connecting ?: return
                        if (pending.startsWith("cast:")) loadCastMedia(session, pending)
                    }
                    override fun onSessionResumeFailed(session: CastSession, error: Int) {
                        if (connecting?.startsWith("cast:") == true) {
                            connecting = null
                            onConnectionChanged(ReiAnixTvConnection.Failure("Não foi possível retomar a transmissão."))
                        }
                    }
                    override fun onSessionSuspended(session: CastSession, reason: Int) {}
                }
                sessionListener = castListener
                context.sessionManager.addSessionManagerListener(castListener, CastSession::class.java)
            }.onFailure {
                // Google Play services may be missing. UPnP and Roku still work.
            }
        }
        refreshCastRoutes()
    }

    private fun refreshCastRoutes() {
        if (closed) return
        val router = router ?: return
        val selector = selector ?: return
        routes.clear()
        devices.keys.filter { it.startsWith("cast:") }.forEach(devices::remove)
        for (route in router.routes) {
            if (!route.isEnabled || route.isDefault || !route.matchesSelector(selector)) continue
            val id = "cast:" + route.id
            val name = route.name.trim()
            // A hybrid TV may be found through both Cast and SSDP. Cast takes
            // precedence, so do not present two rows for the same TV.
            devices.values.filter {
                it.kind != ReiAnixTvKind.CAST &&
                    it.name.trim().equals(name, ignoreCase = true)
            }.map { it.id }.forEach(devices::remove)
            routes[id] = route
            devices[id] = ReiAnixTvDevice(id, name, ReiAnixTvKind.CAST)
        }
        publish()
    }

    private fun discoverSsdp(generation: Int) {
        val target = InetAddress.getByName("239.255.255.250")
        val seen = hashSetOf<String>()
        runCatching {
            DatagramSocket().use { socket ->
                val connectivity = activity.getSystemService(Context.CONNECTIVITY_SERVICE)
                    as? ConnectivityManager
                val localNetwork = connectivity?.allNetworks?.firstOrNull { network ->
                    val caps = connectivity.getNetworkCapabilities(network)
                    caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true ||
                        caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true
                }
                localNetwork?.bindSocket(socket)
                socket.soTimeout = 750
                for (search in listOf("ssdp:all", "urn:schemas-upnp-org:device:MediaRenderer:1", "roku:ecp")) {
                    val query = "M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\n" +
                        "MAN: \"ssdp:discover\"\r\nMX: 2\r\nST: " + search + "\r\n\r\n"
                    val data = query.toByteArray(StandardCharsets.US_ASCII)
                    socket.send(DatagramPacket(data, data.size, target, 1900))
                }
                val deadline = System.currentTimeMillis() + 8_000
                val buffer = ByteArray(12 * 1024)
                while (!closed && generation == searchGeneration &&
                    System.currentTimeMillis() < deadline && seen.size < 32
                ) {
                    try {
                        val reply = DatagramPacket(buffer, buffer.size)
                        socket.receive(reply)
                        if (!reply.address.isSiteLocalAddress) continue
                        val raw = String(reply.data, reply.offset, reply.length, StandardCharsets.UTF_8)
                        val headers = raw.lineSequence().mapNotNull {
                            val colon = it.indexOf(':')
                            if (colon <= 0) null else it.substring(0, colon).trim().lowercase() to
                                it.substring(colon + 1).trim()
                        }.toMap()
                        val location = headers["location"] ?: continue
                        if (!seen.add(location + "|" + headers["st"])) continue
                        val discovered = if (headers["st"]?.contains("roku:ecp", true) == true ||
                            headers["usn"]?.contains("roku:ecp", true) == true
                        ) rokuDevice(reply.address.hostAddress ?: continue)
                        else rendererDevice(location, reply.address.hostAddress ?: continue)
                        if (discovered != null) ui.post {
                            if (generation != searchGeneration || closed) return@post
                            // Deduplicate by friendly name; Cast takes precedence where both exist.
                            val normalized = discovered.name.trim().lowercase(Locale.ROOT)
                            val duplicate = devices.values.firstOrNull {
                                it.name.trim().lowercase(Locale.ROOT) == normalized
                            }
                            if (duplicate == null || duplicate.kind != ReiAnixTvKind.CAST) {
                                if (duplicate != null) devices.remove(duplicate.id)
                                devices[discovered.id] = discovered
                                publish()
                            }
                        }
                    } catch (_: java.net.SocketTimeoutException) {
                        // Discovery remains open for a few seconds.
                    }
                }
            }
        }
    }

    private fun rendererDevice(location: String, sourceIp: String): ReiAnixTvDevice? {
        val url = URL(location)
        if (url.protocol != "http" || url.host != sourceIp || !localHost(url.host)) return null
        val xml = readUrl(url, 96_000) ?: return null
        if (!xml.contains("MediaRenderer", true)) return null
        val doc = safeXml(xml)
        val friendly = doc.getElementsByTagName("friendlyName").item(0)?.textContent
            ?.trim()?.take(100).orEmpty()
        if (friendly.isBlank()) return null
        val services = doc.getElementsByTagName("service")
        for (i in 0 until services.length) {
            val service = services.item(i) as? Element ?: continue
            val type = service.getElementsByTagName("serviceType").item(0)?.textContent.orEmpty()
            if (!type.contains("AVTransport")) continue
            val control = service.getElementsByTagName("controlURL").item(0)?.textContent.orEmpty()
            val resolved = URL(url, control)
            if (resolved.protocol != "http" || !localHost(resolved.host) ||
                resolved.host != sourceIp) continue
            return ReiAnixTvDevice(
                "dlna:" + sourceIp + ":" + resolved.port,
                friendly,
                ReiAnixTvKind.RENDERER,
                resolved.toString(),
                type,
            )
        }
        return null
    }

    private fun rokuDevice(ip: String): ReiAnixTvDevice? {
        if (!localHost(ip)) return null
        val name = runCatching {
            val xml = readUrl(URL("http://" + ip + ":8060/query/device-info"), 32_000)
                ?: return@runCatching null
            val doc = safeXml(xml)
            sequenceOf("user-device-name", "friendly-device-name", "model-name")
                .mapNotNull { doc.getElementsByTagName(it).item(0)?.textContent }
                .firstOrNull { it.isNotBlank() }?.trim()?.take(100)
        }.getOrNull() ?: "Roku TV"
        return ReiAnixTvDevice("roku:" + ip, name, ReiAnixTvKind.ROKU)
    }

    private fun publish() {
        if (closed) return
        // Present one unified list: no transport technology labels.
        onDevicesChanged(devices.values.sortedWith(
            compareBy<ReiAnixTvDevice> { it.name.lowercase(Locale.ROOT) }.thenBy { it.id }
        ))
    }

    fun connect(device: ReiAnixTvDevice) {
        if (closed || connecting != null) return
        connecting = device.id
        Thread({
            val result = runCatching {
                mediaServer ?: ReiAnixTvMediaServer(activity.applicationContext,
                    sourceUri, mediaTitle).also { mediaServer = it }
            }
            ui.post {
                if (closed || connecting != device.id) return@post
                val server = result.getOrElse {
                    connecting = null
                    onConnectionChanged(ReiAnixTvConnection.Failure(
                        it.message ?: "Não foi possível compartilhar este vídeo."
                    ))
                    return@post
                }
                when (device.kind) {
                    ReiAnixTvKind.ROKU -> {
                        connected = device
                        connecting = null
                        onConnectionChanged(ReiAnixTvConnection.RokuReady(device.name, mediaTitle))
                    }
                    ReiAnixTvKind.CAST -> {
                        val route = routes[device.id]
                        val session = cast?.sessionManager?.currentCastSession
                        if (route == null) {
                            connecting = null
                            onConnectionChanged(ReiAnixTvConnection.Failure("TV não está mais disponível."))
                        } else if (session?.isConnected == true &&
                            router?.selectedRoute?.id == route.id
                        ) loadCastMedia(session, device.id)
                        else {
                            router?.selectRoute(route)
                            ui.postDelayed({
                                if (connecting == device.id) {
                                    connecting = null
                                    onConnectionChanged(ReiAnixTvConnection.Failure(
                                        "Tempo esgotado ao conectar à TV."
                                    ))
                                }
                            }, 20_000)
                        }
                    }
                    ReiAnixTvKind.RENDERER -> {
                        Thread({
                            val sent = runCatching {
                                sendDlna(device, server.mediaUrl, "SetAVTransportURI")
                                sendDlna(device, "", "Play")
                            }
                            ui.post {
                                if (closed || connecting != device.id) return@post
                                connecting = null
                                if (sent.isSuccess) {
                                    connected = device
                                    onConnectionChanged(ReiAnixTvConnection.Connected(device.name, device))
                                } else onConnectionChanged(ReiAnixTvConnection.Failure(
                                    sent.exceptionOrNull()?.message ?: "A TV não aceitou o vídeo."
                                ))
                            }
                        }, "reianix-tv-dlna-connect").apply { isDaemon = true; start() }
                    }
                }
            }
        }, "reianix-tv-media-start").apply { isDaemon = true; start() }
    }

    private fun loadCastMedia(session: CastSession, pendingId: String) {
        val server = mediaServer ?: return
        val device = devices[pendingId] ?: return
        val client = session.remoteMediaClient ?: run {
            connecting = null
            onConnectionChanged(ReiAnixTvConnection.Failure("A TV não oferece reprodução de mídia."))
            return
        }
        val metadata = MediaMetadata(MediaMetadata.MEDIA_TYPE_MOVIE).apply {
            putString(MediaMetadata.KEY_TITLE, mediaTitle)
        }
        val media = MediaInfo.Builder(server.mediaUrl)
            .setStreamType(MediaInfo.STREAM_TYPE_BUFFERED)
            .setContentType(guessMime(sourceUri))
            .setMetadata(metadata)
            .build()
        client.load(MediaLoadRequestData.Builder()
            .setMediaInfo(media)
            .setAutoplay(true)
            .setCurrentTime(playPositionMs.coerceAtLeast(0L))
            .build()).setResultCallback { response ->
            if (closed || connecting != pendingId) return@setResultCallback
            connecting = null
            if (response.status.isSuccess) {
                connected = device
                onConnectionChanged(ReiAnixTvConnection.Connected(device.name, device))
            } else onConnectionChanged(ReiAnixTvConnection.Failure(
                "A TV recusou o vídeo. Verifique se o formato é compatível."
            ))
        }
    }

    fun pauseOrResume(resume: Boolean, result: (String?) -> Unit) {
        val device = connected ?: return result("Nenhuma TV conectada.")
        if (device.kind == ReiAnixTvKind.ROKU) return result("Use o controle remoto da TV.")
        if (device.kind == ReiAnixTvKind.CAST) {
            val client = cast?.sessionManager?.currentCastSession?.remoteMediaClient
            if (client == null) return result("Conexão com a TV perdida.")
            if (resume) client.play() else client.pause()
            return result(null)
        }
        Thread({
            val error = runCatching { sendDlna(device, "", if (resume) "Play" else "Pause") }
                .exceptionOrNull()?.message
            ui.post { result(error) }
        }, "reianix-tv-playback").apply { isDaemon = true; start() }
    }

    fun disconnect() {
        val device = connected ?: return
        connecting = null
        connected = null
        if (device.kind == ReiAnixTvKind.CAST) {
            cast?.sessionManager?.endCurrentSession(true)
        } else if (device.kind == ReiAnixTvKind.RENDERER) {
            Thread({ runCatching { sendDlna(device, "", "Stop") } },
                "reianix-tv-stop").apply { isDaemon = true; start() }
        }
        mediaServer?.close()
        mediaServer = null
    }

    private fun sendDlna(device: ReiAnixTvDevice, media: String, operation: String) {
        val body = when (operation) {
            "SetAVTransportURI" -> {
                val mime = guessMime(sourceUri)
                val didl = "<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\" " +
                    "xmlns:dc=\"http://purl.org/dc/elements/1.1/\" " +
                    "xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\">" +
                    "<item id=\"1\" parentID=\"0\" restricted=\"1\"><dc:title>" + xml(mediaTitle) +
                    "</dc:title><upnp:class>object.item.videoItem</upnp:class>" +
                    "<res protocolInfo=\"http-get:*:" + xml(mime) + ":*\">" +
                    xml(media) + "</res></item></DIDL-Lite>"
                "<CurrentURI>" + xml(media) + "</CurrentURI><CurrentURIMetaData>" +
                    xml(didl) + "</CurrentURIMetaData>"
            }
            "Play" -> "<Speed>1</Speed>"
            else -> ""
        }
        val request = "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
            "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" " +
            "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">" +
            "<s:Body><u:" + operation + " xmlns:u=\"" + device.serviceType + "\">" +
            "<InstanceID>0</InstanceID>" + body + "</u:" + operation + "></s:Body></s:Envelope>"
        val url = URL(device.controlUrl)
        require(url.protocol == "http" && localHost(url.host)) { "Endereço de TV inválido." }
        val conn = url.openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 5000
            conn.readTimeout = 6000
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"")
            conn.setRequestProperty("SOAPACTION", "\"" + device.serviceType + "#" + operation + "\"")
            conn.doOutput = true
            conn.outputStream.use { it.write(request.toByteArray(StandardCharsets.UTF_8)) }
            if (conn.responseCode !in 200..299) {
                throw IllegalStateException("A TV não aceitou a transmissão (" + conn.responseCode + ").")
            }
        } finally {
            conn.disconnect()
        }
    }

    override fun close() {
        if (closed) return
        // Stop the remote receiver before the temporary HTTP source disappears.
        disconnect()
        closed = true
        searchGeneration++
        callback?.let { runCatching { router?.removeCallback(it) } }
        sessionListener?.let { listener ->
            runCatching { cast?.sessionManager?.removeSessionManagerListener(listener, CastSession::class.java) }
        }
        mediaServer?.close()
        mediaServer = null
        devices.clear()
        routes.clear()
    }

    private fun guessMime(uri: Uri): String = when (uri.lastPathSegment?.substringAfterLast('.')?.lowercase()) {
        "mp4", "m4v" -> "video/mp4"
        "mkv" -> "video/x-matroska"
        "webm" -> "video/webm"
        "ts" -> "video/mp2t"
        else -> activity.contentResolver.getType(uri) ?: "video/mp4"
    }

    companion object {
        private fun localHost(host: String): Boolean = runCatching {
            InetAddress.getByName(host).isSiteLocalAddress
        }.getOrDefault(false)

        private fun xml(value: String) = value.replace("&", "&amp;").replace("<", "&lt;")
            .replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;")

        private fun readUrl(url: URL, limit: Int): String? {
            val conn = url.openConnection() as HttpURLConnection
            try {
                conn.connectTimeout = 2500
                conn.readTimeout = 3000
                conn.instanceFollowRedirects = false
                if (conn.responseCode != 200) return null
                return conn.inputStream.use { stream ->
                    val result = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(4096)
                    while (result.size() <= limit) {
                        val count = stream.read(buffer, 0,
                            minOf(buffer.size, limit + 1 - result.size()))
                        if (count < 0) break
                        result.write(buffer, 0, count)
                    }
                    if (result.size() > limit) null
                    else String(result.toByteArray(), StandardCharsets.UTF_8)
                }
            } finally { conn.disconnect() }
        }

        private fun safeXml(raw: String): org.w3c.dom.Document {
            val factory = DocumentBuilderFactory.newInstance()
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false)
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            factory.isXIncludeAware = false
            factory.isExpandEntityReferences = false
            return factory.newDocumentBuilder().parse(
                raw.byteInputStream(StandardCharsets.UTF_8)
            )
        }
    }
}
