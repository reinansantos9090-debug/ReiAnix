package com.reianix.reianix_local.downloads

import android.content.Intent
import android.os.Bundle

/**
 * Android external media-launchers do not agree on the "headers" extra type.
 *
 * Nuvio Mobile ExternalPlayerPlatform.android sends a String[] containing
 * "Header-Name: value" (including Referer/User-Agent/Authorization). Browsers
 * often send a Bundle under "com.android.browser.headers". Treating the former
 * as a Bundle silently drops the source session, so CDNs can reject the link.
 *
 * This class only reads intent-supplied metadata. It does not obtain other
 * apps' cookies or log sensitive request values. DownloadHttp separately
 * enforces origin-scoped credentials across redirects.
 */
internal object DownloadIncomingHeaders {
    private val safeName=Regex("""^[A-Za-z0-9-]{1,64}$""")
    private val forbidden=setOf(
        "host","range","if-range","connection","content-length",
        "transfer-encoding","accept-encoding","proxy-authorization",
        "proxy-connection","te","trailer","upgrade"
    )
    fun parse(intent:Intent):Map<String,String> {
        val result=linkedMapOf<String,String>()
        fun put(name:String,value:String) {
            val key=name.trim()
            val cleaned=value.trim()
            if(!safeName.matches(key) || key.lowercase() in forbidden ||
                cleaned.isBlank() || cleaned.length>16384 ||
                cleaned.any{it=='\r'||it=='\n'})return
            val previous=result.keys.firstOrNull{it.equals(key,true)}
            if(previous!=null)result.remove(previous)
            if(result.size<64)result[key]=cleaned
        }
        fun parseStrings(lines:Array<out String>?) {
            lines?.take(64)?.forEach{line->
                val separator=line.indexOf(':')
                if(separator in 1..64)put(line.substring(0,separator),
                    line.substring(separator+1))
            }
        }
        fun parseBundle(bundle:Bundle?) {
            bundle?.keySet()?.take(64)?.forEach{key->
                val value=runCatching{bundle.get(key)}.getOrNull()
                if(value is String)put(key,value)
            }
        }

        // Nuvio Android specifically uses putExtra("headers", Array<String>).
        // Other players use a serialized ArrayList or a Bundle.
        val extras=intent.extras
        for(key in listOf("com.android.browser.headers","headers")) {
            when(val data=runCatching{extras?.get(key)}.getOrNull()) {
                is Bundle -> parseBundle(data)
                is Array<*> -> parseStrings(data.filterIsInstance<String>().toTypedArray())
                is ArrayList<*> -> parseStrings(data.filterIsInstance<String>().toTypedArray())
                is String -> parseStrings(data.lineSequence().take(64).toList().toTypedArray())
            }
        }

        // More external-player integrations pass optional header values here.
        for((extra,header) in listOf(
            "userAgent" to "User-Agent",
            "httpUserAgent" to "User-Agent",
            "referer" to "Referer",
            "httpReferer" to "Referer",
            "cookie" to "Cookie"
        )) {
            val value=runCatching{intent.getStringExtra(extra)}.getOrNull()
            if(!value.isNullOrBlank() && result.keys.none{it.equals(header,true)})
                put(header,value)
        }
        return result
    }
}
