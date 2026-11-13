package com.reianix.reianix_local.downloads

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
        onDownload: (BrowserMediaOption,String)->Unit = { _,_ -> },
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
                if(obj.optString("kind") in setOf("option","download")) {
                    val option=BrowserMediaOption.parse(obj) ?: return@runCatching
                    if(!DownloadHttp.sameOrigin(java.net.URL(option.page),java.net.URL(sourceOrigin.toString())))return@runCatching
                    if(obj.optString("kind")=="download")onDownload(option,
                        obj.optString("title").take(240).filterNot {it.isISOControl()}) else onOption(option)
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
  let lastKey='',lastSent=0,scheduled=false,lastEmbeddedScan=0;
  const manifests=new Map(),masters=new Map(),emittedOptions=new Map(),directMedia=new Map();
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
  const qualityFrom=(label,url)=>{const text=(/^(2160|1440|1080|720|480|360|240)$/.test(String(label||'').trim())?String(label).trim()+'p':String(label||''))+' '+String(url||'');
    let m=/(?:^|[^0-9])(2160|1440|1080|720|480|360|240)\s*p(?:[^a-z]|$)/i.exec(text);
    if(m)return m[1]+'p';
    m=/(?:quality|res|height|q)[=:/_-](2160|1440|1080|720|480|360|240)(?:[^0-9]|$)/i.exec(text);
    if(m)return m[1]+'p';
    try{m=/(?:^|[-_/])(2160|1440|1080|720|480|360|240)\.(?:m3u8|mp4|webm)(?:[?#]|$)/i.exec(String(url||''));}catch(e){}
    return m?m[1]+'p':'Qualidade não informada';
  };
  const sendOption=(raw,label,origin,separateAudio=false,mimeHint='',masterUrl='',estimatedBytes=0)=>{try{
    if(!frame.known||!frame.allowed)return;
    const url=new URL(raw,location.href).href;
    const hint=String(mimeHint||'').toLowerCase();
    const format=mediaFormat(url)||
      (hint.includes('mpegurl')?'hls':hint.includes('dash+xml')?'dash':
        hint.startsWith('video/')?'video':origin==='visible-video'?'video':'');
    if(!format||badUrl(url)||url.length>16384)return;
    const quality=qualityFrom(label,url);
    const key=url+'|'+quality+'|'+masterUrl+'|'+estimatedBytes,now=Date.now();
    if(emittedOptions.get(key)&&now-emittedOptions.get(key)<4000)return;
    if(emittedOptions.size>=96)emittedOptions.clear();
    emittedOptions.set(key,now);
    message({kind:'option',url,quality,format,origin,separateAudio:!!separateAudio,mimeHint:hint,masterUrl,estimatedBytes});
  }catch(e){}};
  const scanOptions=(prepareMasters=false)=>{try{
    if(!frame.known||!frame.allowed)return;
    const visible=Array.from(document.querySelectorAll('video')).filter(v=>{
      const r=rect(v);return r&&r.width>=200&&r.height>=110&&!adElement(v);
    }).slice(0,8);
    const sources=new Set(),pendingMasters=new Set();
    const offer=(raw,label,origin,mimeHint='')=>{try{
      if(!raw)return;
      const url=new URL(raw,location.href).href;
      const hint=String(mimeHint||'').toLowerCase();
      const format=mediaFormat(url)||
        (hint.includes('mpegurl')?'hls':hint.includes('dash+xml')?'dash':hint.startsWith('video/')?'video':'');
      if(!format&&origin!=='visible-video')return;
      if(badUrl(url))return;
      sources.add(url);
      if(format==='hls')pendingMasters.add(url);
      sendOption(url,label,origin,false,hint);
    }catch(e){}};
    // Many pages keep every rendition in an inline player configuration and
    // only attach the selected rendition to <video> after Play. Read bounded,
    // already-loaded page data without executing scripts or interacting with
    // the player. Require a media URL and, for direct files, a nearby quality
    // label so unrelated links in scripts are not offered as downloads.
    const qualityIn=snippet=>{
      const values=Array.from((snippet||'').matchAll(/(?:label|quality|resolution|res|height)["']?\s*[:=]\s*["']?\s*(2160|1440|1080|720|480|360|240)\s*p?/gi),match=>match[1]+'p');
      const unique=Array.from(new Set(values));
      return unique.length===1?unique[0]:'';
    };
    const embedded=(raw,context)=>{
      const url=String(raw||'').replace(/\\u0026/gi,'&').replace(/&amp;/gi,'&');
      const format=mediaFormat(url);
      if(!format)return;
      const quality=qualityIn(context)||qualityFrom('',url);
      if(format!=='hls'&&quality==='Qualidade não informada')return;
      offer(url,quality,'source',format==='hls'?'application/vnd.apple.mpegurl':format==='dash'?'application/dash+xml':'video/mp4');
    };
    if(prepareMasters||Date.now()-lastEmbeddedScan>=1500){
      lastEmbeddedScan=Date.now();
      let scriptBudget=0;
      Array.from(document.scripts).slice(0,64).forEach(script=>{
        let text=script.textContent||'';
        if(!text||text.length>131072||scriptBudget+text.length>524288||!/(?:\.m3u8|\.mpd|\.mp4|\.mkv|\.webm|\.m4v)/i.test(text))return;
        scriptBudget+=text.length;
        text=text.replace(/\\u002f/gi,'/').replace(/\\\//g,'/').replace(/\\u0026/gi,'&').replace(/&amp;/gi,'&');
        const pattern=/(?:https?:\/\/|\/\/|\/)[^"'\\\s<>]{1,1536}?\.(?:m3u8|mpd|mp4|mkv|webm|mov|m4v|ts|m2ts)(?:\?[^"'\\\s<>]{0,2048})?/gi;
        let match,count=0;
        while((match=pattern.exec(text))&&count++<48){
          const context=text.slice(Math.max(0,match.index-320),Math.min(text.length,pattern.lastIndex+320));
          embedded(match[0],context);
        }
      });
      // Resource Timing also sees manifests/files loaded by page scripts and
      // players that do not use fetch/XHR. A master playlist is useful even
      // before the video element is mounted; its renditions are expanded after
      // the user explicitly requests intelligent download.
      try{
        performance.getEntriesByType('resource').slice(-160).forEach(entry=>{
          const url=entry&&entry.name||'',format=mediaFormat(url);
          if(format==='hls'||format==='dash')embedded(url,'');
          else if(format==='video'&&qualityFrom('',url)!=='Qualidade não informada')embedded(url,'');
        });
      }catch(e){}
    }
    visible.forEach(v=>{
      offer(v.currentSrc||v.src,v.getAttribute('data-quality')||v.getAttribute('data-res')||(v.readyState>=1&&v.videoHeight?v.videoHeight+'p':''),'visible-video');
      v.querySelectorAll('source[src]').forEach(source=>{
        offer(source.src,source.getAttribute('label')||source.getAttribute('res')||
          source.getAttribute('data-quality')||source.getAttribute('size')||'','source',
          source.getAttribute('type')||'');
      });
    });
    // Read the existing player's declared renditions, including closed quality
    // menus. Never click controls or manufacture a URL by replacing 720 with 360.
    visible.forEach(v=>{
      const root=v.closest('.jwplayer,.video-js,.plyr,.fluid_video_wrapper,[data-player]')||v.parentElement;
      if(!root||adElement(root))return;
      const declared=(raw,label,type='')=>offer(raw,label,'source',type||'video/mp4');
      root.querySelectorAll('source[src],[data-src],[data-url],select option[value]')
        .forEach(node=>{
          const raw=node.getAttribute('src')||node.getAttribute('data-src')||node.getAttribute('data-url')||node.getAttribute('value');
          if(!raw||!/^https?:|^\/|^\.\.\//i.test(raw))return;
          // Unlike <source>, controls must carry an explicit quality label.
          const label=node.getAttribute('label')||node.getAttribute('data-quality')||node.getAttribute('data-res')||node.getAttribute('res')||node.getAttribute('size')||node.textContent||'';
          if(node.tagName!=='SOURCE'&&qualityFrom(label,'')==='Qualidade não informada')return;
          declared(raw,label,node.getAttribute('type')||'');
        });
      try{
        if(root.classList.contains('jwplayer')&&typeof window.jwplayer==='function'){
          const player=window.jwplayer(root.id),entry=player.getPlaylistItem();
          if(entry&&Array.isArray(entry.sources))entry.sources.slice(0,32).forEach(source=>
            declared(source.file,source.label||source.height||'',source.type&&source.type.includes('/')?source.type:''));
        }
      }catch(e){}
      try{
        if(root.classList.contains('video-js')&&window.videojs&&window.videojs.getPlayer){
          const player=window.videojs.getPlayer(root.id);
          if(player&&player.currentSources)player.currentSources().slice(0,32).forEach(source=>
            declared(source.src,source.label||source.res||source.height||'',source.type||''));
        }
      }catch(e){}
    });
    // Explicit quality controls can expose all renditions before Play. Accept
    // only media links that belong to visible controls, never arbitrary hrefs.
    // Direct links/quality controls are useful even on a page that has not
    // mounted its player yet. Each control must itself be visible and the URL
    // must identify a supported media representation.
    Array.from(document.querySelectorAll('[data-quality][data-src],[data-res][data-url],a[download][href]'))
      .slice(0,80).forEach(control=>{
        const r=rect(control);
        if(!r||adElement(control))return;
        offer(control.getAttribute('data-src')||control.getAttribute('data-url')||
          control.getAttribute('href'),
          control.getAttribute('data-quality')||control.getAttribute('data-res')||
          control.textContent||'','quality-link');
      });
    // A direct file request may precede loadedmetadata. Offer it only after
    // the page has exactly one visible, full-duration player.
    const recentDirect=Array.from(directMedia.values())
      .filter(m=>Date.now()-m.time<20000&&!badUrl(m.url));
    if(visible.length===1 && recentDirect.length===1 &&
      Number.isFinite(visible[0].duration) && visible[0].duration>=120)
      sendOption(recentDirect[0].url,
        visible[0].getAttribute('data-quality')||(visible[0].videoHeight?visible[0].videoHeight+'p':''),'visible-video',
        false,recentDirect[0].mime);
    const recent=Array.from(masters.values()).filter(m=>Date.now()-m.time<60000);
    recent.forEach(master=>{
      const related=sources.has(master.url)||
        master.children.some(child=>sources.has(child.url))||
        (visible.length===1&&recent.length===1);
      if(!related)return;
      master.children.forEach(child=>{
        const vod=manifests.get(child.url),playing=visible[0];
        const duration=vod?vod.duration:(visible.length===1&&Number.isFinite(playing.duration)&&playing.duration>=120?playing.duration:0);
        const estimate=child.bandwidth>0&&duration>0?Math.round(child.bandwidth*duration/8):0;
        sendOption(child.url,child.quality,'master',child.audio,'application/vnd.apple.mpegurl',master.url,estimate);
      });
    });
    // Only on an explicit toolbar request: inspect declared playlists, including
    // a different CDN origin. Fetch still enforces CORS; native discovery can
    // read the same declared playlist using the downloader's HTTP policy.
    if(prepareMasters){
      Array.from(pendingMasters).filter(url=>{
        try{return /^https?:$/.test(new URL(url).protocol)&&!masters.has(url);}catch(e){return false;}
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
      const now=Date.now(),matching=Array.from(manifests.values())
        .filter(m=>now-m.time<60000&&Math.abs(m.duration-v.duration)<=Math.max(10,v.duration*0.02));
      matching.sort((a,b)=>b.time-a.time);
      if(matching.length===1){
        url=matching[0].url;source='hls';
      }else{
        // With a visible MSE/blob player, prefer a single recent MP4 response
        // captured from its own fetch/XHR traffic. Never infer arbitrary links.
        const direct=Array.from(directMedia.values())
          .filter(m=>now-m.time<20000&&!badUrl(m.url));
        if(direct.length!==1){clear();return;}
        url=direct[0].url;source='video';
      }
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
            quality:resolution?resolution[2]+'p':qualityFrom('',new URL(lines[j].trim(),url).href),audio,
            bandwidth:Number((/(?:^|,)AVERAGE-BANDWIDTH=(\d+)/i.exec(info.substring(info.indexOf(':')+1))||/(?:^|,)BANDWIDTH=(\d+)/i.exec(info.substring(info.indexOf(':')+1))||[])[1])||0});
          break;
        }
      }
      if(masters.size<20)masters.set(url,{url,children:links,time:Date.now()});
    }else if(text.includes('#EXT-X-ENDLIST')){
      const times=Array.from(text.matchAll(/#EXTINF:([\d.]+)/g)).map(m=>Number(m[1]));const duration=times.reduce((a,b)=>a+b,0);
      if(duration>=120&&manifests.size<30)manifests.set(url,{url,duration,time:Date.now()});
    }schedule();
  }catch(e){}};
  // Some players deliberately serve HLS as .jpg/image/jpeg. Only trust a
  // URL explicitly declared as HLS by this document's player, not random images.
  const hls=(url,type)=>/mpegurl/i.test(type||'')||/\.m3u8(?:[?#]|${'$'})/i.test(url||'')||
    Array.from(document.querySelectorAll('video source[type]')).slice(0,32).some(s=>
      s.src===url&&/mpegurl/i.test(s.getAttribute('type')||''));
  const observeDirect=(raw,type,status)=>{try{
    const mime=String(type||'').split(';')[0].trim().toLowerCase();
    if(!['video/mp4','video/webm','video/quicktime','video/x-matroska',
      'video/x-m4v','video/3gpp'].includes(mime))return;
    if(status!==200&&status!==206)return;
    const url=new URL(raw,location.href).href;
    if(badUrl(url)||url.length>16384)return;
    const players=Array.from(document.querySelectorAll('video')).filter(v=>{
      const r=rect(v);return r&&r.width>=200&&r.height>=110&&
        !adElement(v)&&!(v.autoplay&&v.muted&&v.loop);
    });
    if(players.length!==1)return;
    if(directMedia.size>=20)directMedia.clear();
    directMedia.set(url,{url,time:Date.now(),mime});
    if(Number.isFinite(players[0].duration)&&players[0].duration>=120 &&
      players[0].videoWidth>=160&&players[0].videoHeight>=90)
      sendOption(url,players[0].getAttribute('data-quality')||(players[0].videoHeight?players[0].videoHeight+'p':''),
        'visible-video',false,mime);
    schedule();
  }catch(e){}};
  const readManifest=async response=>{let reader;try{
    const clone=response.clone();if(!clone.body||!clone.body.getReader)return;
    reader=clone.body.getReader();const decoder=new TextDecoder();let text='',bytes=0;
    while(true){const part=await reader.read();if(part.done)break;bytes+=part.value.byteLength;
      if(bytes>1048576){reader.cancel().catch(()=>{});return;}text+=decoder.decode(part.value,{stream:true});}
    inspect(response.url,text+decoder.decode());
  }catch(e){}finally{try{if(reader)reader.releaseLock();}catch(e){}}};
  const fetchOriginal=window.fetch;
  if(fetchOriginal)window.fetch=function(...args){return fetchOriginal.apply(this,args).then(response=>{
    try{
      const type=response.headers.get('content-type')||'',
        length=Number(response.headers.get('content-length')||0);
      if(hls(response.url,type)&&length<=1048576)readManifest(response);
      observeDirect(response.url,type,response.status);
    }catch(e){}return response;
  });};
  const openOriginal=XMLHttpRequest.prototype.open;
  XMLHttpRequest.prototype.open=function(...args){this.addEventListener('load',()=>{
    try{
      const type=this.getResponseHeader('content-type')||'';
      if((this.responseType===''||this.responseType==='text')&&hls(this.responseURL,type))
        inspect(this.responseURL,this.responseText);
      observeDirect(this.responseURL,type,this.status);
    }catch(e){}
  },{once:true});return openOriginal.apply(this,args);};
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
  // Vidstack-based sites may remux an entire HLS video into a blob in JS.
  // Route an explicit selection through the existing native confirmation dialog
  // before that expensive browser-only flow starts. No automatic enqueue.
  document.addEventListener('click',e=>{try{
    if(!e.isTrusted||!window.ReiAnixMedia||!frame.known||!frame.allowed)return;
    // Web Components retarget clicks crossing a Shadow DOM boundary. The real
    // clicked control is still present in composedPath(), even when e.target is
    // only the app-player-download host.
    const path=typeof e.composedPath==='function'?e.composedPath():[];
    const button=path.find(node=>node&&node.tagName==='BUTTON')||
      (e.target&&e.target.closest?e.target.closest('button'):null);
    const download=path.find(node=>node&&node.tagName==='APP-PLAYER-DOWNLOAD')||
      (button&&button.closest?button.closest('app-player-download'):null)||
      (e.target&&e.target.closest?e.target.closest('app-player-download'):null);
    if(!download||!rect(button)||adElement(download))return;
    const quality=(button.textContent||'').trim();
    if(!/^(2160|1440|1080|720|480|360|240)p$/.test(quality))return;
    const player=download.closest('media-player'),video=player&&player.querySelector('video');
    if(!video||!rect(video))return;
    const source=Array.from(video.querySelectorAll('source[src]')).find(s=>/mpegurl/i.test(s.getAttribute('type')||''));
    if(!source||badUrl(source.src))return;
    const heading=player.querySelector('h5,h1,h2'),episode=/\bT(\d+)\s*EP[.\s]*(\d+)/i.exec(player.textContent||'');
    const title=(heading?heading.textContent:document.title)+(episode?' T'+episode[1]+' Episódio '+episode[2]:'');
    message({kind:'download',url:source.src,quality,format:'hls',origin:'source',mimeHint:'application/vnd.apple.mpegurl',title});
    e.preventDefault();e.stopImmediatePropagation();
  }catch(e){}},true);
  document.addEventListener('loadstart',()=>{clear();manifests.clear();masters.clear();directMedia.clear();schedule();},true);
  ['loadedmetadata','durationchange','play','pause','timeupdate','emptied'].forEach(event=>document.addEventListener(event,schedule,true));
  document.addEventListener('DOMContentLoaded',()=>{window.__reiScanPlayers();new MutationObserver(schedule).observe(document.documentElement,{childList:true,subtree:true,attributes:true,attributeFilter:['src','class','style']});},{once:true});
  window.addEventListener('resize',schedule);window.addEventListener('scroll',schedule);
  if(parent!==window)parent.postMessage({type:'REI_FRAME_REQUEST'},'*');
})();
    """.trimIndent()
}
