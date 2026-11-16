package com.reianix.reianix_local.player.cast

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.provider.OpenableColumns
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Exposes *one* user-selected local video to the connected Cast receiver.
 *
 * The receiver cannot read Android content:// or SAF URIs. HTTP is limited to
 * the current LAN connection, to a random unguessable path, and to GET/HEAD
 * with byte ranges. No directory listing or arbitrary file paths exist here.
 * It performs no transcoding or metadata lookup and never buffers a full video.
 */
internal object LocalCastMediaServer {
    data class PublishedVideo(
        val url: String,
        val mimeType: String,
        val sizeBytes: Long,
        val sourceUri: String,
    )

    private data class Binding(
        val uri: Uri,
        val mimeType: String,
        val size: Long,
        val token: String,
        val socket: ServerSocket,
    ) {
        val pool = Executors.newFixedThreadPool(4) { command ->
            Thread(command, "ReiAnix-Cast-HTTP").apply { isDaemon = true }
        }
    }

    private var active: Binding? = null
    val currentSource: String?
        @Synchronized get() = active?.uri?.toString()

    @Synchronized
    fun publish(context: Context, source: Uri): PublishedVideo {
        val app = context.applicationContext
        val address = localNetworkAddress(app)
            ?: throw IOException("Não foi possível identificar o endereço Wi-Fi do celular")
        val size = contentLength(app, source)
        if (size <= 0L) throw IOException("Tamanho do vídeo indisponível para transmissão")
        val mime = detectMime(app, source)
        if (!mime.startsWith("video/")) {
            throw IOException("O arquivo selecionado não é um vídeo")
        }
        val token = UUID.randomUUID().toString().replace("-", "") +
            UUID.randomUUID().toString().replace("-", "")
        val socket = ServerSocket(0, 12, InetAddress.getByName(address)).apply {
            soTimeout = 1000
        }
        val binding = Binding(source, mime, size, token, socket)
        // Replace the previous episode only after the new endpoint is ready.
        val previous = active
        active = binding
        closeBinding(previous)
        Thread({ serveLoop(app, binding) }, "ReiAnix-Cast-Acceptor").apply {
            isDaemon = true
            start()
        }
        return PublishedVideo(
            "http://$address:${socket.localPort}/media/$token",
            mime,
            size,
            source.toString(),
        )
    }

    @Synchronized
    fun stop() {
        val previous = active
        active = null
        closeBinding(previous)
    }

    private fun closeBinding(binding: Binding?) {
        if (binding == null) return
        runCatching { binding.socket.close() }
        binding.pool.shutdownNow()
    }

    private fun serveLoop(context: Context, binding: Binding) {
        while (!binding.socket.isClosed) {
            try {
                val socket = binding.socket.accept()
                try {
                    binding.pool.execute {
                        socket.use { runCatching { handleRequest(context, binding, it) } }
                    }
                } catch (_: java.util.concurrent.RejectedExecutionException) {
                    socket.close()
                }
            } catch (_: java.net.SocketTimeoutException) {
                // Check whether a disconnect closed this server.
            } catch (_: IOException) {
                break
            }
        }
    }

    private fun handleRequest(context: Context, binding: Binding, socket: Socket) {
        socket.soTimeout = 12_000
        val input = BufferedInputStream(socket.getInputStream())
        val output = BufferedOutputStream(socket.getOutputStream())
        val request = readAsciiLine(input) ?: return
        val parts = request.split(' ')
        if (parts.size < 3 || parts[2] != "HTTP/1.1" && parts[2] != "HTTP/1.0") {
            respond(output, 400, "Bad Request")
            return
        }
        var range: String? = null
        for (index in 0 until 40) {
            val header = readAsciiLine(input) ?: return
            if (header.isEmpty()) break
            if (header.startsWith("Range:", ignoreCase = true)) range = header.substringAfter(':').trim()
        }
        val method = parts[0]
        if (method != "GET" && method != "HEAD" && method != "OPTIONS") {
            respond(output, 405, "Method Not Allowed")
            return
        }
        if (parts[1] != "/media/${binding.token}") {
            respond(output, 404, "Not Found")
            return
        }
        // Cast Web Receivers use CORS preflight for tracks and range requests.
        // The secret route is still required even for preflight requests.
        if (method == "OPTIONS") {
            output.write(("HTTP/1.1 204 No Content\r\n" +
                "Access-Control-Allow-Origin: *\r\n" +
                "Access-Control-Allow-Methods: GET, HEAD, OPTIONS\r\n" +
                "Access-Control-Allow-Headers: Range, Accept-Encoding, Content-Type\r\n" +
                "Access-Control-Max-Age: 600\r\n" +
                "Content-Length: 0\r\nConnection: close\r\n\r\n")
                .toByteArray(StandardCharsets.US_ASCII))
            output.flush()
            return
        }
        val parsed = try { parseRange(range, binding.size) } catch (_: IOException) {
            output.write(("HTTP/1.1 416 Range Not Satisfiable\r\n" +
                "Content-Range: bytes */${binding.size}\r\n" +
                "Access-Control-Allow-Origin: *\r\nContent-Length: 0\r\n\r\n")
                .toByteArray(StandardCharsets.US_ASCII))
            output.flush()
            return
        }
        val (start, end) = parsed
        val partial = range != null
        val length = end - start + 1L
        val header = buildString {
            append(if (partial) "HTTP/1.1 206 Partial Content\r\n" else "HTTP/1.1 200 OK\r\n")
            append("Content-Type: ${binding.mimeType}\r\n")
            append("Accept-Ranges: bytes\r\n")
            append("Access-Control-Allow-Origin: *\r\n")
            append("Access-Control-Expose-Headers: Content-Length, Content-Range, Accept-Ranges\r\n")
            append("Content-Length: $length\r\n")
            if (partial) append("Content-Range: bytes $start-$end/${binding.size}\r\n")
            append("Cache-Control: private, no-store\r\n")
            append("Connection: close\r\n\r\n")
        }
        output.write(header.toByteArray(StandardCharsets.US_ASCII))
        output.flush()
        if (method == "HEAD") return

        context.contentResolver.openFileDescriptor(binding.uri, "r")?.use { descriptor ->
            java.io.FileInputStream(descriptor.fileDescriptor).use { file ->
                val channel = file.channel
                try {
                    channel.position(start)
                } catch (_: IOException) {
                    var remainingSkip = start
                    while (remainingSkip > 0L) {
                        val skipped = file.skip(remainingSkip)
                        if (skipped <= 0L) throw IOException("Provedor não permite avançar no arquivo")
                        remainingSkip -= skipped
                    }
                }
                val buffer = ByteArray(64 * 1024)
                var remaining = length
                while (remaining > 0L && !Thread.currentThread().isInterrupted) {
                    val count = file.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                    if (count < 0) break
                    output.write(buffer, 0, count)
                    remaining -= count
                }
                output.flush()
            }
        } ?: throw IOException("Permissão de leitura do arquivo foi revogada")
    }

