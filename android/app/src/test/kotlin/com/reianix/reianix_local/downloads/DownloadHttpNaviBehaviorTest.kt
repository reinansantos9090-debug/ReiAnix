package com.reianix.reianix_local.downloads

import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.concurrent.thread

/** A local HTTP server validates real request headers and response fallback. */
class DownloadHttpNaviBehaviorTest {
    private inline fun <R> java.net.HttpURLConnection.withDisconnect(
        block:(java.net.HttpURLConnection)->R
    ):R=try {block(this)} finally {disconnect()}

    private data class Seen(val line:String,val headers:Map<String,String>) {
        fun get(name:String)=headers[name.lowercase()].orEmpty()
    }
    private fun serve(responses:List<String>,client:(String,List<Seen>)->Unit) {
        ServerSocket(0,10,InetAddress.getByName("127.0.0.1")).use {server ->
            server.soTimeout=8_000
            val received=java.util.Collections.synchronizedList(mutableListOf<Seen>())
            val worker=thread(isDaemon=true) {
                for(response in responses) {
                    server.accept().use {socket ->
                        socket.soTimeout=8_000
                        val lines=socket.getInputStream().bufferedReader(Charsets.ISO_8859_1)
                        val request=lines.readLine()
                        val map=linkedMapOf<String,String>()
                        while(true) {
                            val line=lines.readLine() ?: break
                            if(line.isEmpty())break
                            map[line.substringBefore(':').lowercase()]=line.substringAfter(':').trim()
                        }
                        received.add(Seen(request.orEmpty(),map))
                        socket.getOutputStream().apply {
                            write(response.toByteArray(Charsets.ISO_8859_1))
                            flush()
                        }
                    }
                }
            }
            try {
                client("http://127.0.0.1:"+server.localPort+"/media.mp4",received)
                worker.join(8_000)
                assertFalse("server did not finish accepting requests",worker.isAlive)
            } finally {
                if(worker.isAlive)worker.interrupt()
            }
        }
    }

    @Test fun firstGetRequestsOpenEndedRangeAndAccepts206() = serve(
        listOf("HTTP/1.1 206 Partial Content\r\nContent-Range: bytes 0-3/4\r\nContent-Length: 4\r\nConnection: close\r\n\r\nDATA")
    ) {url,seen ->
        DownloadHttp.connect(url,emptyMap(),0,null,
            preferInitialRange=true,closeAfterResponse=true).withDisconnect {response ->
            assertEquals(206,response.responseCode)
            assertEquals(4L,DownloadHttp.range(response.getHeaderField("Content-Range"))?.total)
        }
        assertEquals(1,seen.size)
        assertEquals("bytes=0-",seen[0].get("Range"))
        assertEquals("close",seen[0].get("Connection"))
    }

    @Test fun badRangeFallsBackToPlainGetWithSameFirstPartySession() = serve(
        listOf("HTTP/1.1 416 Range Not Satisfiable\r\nContent-Length: 0\r\nConnection: close\r\n\r\n",
            "HTTP/1.1 200 OK\r\nContent-Length: 4\r\nContent-Type: video/mp4\r\nConnection: close\r\n\r\nDATA")
    ) {url,seen ->
        val headers=mapOf("Cookie" to "valid=own","User-Agent" to "ReiAnix-Test",
            "Accept-Language" to "pt-BR","X-Source-App" to "ReiAnix")
        DownloadHttp.connect(url,headers,0,null,
            preferInitialRange=true).withDisconnect {response ->
            assertEquals(200,response.responseCode)
            assertEquals("DATA",response.inputStream.bufferedReader().readText())
        }
        assertEquals(2,seen.size)
        assertEquals("bytes=0-",seen[0].get("Range"))
        assertEquals("",seen[1].get("Range"))
        assertEquals("valid=own",seen[1].get("Cookie"))
        assertEquals("pt-BR",seen[1].get("Accept-Language"))
        assertEquals("ReiAnix",seen[1].get("X-Source-App"))
    }

    @Test fun authorizationDenialIsNotRetriedWithAlternateRequest() = serve(
        listOf("HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
    ) {url,seen ->
        DownloadHttp.connect(url,emptyMap(),0,null,preferInitialRange=true).withDisconnect {
            assertEquals(403,it.responseCode)
        }
        assertEquals(1,seen.size)
        assertEquals("bytes=0-",seen[0].get("Range"))
    }

    @Test fun invalidPartialResponseFallsBackToPlainGet() = serve(
        listOf("HTTP/1.1 206 Partial Content\r\nContent-Range: bytes */*\r\nContent-Length: 0\r\nConnection: close\r\n\r\n",
            "HTTP/1.1 200 OK\r\nContent-Length: 4\r\nConnection: close\r\n\r\nDATA")
    ) {url,seen ->
        DownloadHttp.connect(url,emptyMap(),0,null,preferInitialRange=true).withDisconnect {
            assertEquals(200,it.responseCode)
        }
        assertEquals(2,seen.size)
        assertEquals("",seen[1].get("Range"))
    }
    @Test fun crossOriginRedirectDropsCookiesAuthorizationAndCustomHeaders() {
        ServerSocket(0,10,InetAddress.getByName("127.0.0.1")).use {first ->
            ServerSocket(0,10,InetAddress.getByName("127.0.0.1")).use {second ->
                first.soTimeout=8_000
                second.soTimeout=8_000
                val received=java.util.Collections.synchronizedMap(mutableMapOf<String,String>())
                fun request(socket:java.net.Socket):Map<String,String> {
                    val input=socket.getInputStream().bufferedReader(Charsets.ISO_8859_1)
                    input.readLine()
                    val headers=linkedMapOf<String,String>()
                    while(true) {
                        val row=input.readLine() ?: break
                        if(row.isEmpty())break
                        headers[row.substringBefore(':').lowercase()]=row.substringAfter(':').trim()
                    }
                    return headers
                }
                val firstWorker=thread(isDaemon=true) {
                    first.accept().use {socket ->
                        request(socket)
                        val location="http://127.0.0.1:"+second.localPort+"/final.mp4"
                        socket.getOutputStream().write((
                            "HTTP/1.1 302 Found\r\nLocation: "+location+
                            "\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                        ).toByteArray())
                    }
                }
                val secondWorker=thread(isDaemon=true) {
                    second.accept().use {socket ->
                        received.putAll(request(socket))
                        socket.getOutputStream().write(
                            "HTTP/1.1 200 OK\r\nContent-Length: 4\r\nConnection: close\r\n\r\nDATA".toByteArray()
                        )
                    }
                }
                val url="http://127.0.0.1:"+first.localPort+"/media.mp4"
                DownloadHttp.connect(url,mapOf(
                    "Cookie" to "secret=session",
                    "Authorization" to "Bearer secret",
                    "Origin" to "https://example.com",
                    "X-Private-Session" to "secret",
                    "User-Agent" to "ReiAnix-Test"
                ),0,null,preferInitialRange=true).withDisconnect {
                    assertEquals(200,it.responseCode)
                }
                firstWorker.join(8_000);secondWorker.join(8_000)
                assertFalse(firstWorker.isAlive);assertFalse(secondWorker.isAlive)
                assertNull(received["cookie"])
                assertNull(received["authorization"])
                assertNull(received["origin"])
                assertNull(received["x-private-session"])
                assertEquals("ReiAnix-Test",received["user-agent"])
            }
        }
    }

}
