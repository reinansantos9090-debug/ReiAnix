package com.reianix.reianix_local.downloads

import java.net.URI

/** Small offline ruleset. No remote scripts or extra network service. */
internal object BrowserAdBlock {
    @Volatile var mode="balanced"
    private val common=setOf("doubleclick.net","googlesyndication.com","googleadservices.com",
        "adsterra.com","adsterra.net","popads.net","popcash.net","propellerads.com",
        "onclicka.com","onclickalgo.com","hilltopads.net","trafficjunky.net","exoclick.com",
        "juicyads.com","adnxs.com","adsrvr.org","advertising.com","outbrain.com","taboola.com")
    private val extra=setOf("criteo.com","criteo.net","ads.pubmatic.com","rubiconproject.com",
        "openx.net","demdex.net","scorecardresearch.com")
    fun host(url:String)=runCatching {URI(url).host.orEmpty().lowercase().trimEnd('.')}.getOrDefault("")
    fun related(a:String,b:String):Boolean {
        val x=host(a);val y=host(b)
        return x.isNotBlank() && y.isNotBlank() && (x==y || x.endsWith(".$y") || y.endsWith(".$x"))
    }
    fun blocked(url:String,selected:String=mode):Boolean {
        if(selected=="off")return false
        val h=host(url)
        return (common.asSequence()+if(selected=="advanced")extra.asSequence() else emptySequence())
            .any {h==it || h.endsWith(".$it")}
    }
    val cosmeticScript="""
        (()=>{if(window.__reiAdStyle)return;window.__reiAdStyle=true;
          const style=document.createElement('style');
          style.textContent='ins.adsbygoogle,[data-ad-slot],[id^="div-gpt-ad"],iframe[src*="doubleclick.net"]{display:none!important}';
          (document.head||document.documentElement).appendChild(style);
        })();
    """.trimIndent()
}