    private fun readAsciiLine(input: InputStream): String? {
        val bytes = java.io.ByteArrayOutputStream()
        while (bytes.size() < 8192) {
            val b = input.read()
            if (b < 0) return null
            if (b == 10) return bytes.toString("US-ASCII").trimEnd('\r')
            bytes.write(b)
        }
        throw IOException("Cabeçalho HTTP muito grande")
    }

    private fun respond(output: BufferedOutputStream, code: Int, reason: String) {
        output.write(("HTTP/1.1 $code $reason\r\nContent-Length: 0\r\n" +
            "Connection: close\r\n\r\n").toByteArray(StandardCharsets.US_ASCII))
        output.flush()
    }

    /** Supports full, prefix and suffix single-range requests. */
    internal fun parseRange(header: String?, total: Long): Pair<Long, Long> {
        if (total <= 0L) throw IOException("Empty file")
        if (header == null) return 0L to total - 1
        val match = Regex("^bytes=(\\d*)-(\\d*)$").matchEntire(header)
            ?: throw IOException("Unsupported Range")
        val left = match.groupValues[1]
        val right = match.groupValues[2]
        if (left.isEmpty() && right.isEmpty()) throw IOException("Empty Range")
        val start: Long
        val end: Long
        if (left.isEmpty()) {
            val count = right.toLongOrNull()?.takeIf { it > 0L } ?: throw IOException("Bad suffix")
            start = (total - count).coerceAtLeast(0L)
            end = total - 1L
        } else {
            start = left.toLongOrNull() ?: throw IOException("Bad start")
            end = if (right.isEmpty()) total - 1L else
                right.toLongOrNull()?.coerceAtMost(total - 1L) ?: throw IOException("Bad end")
        }
        if (start >= total || end < start) throw IOException("Out of range")
        return start to end
    }

    private fun contentLength(context: Context, uri: Uri): Long {
        val fdSize = context.contentResolver.openFileDescriptor(uri, "r")
            ?.use { it.statSize } ?: -1L
        if (fdSize > 0L) return fdSize
        return context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else -1L
            } ?: -1L
    }

    private fun detectMime(context: Context, uri: Uri): String {
        val fromProvider = context.contentResolver.getType(uri)
        if (fromProvider?.startsWith("video/") == true) return fromProvider
        val path = uri.lastPathSegment?.lowercase().orEmpty()
        return when {
            path.endsWith(".mp4") || path.endsWith(".m4v") -> "video/mp4"
            path.endsWith(".webm") -> "video/webm"
            path.endsWith(".mkv") -> "video/x-matroska"
            path.endsWith(".ts") -> "video/mp2t"
            path.endsWith(".mov") -> "video/quicktime"
            else -> fromProvider ?: "application/octet-stream"
        }
    }

    private fun localNetworkAddress(context: Context): String? {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val network = manager?.activeNetwork
        val capabilities = network?.let { manager.getNetworkCapabilities(it) }
        if (capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true ||
            capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true
        ) {
            val local = network?.let { manager.getLinkProperties(it) }?.linkAddresses
                ?.map { it.address }
                ?.firstOrNull { it is Inet4Address && it.isSiteLocalAddress }
            if (local != null) return local.hostAddress
        }
        return NetworkInterface.getNetworkInterfaces()?.toList()
            ?.filter { runCatching { it.isUp && !it.isLoopback && !it.isVirtual }.getOrDefault(false) }
            ?.sortedBy { if (it.name.startsWith("wlan") || it.name.startsWith("eth")) 0 else 1 }
            ?.flatMap { it.inetAddresses.toList() }
            ?.firstOrNull { it is Inet4Address && it.isSiteLocalAddress }
            ?.hostAddress
    }
}
