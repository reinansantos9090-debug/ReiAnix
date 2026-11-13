package com.reianix.reianix_local.downloads

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

/** HTTP policy separated from Android so range/redirect behavior is independently reviewable. */
object DownloadHttp {
    fun validateUrl(value: String): URI {
        val uri=URI(value.trim())
        require(uri.scheme?.lowercase() in setOf("http","https") && !uri.host.isNullOrBlank() && uri.userInfo==null) { "Use um link HTTP/HTTPS válido; informe credenciais nos campos próprios" }
        require(value.none { it=='\r' || it=='\n' })
        return uri
    }
    fun sameOrigin(a: URL,b: URL): Boolean = a.protocol.equals(b.protocol,true) && a.host.equals(b.host,true) &&
        (if(a.port<0) a.defaultPort else a.port)==(if(b.port<0)b.defaultPort else b.port)
    fun connect(url: String, headers: Map<String,String>, offset: Long, validator: String?, end: Long? = null, credentialOrigin: String = url,
        method: String = "GET", connectTimeoutMs: Int = 20_000, readTimeoutMs: Int = 25_000,
        probeRange: String? = null, conditionalEtag: String? = null,
        preferInitialRange: Boolean = false, closeAfterResponse: Boolean = false,
        retryHtmlWithSelfReferer: Boolean = false,
        allowMediaHttpDowngrade: Boolean = false): HttpURLConnection {
        require(method in setOf("GET","HEAD"))
        val origin=URL(validateUrl(credentialOrigin).toASCIIString())
        var target=URL(validateUrl(url).toASCIIString())
        // A full-file initial range is common in download managers, but some
        // servers reject it. On a 400/405/416/501, unusable 206 or socket
        // failure, retry once with plain GET. Never use this fallback for 401/403.
        var initialRange=preferInitialRange && method=="GET" && offset==0L &&
            end==null && probeRange==null
        var redirects=0
        var selfReferer=false
        val alreadyHasReferer=headers.keys.any {it.equals("Referer",true)}
        repeat(12) {
            val connection=target.openConnection() as HttpURLConnection
            connection.instanceFollowRedirects=false
            connection.connectTimeout=connectTimeoutMs.coerceIn(1000,30_000)
            connection.readTimeout=readTimeoutMs.coerceIn(1000,30_000)
            connection.requestMethod=method
            connection.setRequestProperty("Accept-Encoding","identity")
            connection.setRequestProperty("User-Agent","ReiAnix/Downloads")
            if(closeAfterResponse)connection.setRequestProperty("Connection","close")
            // Preserve legitimate source-session request headers on the exact
            // origin only. Never override transport-managed HTTP fields or
            // forward authorization/cookies/custom headers after a redirect.
            if(sameOrigin(origin,target))headers.forEach { (key,value) ->
                val k=key.lowercase(java.util.Locale.ROOT)
                val safeName=key.length in 1..64 && key.all {
                    it.isLetterOrDigit() || it=='-'
                }
                if(safeName && value.length<=16384 && value.none {
                    it=='\r'||it=='\n'
                } && k !in setOf("host","content-length","transfer-encoding",
                    "accept-encoding","range","if-range","connection","upgrade",
                    "proxy-authorization","proxy-connection","te","trailer"))
                    connection.setRequestProperty(key,value)
            }
            if(!sameOrigin(origin,target)) {
                headers.entries.firstOrNull {it.key.equals("user-agent",true)}
                    ?.value?.takeIf {it.length<=1024 && it.none {c->c=='\r'||c=='\n'}}
                    ?.let {connection.setRequestProperty("User-Agent",it)}
                headers.entries.firstOrNull {it.key.equals("referer",true)}
                    ?.value?.let { value ->
                        runCatching {
                            val page=URL(value)
                            if(page.protocol in setOf("http","https") &&
                                !(page.protocol=="https" && target.protocol!="https"))
                                connection.setRequestProperty("Referer",
                                    URL(page.protocol,page.host,page.port,"/").toString())
                        }
                    }
            }
            // Navi's metadata discovery retries some HTML landing responses
            // with the original source as Referer. Only the original same
            // origin receives the full referer; never resend a signed query to
            // a cross-origin redirect.
            if(selfReferer && sameOrigin(origin,target))
                connection.setRequestProperty("Referer",origin.toString())
            if(initialRange) {
                connection.setRequestProperty("Range","bytes=0-")
            } else if(method=="GET" && !probeRange.isNullOrBlank()) {
                require(probeRange.length<=64 && probeRange.startsWith("bytes=") &&
                    probeRange.drop(6).all { it.isDigit() || it==',' || it=='-' })
                connection.setRequestProperty("Range",probeRange)
            } else if(end!=null || (offset>0 && !validator.isNullOrBlank())) {
                connection.setRequestProperty("Range","bytes="+offset+"-"+(end?.toString() ?: ""))
                if(!validator.isNullOrBlank()) connection.setRequestProperty("If-Range",validator)
            }
            // Validators are used only for metadata checks, not for cross-origin
            // authentication or to change a download's byte representation.
            if(!conditionalEtag.isNullOrBlank() && conditionalEtag.length<=256 &&
                conditionalEtag.none { it=='\r' || it=='\n' } && sameOrigin(origin,target))
                connection.setRequestProperty("If-None-Match",conditionalEtag)
            val code=try {connection.responseCode} catch(error: IOException) {
                connection.disconnect()
                if(initialRange) {
                    initialRange=false
                    return@repeat
                }
                throw error
            } catch(error:Exception) {
                connection.disconnect();throw error
            }
            if(code in setOf(301,302,303,307,308)) {
                val location=connection.getHeaderField("Location");connection.disconnect()
                if(location.isNullOrBlank())throw IOException("Redirecionamento sem destino")
                if(++redirects>8)throw IOException("Redirecionamentos demais")
                val next=URL(target,location);validateUrl(next.toString())
                if(!canFollowRedirect(target,next,allowMediaHttpDowngrade))
                    throw IOException("Redirecionamento HTTPS para HTTP não permitido")
                // All first-party cookies, Authorization and nonstandard
                // headers are automatically withheld when scheme/host changes.
                // Do not send the original HTTPS Referer to an HTTP target.
                target=next
                return@repeat
            }
            if(retryHtmlWithSelfReferer && !selfReferer && !alreadyHasReferer &&
                code==200 && method=="GET" && sameOrigin(origin,target) &&
                connection.contentType.orEmpty().substringBefore(';')
                    .trim().equals("text/html",true)) {
                connection.disconnect()
                selfReferer=true
                initialRange=false
                return@repeat
            }
            if(initialRange && shouldRetryWithoutInitialRange(code,
                connection.getHeaderField("Content-Range"))) {
                connection.disconnect()
                initialRange=false
                return@repeat
            }
            return connection
        }
        throw IOException("Limite de tentativas HTTP excedido")
    }
    /**
     * Some public media CDNs redirect an HTTPS gateway to an HTTP file.
     * Mirror download managers only for explicitly requested media; never
     * downgrade by default for arbitrary API requests. Credential forwarding
     * remains origin scoped, independent of this opt-in.
     */
    fun canFollowRedirect(from:URL,to:URL,allowMediaHttpDowngrade:Boolean):Boolean =
        to.protocol in setOf("http","https") &&
            (from.protocol!="https" || to.protocol=="https" ||
                allowMediaHttpDowngrade)

    /** Never retry authentication denials with a different request shape. */
    fun shouldRetryWithoutInitialRange(code:Int,contentRange:String?):Boolean =
        code in setOf(400,405,416,501) ||
            (code==206 && range(contentRange)?.start!=0L)

    data class Range(val start: Long,val end: Long,val total: Long)
    fun range(header: String?): Range? {
        val m=Regex("bytes (\\d+)-(\\d+)/(\\d+)",RegexOption.IGNORE_CASE).matchEntire(header?.trim().orEmpty()) ?: return null
        val start=m.groupValues[1].toLongOrNull() ?: return null
        val end=m.groupValues[2].toLongOrNull() ?: return null
        val total=m.groupValues[3].toLongOrNull() ?: return null
        return if(start>=0 && end>=start && total>end) Range(start,end,total) else null
    }
}
