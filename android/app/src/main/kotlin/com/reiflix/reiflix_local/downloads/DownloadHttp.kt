package com.reiflix.reiflix_local.downloads

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
    fun connect(url: String, headers: Map<String,String>, offset: Long, validator: String?, end: Long? = null, credentialOrigin: String = url): HttpURLConnection {
        val origin=URL(validateUrl(credentialOrigin).toASCIIString()); var target=URL(validateUrl(url).toASCIIString())
        repeat(8) {
            val connection=target.openConnection() as HttpURLConnection
            connection.instanceFollowRedirects=false; connection.connectTimeout=20_000; connection.readTimeout=25_000
            connection.setRequestProperty("Accept-Encoding","identity")
            connection.setRequestProperty("User-Agent","ReiAnix/Downloads")
            // Never forward cookies, referer tokens or credentials to a redirect's other origin.
            if(sameOrigin(origin,target)) headers.forEach { (key,value) ->
                if(key.lowercase() in setOf("authorization","cookie","referer","user-agent") && value.none { c -> c=='\r'||c=='\n' }) connection.setRequestProperty(key,value)
            }
            if(end!=null || (offset>0 && !validator.isNullOrBlank())) {
                connection.setRequestProperty("Range","bytes=$offset-${end ?: ""}"); if(!validator.isNullOrBlank()) connection.setRequestProperty("If-Range",validator)
            }
            val code=try { connection.responseCode } catch(error: Exception) { connection.disconnect(); throw error }
            if(code in setOf(301,302,303,307,308)) {
                val location=connection.getHeaderField("Location"); connection.disconnect()
                if(location.isNullOrBlank()) throw IOException("Redirecionamento sem destino")
                val next=URL(target,location); validateUrl(next.toString())
                if(target.protocol=="https" && next.protocol!="https") throw IOException("Redirecionamento HTTPS para HTTP recusado")
                target=next
            } else return connection
        }
        throw IOException("Redirecionamentos demais")
    }
    data class Range(val start: Long,val end: Long,val total: Long)
    fun range(header: String?): Range? {
        val m=Regex("bytes (\\d+)-(\\d+)/(\\d+)",RegexOption.IGNORE_CASE).matchEntire(header?.trim().orEmpty()) ?: return null
        val start=m.groupValues[1].toLongOrNull() ?: return null
        val end=m.groupValues[2].toLongOrNull() ?: return null
        val total=m.groupValues[3].toLongOrNull() ?: return null
        return if(start>=0 && end>=start && total>end) Range(start,end,total) else null
    }
}
