package com.reianix.reianix_local.downloads

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.net.ServerSocket
import kotlin.concurrent.thread

/** Local-only HTTP server: deterministic metadata tests, no external CDN or live links. */
class DownloadHttpMetadataTest {
    private fun withServer(responses:List<(String)->String>, block:(String)->Unit) {
        ServerSocket(0).use {server ->
            server.soTimeout=8000
            val worker=thread(name="metadata-fixture") {
                for(reply in responses) {
                    server.accept().use {socket ->
                        socket.soTimeout=8000
                        val reader=socket.getInputStream().bufferedReader()
                        val request=reader.readLine().orEmpty()
                        var line=reader.readLine()
                        while(!line.isNullOrEmpty())line=reader.readLine()
                        val response=reply(request)
                        socket.getOutputStream().write(response.toByteArray(Charsets.ISO_8859_1))
                        socket.getOutputStream().flush()
                    }
                }
            }
            try {block("http://127.0.0.1:"+server.localPort+"/files/4af0f294e36b91c271e83430.mp4")}
            finally {server.close();worker.join(8000)}
        }
    }

    @Test fun headRejectedThenRangeReturnsExactSizeAndFriendlyName() {
        withServer(listOf(
            { _ -> "HTTP/1.1 405 Method Not Allowed\r\nContent-Length: 0\r\nConnection: close\r\n\r\n" },
            { _ -> "HTTP/1.1 206 Partial Content\r\nContent-Range: bytes 0-0/146800640\r\n" +
                "Content-Length: 1\r\nContent-Type: video/mp4\r\n" +
                "Content-Disposition: attachment; filename*=UTF-8''Solo%20Leveling%20-%20Episodio%2001.mp4\r\n" +
                "Connection: close\r\n\r\nX" },
        )) {url ->
            val result=runBlocking {DownloadSizeProbe.inspect(url,emptyMap())}
            assertEquals(146800640L,result.bytes)
            assertTrue(result.exact)
            assertEquals("Solo Leveling - Episodio 01.mp4",result.name)
        }
    }

    @Test fun rejectingHeadAndRangeFallsBackToPlainGetWithoutDownloadingBody() {
        withServer(listOf(
            { _ -> "HTTP/1.1 405 Method Not Allowed\r\nContent-Length: 0\r\nConnection: close\r\n\r\n" },
            { _ -> "HTTP/1.1 416 Range Not Satisfiable\r\nContent-Length: 0\r\nConnection: close\r\n\r\n" },
            { req ->
                assertTrue(req.startsWith("GET "))
                "HTTP/1.1 200 OK\r\nContent-Length: 146800640\r\nContent-Type: video/mp4\r\n" +
                    "Content-Disposition: attachment; filename=\"Episodio 01.mp4\"\r\nConnection: close\r\n\r\n"
            },
        )) {url ->
            val result=runBlocking {DownloadSizeProbe.inspect(url,emptyMap())}
            assertEquals(146800640L,result.bytes)
            assertEquals("get",result.source)
            assertEquals("Episodio 01.mp4",result.name)
        }
    }
}
