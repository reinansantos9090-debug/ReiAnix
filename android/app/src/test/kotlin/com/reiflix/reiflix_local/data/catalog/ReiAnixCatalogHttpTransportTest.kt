package com.reiflix.reiflix_local.data.catalog

import java.io.IOException
import java.net.URI
import org.junit.Assert.assertEquals
import org.junit.Test

class ReiAnixCatalogHttpTransportTest {
    @Test
    fun relativeAndAbsoluteHttpsRedirectTargetsAreResolved() {
        val current = URI("https://api.example.test/v1/anime")

        assertEquals(
            URI("https://api.example.test/v1/anime/42"),
            resolveSafeCatalogRedirect(current, "anime/42"),
        )
        assertEquals(
            URI("https://cdn.example.test/poster/42.webp?size=large"),
            resolveSafeCatalogRedirect(current, "https://cdn.example.test/poster/42.webp?size=large"),
        )
    }

    @Test(expected = IOException::class)
    fun httpsToHttpRedirectIsRejected() {
        resolveSafeCatalogRedirect(
            URI("https://api.example.test/v1/anime"),
            "http://api.example.test/v1/anime/42",
        )
    }

    @Test(expected = IOException::class)
    fun redirectWithUserInfoIsRejected() {
        resolveSafeCatalogRedirect(
            URI("https://api.example.test/v1/anime"),
            "https://user:password@cdn.example.test/poster.jpg",
        )
    }

    @Test(expected = IOException::class)
    fun redirectWithoutHostIsRejected() {
        resolveSafeCatalogRedirect(
            URI("https://api.example.test/v1/anime"),
            "https:///missing-host",
        )
    }
}
