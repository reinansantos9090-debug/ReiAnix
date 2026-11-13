package com.reianix.reianix_local.downloads

import android.content.Context
import android.webkit.WebView
import android.webkit.WebResourceRequest
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import io.github.edsuns.adblockclient.ResourceType
import kotlinx.coroutines.*
import java.net.URI
import org.json.JSONObject

/** Subscription-backed native filtering, separate from the media downloader. */
internal object BrowserAdBlock {
    @Volatile var mode="balanced"
    @Volatile private var ready=false
    suspend fun prepare(context:Context):String = try {
        val result=BrowserFilterLists.prepare(context)
        ready=true
        result
    } catch(t:Throwable) {
        ready=false
        throw t
    }
    suspend fun refresh(context:Context)=BrowserFilterLists.refresh(context)
    val status:String get()=BrowserFilterLists.status
    fun host(url:String)=runCatching {URI(url).host.orEmpty().lowercase().trimEnd('.')}.getOrDefault("")
    fun related(a:String,b:String):Boolean {
        val x=host(a);val y=host(b)
        return x.isNotBlank() && y.isNotBlank() && (x==y || x.endsWith(".$y") || y.endsWith(".$x"))
    }
    fun blocked(url:String,selected:String=mode,page:String=url,type:ResourceType=ResourceType.UNKNOWN):Boolean {
        if(selected=="off" || !ready || !url.startsWith("http"))return false
        return BrowserFilterLists.blocked(url,page,type,selected=="advanced")
    }
    fun blocked(request:WebResourceRequest,page:String):Boolean {
        val headers=request.requestHeaders
        val dest=headers.entries.firstOrNull {it.key.equals("Sec-Fetch-Dest",true)}?.value
        val type=when {
            request.isForMainFrame -> ResourceType.DOCUMENT
            dest=="iframe" || dest=="frame" -> ResourceType.SUBDOCUMENT
            dest=="script" -> ResourceType.SCRIPT
            dest=="style" -> ResourceType.CSS
            dest=="image" -> ResourceType.IMAGE
            dest=="font" -> ResourceType.FONT
            dest=="video" || dest=="audio" -> ResourceType.MEDIA
            else -> ResourceType.from(request)
        }
        val frame=headers.entries.firstOrNull {it.key.equals("Referer",true)}?.value ?: page
        return blocked(request.url.toString(),page=frame,type=type)
    }
    fun install(context:Context,view:WebView,scope:CoroutineScope) {
        if(!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER) ||
            !WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT))return
        // Same bridge discipline as media capture: bounded input, origin checked,
        // no enqueue/file/network operations exposed to page JavaScript.
        WebViewCompat.addWebMessageListener(view,"ReiAnixFilters",setOf("*")) { _,msg,origin,_,reply ->
            if(msg.type!=androidx.webkit.WebMessageCompat.TYPE_STRING)return@addWebMessageListener
            val raw=msg.data ?: return@addWebMessageListener
            if(raw.length>16384 || origin.scheme !in setOf("http","https"))return@addWebMessageListener
            val url=runCatching {JSONObject(raw).getString("url")}.getOrNull() ?: return@addWebMessageListener
            if(!runCatching {DownloadHttp.sameOrigin(java.net.URL(url),java.net.URL(origin.toString()))}.getOrDefault(false))
                return@addWebMessageListener
            scope.launch {
                val data=withContext(Dispatchers.IO) {
                    if(mode=="off" || !ready)"{}" else BrowserFilterLists.cosmetics(url,mode=="advanced")
                }
                reply.postMessage(data)
            }
        }
        val libraries=context.assets.open("browser-filter/extended-css.min.js").bufferedReader().use {it.readText()}+
            "\n"+context.assets.open("browser-filter/scriptlets.min.js").bufferedReader().use {it.readText()}
        val script="""
            (()=>{
              if(!window.ReiAnixFilters)return;
              var scriptlets;
              $libraries
              let last='';
              window.ReiAnixFilters.onmessage=e=>{try{
                const d=JSON.parse(e.data);if(last===e.data)return;last=e.data;
                const apply=()=>{
                  let style=document.getElementById('__rei_filter_css');
                  if(!style){style=document.createElement('style');style.id='__rei_filter_css';(document.head||document.documentElement).appendChild(style);}
                  style.textContent=d.css||'';
                  if(d.extended&&d.extended.length)try{new ExtendedCss({styleSheet:d.extended.join('\n')}).apply();}catch(e){}
                  (d.scripts||[]).forEach(args=>{try{
                    const code=scriptlets.invoke({name:args[0],args:args.slice(1)});
                    if(code)new Function(code)();
                  }catch(e){}});
                };
                if(document.documentElement)apply();else document.addEventListener('DOMContentLoaded',apply,{once:true});
              }catch(e){}};
              const request=()=>{try{window.ReiAnixFilters.postMessage(JSON.stringify({url:location.href}));}catch(e){}};
              request();document.addEventListener('DOMContentLoaded',request,{once:true});
            })();
        """.trimIndent()
        WebViewCompat.addDocumentStartJavaScript(view,script,setOf("*"))
    }
}
