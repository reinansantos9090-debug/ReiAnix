package com.reianix.reianix_local.downloads

import org.junit.Assert.*
import org.junit.Test

class BrowserVerificationPolicyTest {
    @Test fun cloudflareIsAllowedWithoutAllowingSpoofedDomains() {
        val site="https://js-yt.github.io/link/anime/5JTIU.html"
        assertTrue(BrowserVerificationPolicy.isVerificationResource(
            "https://challenges.cloudflare.com/turnstile/v0/api.js",site))
        assertTrue(BrowserVerificationPolicy.isVerificationResource(
            "https://challenges.cloudflare.com/cdn-cgi/challenge-platform/h/g/",site))
        assertTrue(BrowserVerificationPolicy.isVerificationResource(
            "https://js-yt.github.io/cdn-cgi/challenge-platform/h/g/flow",site))
        assertFalse(BrowserVerificationPolicy.isVerificationResource(
            "https://js-yt.github.io/cdn-cgi/challenge-platform/h/g/flow","https://evil.test/"))
        assertFalse(BrowserVerificationPolicy.isVerificationResource(
            "https://challenges.cloudflare.com.evil.test/turnstile/v0/api.js",site))
        assertFalse(BrowserVerificationPolicy.isVerificationResource(
            "https://challenges.cloudflare.com@evil.test/turnstile/v0/api.js",site))
        assertFalse(BrowserVerificationPolicy.isVerificationResource(
            "https://ads.cloudflare.com/banner.js",site))
        assertFalse(BrowserVerificationPolicy.isVerificationResource(
            "https://cdn.example.test/cloudflare-captcha-ads.js",site))
    }

    @Test fun recaptchaAndHcaptchaAreAllowedWithoutWhitelistingAds() {
        val site="https://anime.example/watch"
        assertTrue(BrowserVerificationPolicy.isVerificationResource(
            "https://www.google.com/recaptcha/api2/anchor",site))
        assertTrue(BrowserVerificationPolicy.isVerificationResource(
            "https://www.gstatic.com/recaptcha/releases/v2/widget.js",site))
        assertTrue(BrowserVerificationPolicy.isVerificationResource(
            "https://js.hcaptcha.com/1/api.js",site))
        assertFalse(BrowserVerificationPolicy.isVerificationResource(
            "https://www.google.com/ads/track",site))
        assertFalse(BrowserVerificationPolicy.isVerificationResource(
            "https://hcaptcha.com.evil.example/pixel",site))
    }

    @Test fun balancedPreservesFirstPartyPlayerAndScriptsWithoutAllowingAdHosts() {
        val page="https://animesonline.io/watch/episode-1"
        assertTrue(BrowserVerificationPolicy.isEssentialInBalanced(
            "https://animesonline.io/static/player.js",page,"script"))
        assertTrue(BrowserVerificationPolicy.isEssentialInBalanced(
            "https://animesonline.io/api/episode",page,"xmlhttprequest"))
        assertTrue(BrowserVerificationPolicy.isEssentialInBalanced(
            "https://cdn.animesonline.io/style.css",page,"stylesheet"))
        assertTrue(BrowserVerificationPolicy.isEssentialInBalanced(
            "https://video-cdn.example/movie.m3u8",page,"media"))
        assertFalse(BrowserVerificationPolicy.isEssentialInBalanced(
            "https://ads.bad-domain.example/banner.js",page,"script"))
        assertFalse(BrowserVerificationPolicy.isEssentialInBalanced(
            "https://animesonline.io.evil.test/track.js",page,"script"))
    }
}
