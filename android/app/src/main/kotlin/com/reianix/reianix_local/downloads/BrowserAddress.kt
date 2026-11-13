package com.reianix.reianix_local.downloads

import java.net.URI
import java.net.URLEncoder

/**
 * Address-bar input is not automatically a hostname. Bare words and phrases
 * are searches; only real web hosts are promoted to URLs.
 */
internal object BrowserAddress {
    private val domain=Regex("(?i)^(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)$")
    private val ipv4=Regex("^(?:[0-9]{1,3}\\.){3}[0-9]{1,3}$")
    private val forbidden=Regex("(?i)^(?:javascript|data|file|content|intent|about):")
    private fun isHost(host:String):Boolean =
        host.equals("localhost",true) || host.matches(domain) ||
            (host.matches(ipv4) && host.split('.').all {it.toInt() in 0..255}) ||
            host.contains(':')

    fun resolve(text:String):String? {
        val raw=text.trim()
        if(raw.isBlank() || raw.any {it.isISOControl()} || forbidden.containsMatchIn(raw))return null
        val explicit=raw.startsWith("https://",true) || raw.startsWith("http://",true)
        if(raw.contains("://") && !explicit)return null
        if(raw.any {it.isWhitespace()})return search(raw)

        val prefix=if(explicit)raw.substringAfter("://") else raw
        val authority=prefix.substringBefore('/').substringBefore('?').substringBefore('#')
        val host=authority.substringBeforeLast('@').let {if('@' in authority)"" else it}
            .let {if(it.startsWith("["))it.substringBefore(']')+"]" else it.substringBefore(':')}
        if(host.isEmpty())return null
        val hostname=host.removePrefix("[").removeSuffix("]")
        if(!isHost(hostname))return search(prefix.trimEnd('/'))

        val scheme=if(!explicit && (hostname.equals("localhost",true) || hostname=="127.0.0.1")) "http://"
            else "https://"
        val candidate=if(explicit)raw else scheme+raw
        val parsed=runCatching {URI(candidate)}.getOrNull() ?: return null
        if(parsed.scheme?.lowercase() !in setOf("http","https") ||
            parsed.host==null || parsed.rawUserInfo!=null || parsed.port>65535)return null
        return parsed.toASCIIString()
    }

    private fun search(query:String):String =
        "https://www.google.com/search?q="+URLEncoder.encode(query,"UTF-8")
}
