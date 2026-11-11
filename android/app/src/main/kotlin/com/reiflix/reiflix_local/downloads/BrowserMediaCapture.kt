package com.reiflix.reiflix_local.downloads

import android.net.Uri
import android.webkit.WebView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONObject

/** A bounded URL-only message channel. Pages cannot enqueue, read files or obtain app secrets. */
internal object BrowserMediaCapture {
    fun install(
        web: WebView,
        onMedia: (String,PlayerMediaCandidate?)->Unit,
        onOption: (BrowserMediaOption)->Unit,
    ) {
        if(!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER) ||
            !WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) return
        WebViewCompat.addWebMessageListener(web,"ReiAnixMedia",setOf("*")) { _,message,sourceOrigin,_,_ ->
            if(sourceOrigin.scheme !in setOf("http","https"))return@addWebMessageListener
            if(message.type!=androidx.webkit.WebMessageCompat.TYPE_STRING)return@addWebMessageListener
            val raw=message.data ?: return@addWebMessageListener
            if(raw.length>32768)return@addWebMessageListener
            runCatching {
                val obj=JSONObject(raw)
                val frame=obj.optString("frame")
                if(frame.length !in 1..100)return@runCatching
                if(obj.optString("kind")=="clear") {onMedia(frame,null);return@runCatching}
                if(obj.optString("kind")=="option") {
                    val option=BrowserMediaOption.parse(obj) ?: return@runCatching
                    if(!DownloadHttp.sameOrigin(java.net.URL(option.page),java.net.URL(sourceOrigin.toString())))return@runCatching
                    onOption(option)
                    return@runCatching
                }
                val item=PlayerMediaCandidate.parse(obj) ?: return@runCatching
                // Referrer must belong to the document that emitted the player evidence.
                if(!DownloadHttp.sameOrigin(java.net.URL(item.page),java.net.URL(sourceOrigin.toString())))return@runCatching
                onMedia(frame,item)
            }
        }
        WebViewCompat.addDocumentStartJavaScript(web,SCRIPT,setOf("*"))
    }
    val SCRIPT="""
(() => {
  if(window.__reiMainPlayer)return;window.__reiMainPlayer=true;
  const frameId=Math.random().toString(36).slice(2)+Date.now().toString(36);
  let frame={known:window===window.top,allowed:window===window.top,width:innerWidth,height:innerHeight};
  let lastKey='',lastSent=0,scheduled=false;
  const manifests=new Map(),masters=new Map(),emittedOptions=new Map();
  const badUrl=raw=>{try{const u=new URL(raw,location.href),d=/(?:^|[\/_.-])(\d{2,4})x(\d{2,4})(?=[\/_. -]|${'$'})/i.exec(u.pathname);
    return !/^https?:${'$'}/.test(u.protocol)||/(^|\.)(doubleclick\.net|googlesyndication\.com|adnxs\.com|adsterra\.com|popads\.net)${'$'}/.test(u.hostname)||/(?:^|\/)(ads?|advert(isement)?s?|banners?|preroll)(?:\/|[_.-]|${'$'})/i.test(u.pathname)||(d&&Number(d[1])/Number(d[2])>4);
  }catch(e){return true}};
  const adElement=e=>{try{for(let n=e;n&&n.nodeType===1;n=n.parentElement){if(/(?:^|[\s_-])(ad|ads|advert|advertisement|banner|preroll)(?:${'$'}|[\s_-])/i.test((n.id||'')+' '+(typeof n.className==='string'?n.className:'')))return true;}return false;}catch(e){return true}};
  const rect=e=>{try{
    for(let n=e;n&&n.nodeType===1;n=n.parentElement){const s=getComputedStyle(n);if(s.display==='none'||s.visibility==='hidden'||Number(s.opacity)===0)return null;}
    const r=e.getBoundingClientRect(),left=Math.max(0,r.left),right=Math.min(innerWidth,r.right),top=Math.max(0,r.top),bottom=Math.min(innerHeight,r.bottom);
    return right>left&&bottom>top?{left,right,top,bottom,width:right-left,height:bottom-top}:null;
  }catch(e){return null}};
  const message=obj=>{try{if(window.ReiAnixMedia)window.ReiAnixMedia.postMessage(JSON.stringify({...obj,frame:frameId,page:location.href}));}catch(e){}};
  const children=()=>Array.from(document.querySelectorAll('iframe,frame'));
  const sendFrame=e=>{try{const r=rect(e);e.contentWindow.postMessage({type:'REI_FRAME_INFO',known:frame.known,allowed:frame.allowed&&!!r&&r.width>=200&&r.height>=110&&!adElement(e),width:r?Math.min(r.width,frame.width):0,height:r?Math.min(r.height,frame.height):0},'*');}catch(e){}};
  const schedule=()=>{if(!scheduled){scheduled=true;setTimeout(()=>{scheduled=false;scan();scanOptions(false);children().forEach(sendFrame);},200);}};
  const clear=()=>{if(lastKey){message({kind:'clear'});lastKey='';}};

  // All candidates must come from a visible video, a visible quality control,
  // or an HLS master already exposed to the page. We do not inspect random
  // scripts, autoplay the episode, click ads, or bypass server access controls.
  const mediaFormat=raw=>{try{
    const path=new URL(raw,location.href).pathname.toLowerCase();
    if(path.endsWith('.m3u8'))return 'hls';
    if(path.endsWith('.mpd'))return 'dash';
    if(['.mp4','.mkv','.webm','.mov','.m4v','.ts','.m2ts'].some(x=>path.endsWith(x)))return 'video';
    return '';
  }catch(e){return ''}};
  const qualityFrom=(label,url)=>{const text=String(label||'')+' '+String(url||'');
    let m=/(?:^|[^0-9])(2160|1440|1080|720|480|360|240)\s*p(?:[^a-z]|$)/i.exec(text);
    if(m)return m[1]+'p';
    m=/(?:quality|res|height|q)[=:/_-](2160|1440|1080|720|480|360|240)(?:[^0-9]|$)/i.exec(text);
    return m?m[1]+'p':'Qualidade não informada';
  };
  const sendOption=(raw,label,origin,separateAudio=false)=>{try{
    if(!frame.known||!frame.allowed)return;
    const url=new URL(raw,location.href).href;
    const format=mediaFormat(url)||(origin==='visible-video'?'video':'');
    if(!format||badUrl(url)||url.length>16384)return;
    const quality=qualityFrom(label,url);
    const key=url+'|'+quality,now=Date.now();
    if(emittedOptions.get(key)&&now-emittedOptions.get(key)<4000)return;
    if(emittedOptions.size>=96)emittedOptions.clear();
    emittedOptions.set(key,now);
    message({kind:'option',url,quality,format,origin,separateAudio:!!separateAudio});
  }catch(e){}};
  const scanOptions=(prepareMasters=false)=>{try{
    if(!frame.known||!frame.allowed)return;
    const visible=Array.from(document.querySelectorAll('video')).filter(v=>{
      const r=rect(v);return r&&r.width>=200&&r.height>=110&&!adElement(v);
    }).slice(0,8);
    const sources=new Set(),pendingMasters=new Set();
    const offer=(raw,label,origin)=>{try{
      if(!raw)return;
      const url=new URL(raw,location.href).href;
      if(!mediaFormat(url)&&origin!=='visible-video')return;
      if(badUrl(url))return;
      sources.add(url);
      if(mediaFormat(url)==='hls')pendingMasters.add(url);
      sendOption(url,label,origin);
    }catch(e){}};
    visible.forEach(v=>{
      offer(v.currentSrc||v.src,v.getAttribute('data-quality')||v.getAttribute('data-res')||'','visible-video');
      v.querySelectorAll('source[src]').forEach(source=>{
        offer(source.src,source.getAttribute('label')||source.getAttribute('res')||
          source.getAttribute('data-quality')||source.getAttribute('size')||'','source');
      });
    });
    // Explicit quality controls can expose all renditions before Play. Accept
    // only media links that belong to visible controls, never arbitrary hrefs.
    if(visible.length>0) {
      Array.from(document.querySelectorAll('[data-quality][data-src],[data-res][data-url],a[download][href]'))
        .slice(0,80).forEach(control=>{
          const r=rect(control);
          if(!r||adElement(control))return;
          offer(control.getAttribute('data-src')||control.getAttribute('data-url')||
            control.getAttribute('href'),
            control.getAttribute('data-quality')||control.getAttribute('data-res')||
            control.textContent||'','quality-link');
        });
    }
    const recent=Array.from(masters.values()).filter(m=>Date.now()-m.time<60000);
    recent.forEach(master=>{
      const related=sources.has(master.url)||
        master.children.some(child=>sources.has(child.url))||
        (visible.length===1&&recent.length===1);
      if(!related)return;
      master.children.forEach(child=>sendOption(child.url,child.quality,'master',child.audio));
    });
    // Only on an explicit toolbar request: inspect a few *same-origin*
    // visible HLS playlists. Cross-origin/CORS/auth restrictions are respected.
    if(prepareMasters){
      Array.from(pendingMasters).filter(url=>{
        try{return new URL(url).origin===location.origin&&!masters.has(url);}catch(e){return false;}
      }).slice(0,4).forEach(async url=>{
        const ctl=new AbortController(),abort=setTimeout(()=>ctl.abort(),3000);
        try{
          const result=await fetch(url,{credentials:'same-origin',signal:ctl.signal});
          if(!result.ok)return;
          const size=Number(result.headers.get('content-length')||0);
          if(size>1048576)return;
          const text=await result.text();
          if(text.length<=1048576)inspect(url,text);
        }catch(e){}finally{clearTimeout(abort);}
      });
    }
  }catch(e){}};
  const scan=()=>{try{
    if(!frame.known||!frame.allowed){clear();return;}
    const players=Array.from(document.querySelectorAll('video')).map(v=>({v,r:rect(v)})).filter(({v,r})=>r&&r.width>=200&&r.height>=110&&!adElement(v)&&v.readyState>=1&&Number.isFinite(v.duration)&&v.duration>=120&&v.videoWidth>=160&&v.videoHeight>=90&&v.videoWidth/v.videoHeight>=0.45&&v.videoWidth/v.videoHeight<=3.2&&!(v.autoplay&&v.muted&&v.loop));
    players.sort((a,b)=>(b.r.width*b.r.height*(b.v.paused?1:1.5))-(a.r.width*a.r.height*(a.v.paused?1:1.5)));
    const p=players[0];if(!p){clear();return;}
    const v=p.v;let url=v.currentSrc||v.src,source='video';
    if(!/^https?:/i.test(url||'')){
      const now=Date.now(),matching=Array.from(manifests.values()).filter(m=>now-m.time<60000&&Math.abs(m.duration-v.duration)<=Math.max(10,v.duration*0.02));
      matching.sort((a,b)=>b.time-a.time);if(matching.length!==1){clear();return;}const media=matching[0];
      url=media.url;source='hls';
      // Keep the exact rendition the player loaded, including its signed query.
      // Switching back to a master may require a different session or choose another rendition.
    }
    if(badUrl(url)){clear();return;}
    const key=url+'|'+v.videoWidth+'|'+v.videoHeight+'|'+v.duration;
    if(key===lastKey&&Date.now()-lastSent<5000)return;
    lastKey=key;lastSent=Date.now();message({kind:'player',url,source,duration:v.duration,width:v.videoWidth,height:v.videoHeight,renderedWidth:Math.min(p.r.width,frame.width),renderedHeight:Math.min(p.r.height,frame.height)});
  }catch(e){clear();}};
  const inspect=(raw,text)=>{try{
    const url=new URL(raw,location.href).href;if(badUrl(url)||typeof text!=='string'||text.length>1048576||!text.trimStart().startsWith('#EXTM3U'))return;
    if(text.includes('#EXT-X-STREAM-INF:')){
      const lines=text.split(/\r?\n/),links=[];
      for(let i=0;i<lines.length;i++)if(lines[i].startsWith('#EXT-X-STREAM-INF:')){
        const info=lines[i],resolution=/RESOLUTION=(\d+)x(\d+)/i.exec(info);
        const audio=/(?:^|,)AUDIO=/i.test(info);
        for(let j=i+1;j<lines.length;j++)if(lines[j].trim()&&!lines[j].startsWith('#')){
          links.push({url:new URL(lines[j].trim(),url).href,
            quality:resolution?resolution[2]+'p':'Qualidade não informada',audio});
          break;
        }
      }
      if(masters.size<20)masters.set(url,{url,children:links,time:Date.now()});
    }else if(text.includes('#EXT-X-ENDLIST')){
      const times=Array.from(text.matchAll(/#EXTINF:([\d.]+)/g)).map(m=>Number(m[1]));const duration=times.reduce((a,b)=>a+b,0);
      if(duration>=120&&manifests.size<30)manifests.set(url,{url,duration,time:Date.now()});
    }schedule();
  }catch(e){}};
  const hls=(url,type)=>/mpegurl/i.test(type||'')||/\.m3u8(?:[?#]|${'$'})/i.test(url||'');
  const readManifest=async response=>{let reader;try{
    const clone=response.clone();if(!clone.body||!clone.body.getReader)return;
    reader=clone.body.getReader();const decoder=new TextDecoder();let text='',bytes=0;
    while(true){const part=await reader.read();if(part.done)break;bytes+=part.value.byteLength;
      if(bytes>1048576){reader.cancel().catch(()=>{});return;}text+=decoder.decode(part.value,{stream:true});}
    inspect(response.url,text+decoder.decode());
  }catch(e){}finally{try{if(reader)reader.releaseLock();}catch(e){}}};
  const fetchOriginal=window.fetch;
  if(fetchOriginal)window.fetch=function(...args){return fetchOriginal.apply(this,args).then(response=>{
    try{const type=response.headers.get('content-type')||'',length=Number(response.headers.get('content-length')||0);if(hls(response.url,type)&&length<=1048576)readManifest(response);}catch(e){}return response;
  });};
  const openOriginal=XMLHttpRequest.prototype.open;
  XMLHttpRequest.prototype.open=function(...args){this.addEventListener('load',()=>{try{if((this.responseType===''||this.responseType==='text')&&hls(this.responseURL,this.getResponseHeader('content-type')))inspect(this.responseURL,this.responseText);}catch(e){}},{once:true});return openOriginal.apply(this,args);};
  window.addEventListener('message',e=>{const d=e.data;if(!d||typeof d!=='object')return;
    if(e.source===parent&&d.type==='REI_FRAME_INFO'){frame={known:!!d.known,allowed:!!d.allowed,width:Number(d.width)||0,height:Number(d.height)||0};schedule();}
    if(e.source===parent&&d.type==='REI_SCAN'){if(d.prepare)prepare();scan();scanOptions(!!d.prepare);children().forEach(f=>{try{f.contentWindow.postMessage({type:'REI_SCAN',prepare:!!d.prepare},'*')}catch(e){}});}
    if(d.type==='REI_FRAME_REQUEST')children().filter(f=>f.contentWindow===e.source).forEach(sendFrame);
  });
  // Only an explicit toolbar request asks the existing visible player to load
  // metadata. Never autoplay, click ads, or scrape arbitrary script URLs.
  const prepare=()=>{if(!frame.known||!frame.allowed)return;
    Array.from(document.querySelectorAll('video')).forEach(v=>{const r=rect(v);
      if(!r||r.width<200||r.height<110||adElement(v)||v.readyState>=1||v.autoplay&&v.muted&&v.loop)return;
      const source=v.currentSrc||v.src||(v.querySelector('source[src]')||{}).src;
      if(source&&!badUrl(source)){v.preload='metadata';if(v.networkState===0||v.networkState===3)v.load();}
    });
  };
  window.__reiScanPlayers=(prepareMetadata=false)=>{if(prepareMetadata)prepare();scan();scanOptions(prepareMetadata);children().forEach(f=>{sendFrame(f);try{f.contentWindow.postMessage({type:'REI_SCAN',prepare:prepareMetadata},'*')}catch(e){}});};
  document.addEventListener('loadstart',()=>{clear();manifests.clear();masters.clear();schedule();},true);
  ['loadedmetadata','durationchange','play','pause','timeupdate','emptied'].forEach(event=>document.addEventListener(event,schedule,true));
  document.addEventListener('DOMContentLoaded',()=>{window.__reiScanPlayers();new MutationObserver(schedule).observe(document.documentElement,{childList:true,subtree:true,attributes:true,attributeFilter:['src','class','style']});},{once:true});
  window.addEventListener('resize',schedule);window.addEventListener('scroll',schedule);
  if(parent!==window)parent.postMessage({type:'REI_FRAME_REQUEST'},'*');
})();
    """.trimIndent()
}
