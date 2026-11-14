package com.reiflix.reiflix_local.data.anilist

/**
 * AniList's documented implicit-grant URL uses client_id and response_type=token.
 * Use the redirect URI already registered with the application at AniList, rather
 * than supplying a second, redundant redirect_uri parameter in the browser.
 *
 * The OAuth2 state nonce is retained for CSRF protection and checked on callback.
 * No client secret, access token or user credential belongs in this URL.
 */
internal object AniListOAuthRequest {
    fun authorizationUrl(clientId: String, state: String): String {
        require(clientId.isNotBlank() && clientId.all(Char::isDigit)) {
            "AniList Client ID must be numeric."
        }
        require(state.length in 20..128 && state.all { it.isLetterOrDigit() || it == '-' }) {
            "Invalid OAuth state."
        }
        return "https://anilist.co/api/v2/oauth/authorize" +
            "?client_id=$clientId&response_type=token&state=$state"
    }
}
