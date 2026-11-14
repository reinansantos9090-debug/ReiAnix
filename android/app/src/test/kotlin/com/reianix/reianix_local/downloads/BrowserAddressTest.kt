package com.reianix.reianix_local.downloads

import org.junit.Assert.*
import org.junit.Test

class BrowserAddressTest {
    @Test fun searchesWordsAndPhrasesInsteadOfInventingDomains() {
        assertEquals("https://www.google.com/search?q=google",BrowserAddress.resolve("google"))
        assertEquals("https://www.google.com/search?q=animefire",BrowserAddress.resolve("animefire/"))
        assertEquals("https://www.google.com/search?q=One+Piece",BrowserAddress.resolve("One Piece"))
        assertEquals("https://www.google.com/search?q=google",BrowserAddress.resolve("https://google/"))
    }

    @Test fun navigatesToActualWebHosts() {
        assertEquals("https://animesonline.io/",BrowserAddress.resolve("https://animesonline.io/"))
        assertEquals("https://animefire.cv/",BrowserAddress.resolve("animefire.cv/"))
        assertEquals("https://www.google.com",BrowserAddress.resolve("www.google.com"))
        assertEquals("http://localhost:8080/",BrowserAddress.resolve("http://localhost:8080/"))
    }

    @Test fun rejectsUnsafeOrEmptySchemes() {
        assertNull(BrowserAddress.resolve("javascript:alert(1)"))
        assertNull(BrowserAddress.resolve("file:///sdcard/video.mp4"))
        assertNull(BrowserAddress.resolve("ftp://example.com"))
        assertNull(BrowserAddress.resolve(""))
    }
}
