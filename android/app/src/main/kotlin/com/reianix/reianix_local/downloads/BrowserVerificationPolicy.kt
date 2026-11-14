package com.reianix.reianix_local.downloads

import java.net.URI

/**
 * Trusted human-verification endpoints must load in both filtering modes.
 * Exact verified hosts/paths only: strings containing "captcha" or "cloudflare"
 * do not qualify as exceptions.
 */
internal object BrowserVerificationPolicy {
    private fun uri(raw:String):URI? = runCatching {
        URI(raw).takeIf {it.scheme?.lowercase()=="https" &&
            !it.host.isNullOrBlank() && it.rawUserInfo==null}
    }.getOrNull()

    private fun hostname(value:URI)=value.host.lowercase().trimEnd('.')

    fun isVerificationResource(url:String,page:String):Boolean {
        val resource=uri(url) ?: return false
        val resourceHost=hostname(resource)
        val path=resource.rawPath.orEmpty().lowercase()
        if(resourceHost=="challenges.cloudflare.com")return true
        if(resourceHost in setOf("www.google.com","www.recaptcha.net","recaptcha.net") &&
            path.startsWith("/recaptcha/"))return true
        if(resourceHost=="www.gstatic.com" && path.startsWith("/recaptcha/"))return true
        if(resourceHost=="hcaptcha.com" || resourceHost.endsWith(".hcaptcha.com"))return true
        val pageUrl=uri(page) ?: return false
        if(resourceHost!=hostname(pageUrl))return false
        return path.startsWith("/cdn-cgi/challenge-platform/") ||
            path.startsWith("/cdn-cgi/turnstile/")
    }

    /** Balanced mode favors functional first-party scripts, players and styles. */
    fun isEssentialInBalanced(url:String,page:String,kind:String):Boolean {
        if(kind=="document" || kind=="media")return true
        val resource=uri(url) ?: return false
        val pageUrl=uri(page) ?: return false
        val a=hostname(resource);val b=hostname(pageUrl)
        if(a!=b && !a.endsWith("."+b) && !b.endsWith("."+a))return false
        return kind in setOf("script","stylesheet","font","xmlhttprequest","subdocument","other")
    }
}
