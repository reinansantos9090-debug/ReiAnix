package com.reianix.reianix_local.downloads

import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebSettings
import java.net.URL

/**
 * Resolve first-party session metadata for a direct HTTP resource.
 * Cookies are taken only from this application's own WebView store for
 * this exact URL. Other applications' private login sessions cannot be read.
 * DownloadHttp enforces origin separation during HTTP redirects.
 */
internal object DownloadSessionHeaders {
    /** CookieManager applies target host/path and Secure scoping itself. */
    fun cookieForTarget(url:String):String? {
        if(runCatching{DownloadHttp.validateUrl(url)}.isFailure)return null
        return runCatching {CookieManager.getInstance().getCookie(url)}.getOrNull()
            ?.takeIf{it.isNotBlank() && it.length<=16384 &&
                it.none{c->c=='\r'||c=='\n'}}
    }

    fun resolve(context:Context,url:String,provided:Map<String,String>):Map<String,String> {
        val result=LinkedHashMap<String,String>()
        for((name,value) in provided) {
            if(name.length in 1..64 && name.all {it.isLetterOrDigit() || it=='-'} &&
                value.length<=16384 && value.none {it=='\r'||it=='\n'}) {
                result[name]=value
            }
        }
        val valid=runCatching { URL(DownloadHttp.validateUrl(url).toASCIIString()) }.getOrNull()
            ?: return result
        fun exists(name:String)=result.keys.any {it.equals(name,true)}
        if(!exists("Cookie"))runCatching {
            CookieManager.getInstance().getCookie(valid.toString())
        }.getOrNull()?.takeIf {it.isNotBlank() && it.length<=16384 &&
            it.none {c->c=='\r'||c=='\n'}}?.let {result["Cookie"]=it}
        if(!exists("User-Agent")) {
            val agent=runCatching {WebSettings.getDefaultUserAgent(context)}.getOrNull()
                ?.takeIf {it.isNotBlank() && it.length<=1024 &&
                    it.none {c->c=='\r'||c=='\n'}}
            if(agent!=null)result["User-Agent"]=agent
        }
        return result
    }
}
