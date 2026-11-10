package com.reiflix.reiflix_local.data.anilist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AniListOAuthRequestTest {
    private val state = "849ce146-90fe-432f-96ce-039e3e035b68"

    @Test
    fun authorizeUsesAniListImplicitGrantWithRegisteredRedirect() {
        val url = AniListOAuthRequest.authorizationUrl("56542", state)
        assertEquals(
            "https://anilist.co/api/v2/oauth/authorize?client_id=56542&response_type=token&state=$state",
            url,
        )
        assertFalse(url.contains("redirect_uri"))
        assertFalse(url.contains("grant_type"))
        assertFalse(url.contains("client_secret"))
        assertFalse(url.contains("access_token"))
    }

    @Test
    fun rejectsInvalidPublicClientIdAndState() {
        assertThrows(IllegalArgumentException::class.java) {
            AniListOAuthRequest.authorizationUrl("", state)
        }
        assertThrows(IllegalArgumentException::class.java) {
            AniListOAuthRequest.authorizationUrl("bad-id", state)
        }
        assertThrows(IllegalArgumentException::class.java) {
            AniListOAuthRequest.authorizationUrl("56542", "short")
        }
        assertThrows(IllegalArgumentException::class.java) {
            AniListOAuthRequest.authorizationUrl("56542", state + "&redirect_uri=https://attacker.invalid")
        }
        assertTrue(AniListOAuthRequest.authorizationUrl("56542", state).contains("response_type=token"))
    }
}
