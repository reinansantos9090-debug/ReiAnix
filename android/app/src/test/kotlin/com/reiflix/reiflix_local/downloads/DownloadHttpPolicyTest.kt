package com.reiflix.reiflix_local.downloads

import org.junit.Assert.*
import org.junit.Test
import java.net.URL

/** Pure protocol contract tests; no emulator or new test infrastructure. */
class DownloadHttpPolicyTest {
    @Test fun validatesExactResumeRange() {
        assertEquals(DownloadHttp.Range(1024,2047,4096),DownloadHttp.range("bytes 1024-2047/4096"))
        assertNull(DownloadHttp.range("bytes 2048-1024/4096"))
        assertNull(DownloadHttp.range("bytes 1024-4096/4096"))
        assertNull(DownloadHttp.range("bytes 0-9/*"))
        assertNull(DownloadHttp.range("bytes 0-9223372036854775808/99"))
    }
    @Test fun originIncludesSchemeHostAndEffectivePort() {
        assertTrue(DownloadHttp.sameOrigin(URL("https://example.com/a"),URL("https://example.com:443/b")))
        assertFalse(DownloadHttp.sameOrigin(URL("https://example.com/a"),URL("http://example.com/a")))
        assertFalse(DownloadHttp.sameOrigin(URL("https://example.com/a"),URL("https://cdn.example.com/a")))
        assertFalse(DownloadHttp.sameOrigin(URL("https://example.com/a"),URL("https://example.com:8443/a")))
    }
    @Test fun rejectsNonHttpCredentialsAndHeaderInjectionInUrl() {
        listOf("file:///private/video.mp4","javascript:alert(1)","https://user:password@example.com/a","https://example.com/a\r\nX-Test: x").forEach {
            assertTrue(runCatching { DownloadHttp.validateUrl(it) }.isFailure)
        }
        assertEquals("example.com",DownloadHttp.validateUrl("https://example.com/file.mp4?token=example").host)
    }
}
