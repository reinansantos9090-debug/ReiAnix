package com.reianix.reianix_local.downloads

import org.junit.Assert.*
import org.junit.Test

class DownloadSourceSelectionTest {
    @Test fun directSharedLinkWinsAgainstGatewayEvenIfGatewayComesFirst() {
        val text="Player: https://frost.example/watch/ep01\n"+
            "Arquivo: https://cdn.example/episode-01.mp4?token=abc123"
        val results=DownloadSourceSelection.choose(listOf(text))
        assertEquals(2,results.size)
        assertEquals("https://cdn.example/episode-01.mp4?token=abc123",results.first().url)
        assertEquals(DownloadSourceSelection.Kind.DIRECT_FILE,results.first().kind)
        assertEquals(DownloadSourceSelection.Kind.PAGE_OR_GATEWAY,results.last().kind)
    }

    @Test fun extractedDirectMediaFromExplicitShareFieldsWinsOverPage() {
        val results=DownloadSourceSelection.choose(
            texts=listOf("https://player.example/episode/1"),
            explicitUrls=listOf("https://media.example/series/ep1.mkv?signature=abc")
        )
        assertEquals("https://media.example/series/ep1.mkv?signature=abc",results.first().url)
    }

    @Test fun nestedUserProvidedMediaUrlIsOfferedUnchanged() {
        val wrapper="https://player.example/download?url=https%3A%2F%2Fcdn.example%2Fep.mp4%3Fsign%3Da%252Bb"
        val found=DownloadSourceSelection.choose(listOf(wrapper))
        assertEquals("https://cdn.example/ep.mp4?sign=a%2Bb",found.first().url)
        assertEquals(2,found.size)
    }

    @Test fun signedOpaqueCDNIsPossibleMediaNotAutomaticallyPage() {
        assertEquals(DownloadSourceSelection.Kind.POSSIBLE_MEDIA,
            DownloadSourceSelection.kind("https://cdn.example/resources/ab12cd?sign=abc"))
        assertFalse(DownloadSourceSelection.clearlyPage(
            "https://cdn.example/resources/ab12cd"))
    }

    @Test fun knownPlayersAndHtmlRemainPages() {
        assertTrue(DownloadSourceSelection.clearlyPage(
            "https://frost.example/watch/episode-1"))
        assertTrue(DownloadSourceSelection.clearlyPage(
            "https://frost.example/index.html"))
        assertFalse(DownloadSourceSelection.clearlyPage(
            "https://cdn.example/resources/ep.mp4"))
    }

    @Test fun rejectsNonHttpAndCredentialUrlsAndPreservesSignedQuery() {
        val results=DownloadSourceSelection.choose(listOf(
            "javascript:alert(1) https://user:secret@cdn.example/ep.mp4 "+
            "https://cdn.example/ep.mp4?X-Amz-Signature=a%2Bb%3D"
        ))
        assertEquals(1,results.size)
        assertEquals("https://cdn.example/ep.mp4?X-Amz-Signature=a%2Bb%3D",
            results.first().url)
    }

    @Test fun neverInventsDirectLinkWhenOnlyIntermediateExists() {
        val only=DownloadSourceSelection.choose(listOf(
            "Veja o player: https://frost.example/player/episode-1"))
        assertEquals(1,only.size)
        assertEquals(DownloadSourceSelection.Kind.PAGE_OR_GATEWAY,only.first().kind)
    }
}
