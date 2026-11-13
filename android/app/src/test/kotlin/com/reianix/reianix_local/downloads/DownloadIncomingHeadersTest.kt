package com.reianix.reianix_local.downloads

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.concurrent.thread

/**
 * Reproduces Nuvio Mobile's buildExternalPlayerIntent contract: ACTION_VIEW,
 * setDataAndType(video/*) and putExtra("headers", Array<String>).
 *
 * Old ReiAnix code used getBundleExtra("headers") and lost the String[].
 */
@RunWith(RobolectricTestRunner::class)
class DownloadIncomingHeadersTest {
    private fun nuvioIntent(url:String)=Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(Uri.parse(url),"video/*")
        putExtra("headers",arrayOf(
            "Referer: https://player.example/watch/one",
            "User-Agent: Mobile-Player/1.0",
            "X-Stream-Session: test-session"
        ))
        putExtra(Intent.EXTRA_TITLE,"Example Video - E01")
    }

    @Test fun readsNuvioArrayWithoutChangingSourceUrl() {
        val url="https://media.example/series/id-token"
        val intent=nuvioIntent(url)
        val headers=DownloadIncomingHeaders.parse(intent)
        assertEquals(url,intent.dataString)
        assertEquals("https://player.example/watch/one",headers["Referer"])
        assertEquals("Mobile-Player/1.0",headers["User-Agent"])
        assertEquals("test-session",headers["X-Stream-Session"])
        assertEquals(3,headers.size)
    }

    @Test fun readsBrowserBundleAndHeaderArrayList() {
        val browser=Bundle().apply {
            putString("Referer","https://site.example/watch")
            putString("User-Agent","Browser/99")
        }
        val intent=Intent(Intent.ACTION_VIEW).apply {
            putExtra("com.android.browser.headers",browser)
            putStringArrayListExtra("headers",arrayListOf("Cookie: own=1",
                "User-Agent: App/1"))
        }
        val headers=DownloadIncomingHeaders.parse(intent)
        assertEquals("own=1",headers["Cookie"])
        assertEquals("App/1",headers["User-Agent"])
        assertEquals("https://site.example/watch",headers["Referer"])
    }

    @Test fun dropsTransportOverridesAndInvalidHeaderValues() {
        val intent=Intent(Intent.ACTION_VIEW).apply {
            putExtra("headers",arrayOf(
                "Range: bytes=500-",
                "Host: attacker.example",
                "Connection: upgrade",
                "X-Injected: valid\r\nCookie: secret",
                "Referer: https://player.example/video",
                "X-Additional: accepted"
            ))
        }
        val h=DownloadIncomingHeaders.parse(intent)
        assertFalse(h.keys.any {it.equals("Host",true)||it.equals("Range",true)})
        assertFalse(h.containsKey("X-Injected"))
        assertEquals("https://player.example/video",h["Referer"])
        assertEquals("accepted",h["X-Additional"])
    }

    @Test fun fullGatewayRedirectWithNuvioHeadersReachesMedia() {
        ServerSocket(0,10,InetAddress.getByName("127.0.0.1")).use {server ->
            server.soTimeout=6_000
            val url="http://127.0.0.1:"+server.localPort+"/series/resource"
            val finalUrl="http://127.0.0.1:"+server.localPort+"/cdn/file.mp4"
            val seen=java.util.Collections.synchronizedList(mutableListOf<Map<String,String>>())
            val worker=thread(isDaemon=true) {
                repeat(2) {index ->
                    server.accept().use {socket ->
                        socket.soTimeout=6_000
                        val input=socket.getInputStream().bufferedReader()
                        input.readLine()
                        val headers=linkedMapOf<String,String>()
                        while(true) {
                            val line=input.readLine()?:break
                            if(line.isBlank())break
                            headers[line.substringBefore(':').lowercase()]=
                                line.substringAfter(':').trim()
                        }
                        seen.add(headers)
                        val valid=headers["x-stream-session"]=="test-session" &&
                            headers["referer"]=="https://player.example/watch/one"
                        val response=when {
                            !valid -> "HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                            index==0 -> "HTTP/1.1 302 Found\r\nLocation: /cdn/file.mp4\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                            else -> "HTTP/1.1 206 Partial Content\r\nContent-Type: video/mp4\r\nContent-Range: bytes 0-3/4\r\nContent-Length: 4\r\nConnection: close\r\n\r\nDATA"
                        }
                        socket.getOutputStream().apply{write(response.toByteArray());flush()}
                    }
                }
            }
            val outgoing=nuvioIntent(url)
            val http=DownloadHttp.connect(url,DownloadIncomingHeaders.parse(outgoing),
                0,null,preferInitialRange=true)
            try {
                assertEquals(206,http.responseCode)
                assertEquals(finalUrl,http.url.toString())
                assertEquals(4L,DownloadHttp.range(
                    http.getHeaderField("Content-Range"))?.total)
            }finally{http.disconnect()}
            worker.join(6_000)
            assertFalse(worker.isAlive)
            assertEquals(2,seen.size)
            assertEquals("test-session",seen[0]["x-stream-session"])
            assertEquals("bytes=0-",seen[1]["range"])
        }
    }
    @Test fun metadataProbeIdentifiesRedirectedFinalMediaAndFullLength() {
        ServerSocket(0,10,InetAddress.getByName("127.0.0.1")).use {server ->
            server.soTimeout=6_000
            val source="http://127.0.0.1:"+server.localPort+"/series/resource"
            val direct="http://127.0.0.1:"+server.localPort+"/final.mp4"
            val worker=thread(isDaemon=true) {
                repeat(2) {index ->
                    server.accept().use {socket ->
                        socket.soTimeout=6_000
                        val input=socket.getInputStream().bufferedReader()
                        input.readLine()
                        val headers=linkedMapOf<String,String>()
                        while(true) {
                            val line=input.readLine()?:break
                            if(line.isBlank())break
                            headers[line.substringBefore(':').lowercase()]=
                                line.substringAfter(':').trim()
                        }
                        val response=if(headers["x-stream-session"]!="test-session")
                            "HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                        else if(index==0)
                            "HTTP/1.1 302 Found\r\nLocation: /final.mp4\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                        else "HTTP/1.1 206 Partial Content\r\nContent-Type: video/mp4\r\n"+
                            "Content-Range: bytes 0-3/400000000\r\nContent-Length: 4\r\n"+
                            "Connection: close\r\n\r\nDATA"
                        socket.getOutputStream().apply {write(response.toByteArray());flush()}
                    }
                }
            }
            val headers=DownloadIncomingHeaders.parse(nuvioIntent(source))
            val result=kotlinx.coroutines.runBlocking {
                DownloadSizeProbe.inspect(source,headers)
            }
            worker.join(6_000)
            assertFalse(worker.isAlive)
            assertEquals(400_000_000L,result.bytes)
            assertTrue(result.exact)
            assertEquals(direct,result.resolvedUrl)
        }
    }

}
