package com.reiflix.reiflix_local.downloads

import android.net.Uri
import android.webkit.WebView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONObject

/** A bounded URL-only message channel. Pages cannot enqueue, read files or obtain app secrets. */
internal object BrowserMediaCapture {
    fun install(web: WebView,onMedia: (String,String)->Unit) {
        if(!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER) ||
            !WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) return
        WebViewCompat.addWebMessageListener(web,"ReiAnixMedia",setOf("*")) { _,message,sourceOrigin,_,_ ->
            if(sourceOrigin.scheme !in setOf("http","https"))return@addWebMessageListener
            val raw=message.data ?: return@addWebMessageListener
            if(raw.length>32768)return@addWebMessageListener
            runCatching {
                val obj=JSONObject(raw);val url=obj.optString("url");val page=obj.optString("page")
                if(url.length<=16384 && Uri.parse(url).scheme in setOf("http","https")) {
                    DownloadHttp.validateUrl(url)
                    val ref=runCatching {DownloadHttp.validateUrl(page);page}.getOrDefault(sourceOrigin.toString())
                    onMedia(url,ref)
                }
            }
        }
        WebViewCompat.addDocumentStartJavaScript(web,SCRIPT,setOf("*"))
    }
    val SCRIPT="""
        (() => {
          if(window.__reiMediaInstalled)return; window.__reiMediaInstalled=true;
          const sent=new Set();
          const video=(url,type='') => /^(video\/|application\/(?:vnd\.apple\.mpegurl|x-mpegurl|dash\+xml))/i.test(type)||/\.(?:mp4|mkv|webm|m4v|mov|avi|m3u8|mpd)(?:[?#]|${'$'})/i.test(url);
          const report=(raw,type='',explicit=false) => {try {
            const url=new URL(raw,location.href).href;
            if(!/^https?:/i.test(url)||(!explicit&&!video(url,type))||sent.has(url)||sent.size>=120)return;
            sent.add(url); if(window.ReiAnixMedia)window.ReiAnixMedia.postMessage(JSON.stringify({url,page:location.href}));
          }catch(e){}};
          let scheduled=false; const schedule=()=>{if(!scheduled){scheduled=true;setTimeout(()=>{scheduled=false;scan();},200);}};
          const scan=()=>{document.querySelectorAll('video,video source,a[download]').forEach(e=>{
            const raw=e.currentSrc||e.src||e.href;if(raw)report(raw,e.type||'',true);
          });};
          const fetchOriginal=window.fetch;
          if(fetchOriginal)window.fetch=function(...args){return fetchOriginal.apply(this,args).then(response=>{
            try{report(response.url,response.headers.get('content-type')||'');}catch(e){}return response;
          });};
          const openOriginal=XMLHttpRequest.prototype.open;
          XMLHttpRequest.prototype.open=function(...args){this.addEventListener('load',()=>{try{report(this.responseURL,this.getResponseHeader('content-type')||'');}catch(e){}},{once:true});return openOriginal.apply(this,args);};
          try{new PerformanceObserver(list=>list.getEntries().forEach(e=>report(e.name))).observe({type:'resource',buffered:true});}catch(e){}
          document.addEventListener('loadedmetadata',scan,true);document.addEventListener('play',scan,true);
          document.addEventListener('DOMContentLoaded',()=>{scan();new MutationObserver(schedule).observe(document.documentElement,{childList:true,subtree:true,attributes:true,attributeFilter:['src','href']});},{once:true});
        })();
    """.trimIndent()
}
