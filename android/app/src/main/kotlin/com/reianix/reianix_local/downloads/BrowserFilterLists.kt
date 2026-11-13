package com.reianix.reianix_local.downloads

import android.content.Context
import io.github.edsuns.adblockclient.AdBlockClient
import io.github.edsuns.adblockclient.ResourceType
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/** Fixed HTTPS subscriptions, last-good cache and an offline snapshot ship with the app. */
internal object BrowserFilterLists {
    private data class ListSource(val id:String,val url:String,val advanced:Boolean)
    private data class Loaded(val source:ListSource,val client:AdBlockClient)
    private val lock=Any()
    private val maintenance=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private val updateLock=Mutex()
    @Volatile private var loaded:List<Loaded> = emptyList()
    @Volatile var status="Carregando filtros…"; private set
    private const val MAX_BYTES=12*1024*1024
    @Volatile private var lastAttempt=0L

    private fun sources(context:Context):List<ListSource> {
        val json=context.assets.open("browser-filter/sources.json").bufferedReader().use {JSONArray(it.readText())}
        return (0 until json.length()).map {i->json.getJSONObject(i).let {
            ListSource(it.getString("id"),it.getString("url"),it.getBoolean("advanced"))
        }}
    }
    private fun cache(context:Context,s:ListSource)=File(context.filesDir,"browser-filters/${s.id}.txt.gz")
    private fun read(context:Context,s:ListSource):ByteArray {
        val local=cache(context,s)
        return runCatching {if(local.exists())GZIPInputStream(local.inputStream()).use {it.readBytes()}
            else error("no cache")}.getOrElse {
            GZIPInputStream(context.assets.open("browser-filter/${s.id}.txt.gz")).use {it.readBytes()}
        }
    }
    private fun client(s:ListSource,data:ByteArray):Loaded {
        require(data.size in 1000..MAX_BYTES)
        val text=data.toString(Charsets.UTF_8)
        require(!text.trimStart().startsWith('<') && (text.startsWith('!') || text.startsWith('[')))
        val engine=AdBlockClient(s.id)
        try {engine.loadBasicData(data,true);require(engine.getFiltersCount()>100);return Loaded(s,engine)}
        catch(t:Throwable) {engine.close();throw t}
    }
    suspend fun prepare(context:Context)=withContext(Dispatchers.IO) {
        updateLock.withLock {
            if(loaded.isEmpty()) {
                val items=mutableListOf<Loaded>()
                try {
                    sources(context).forEach {items+=client(it,read(context,it))}
                    synchronized(lock) {loaded=items.toList();status="${items.sumOf {it.client.getFiltersCount()}} regras • 5 listas"}
                } catch(t:Throwable) {items.forEach {it.client.close()};throw t}
            }
        }
        // Updates never delay opening the first tab or destroy working offline rules.
        if(System.currentTimeMillis()-lastAttempt>24*60*60*1000L)maintenance.launch {refresh(context,false)}
        status
    }
    suspend fun refresh(context:Context,force:Boolean=true)=withContext(Dispatchers.IO) {
        updateLock.withLock {
            val now=System.currentTimeMillis()
            if(!force && now-lastAttempt<24*60*60*1000L)return@withLock
            lastAttempt=now
            var updated=0
            for(s in sources(context)) {
                val file=cache(context,s)
                if(!force && file.exists() && now-file.lastModified()<24*60*60*1000L)continue
                try {
                    // No page cookies, history, media URLs or credentials go to list servers.
                    val c=java.net.URL(s.url).openConnection() as java.net.HttpURLConnection
                    c.connectTimeout=8000;c.readTimeout=8000
                    val bytes=try {
                        require(c.responseCode==200)
                        require(c.getHeaderFieldLong("Content-Length",-1)<=MAX_BYTES)
                        c.inputStream.use {input ->
                            val out=java.io.ByteArrayOutputStream();val buffer=ByteArray(16384)
                            while(true) {currentCoroutineContext().ensureActive();val n=input.read(buffer);if(n<0)break
                                require(out.size()+n<=MAX_BYTES);out.write(buffer,0,n)}
                            out.toByteArray()
                        }
                    }finally {c.disconnect()}
                    val replacement=client(s,bytes)
                    try {
                        file.parentFile?.mkdirs()
                        val atomic=android.util.AtomicFile(file)
                        val stream=atomic.startWrite()
                        try {
                            // finishWrite owns/closes the file stream, not the gzip wrapper.
                            val gzip=GZIPOutputStream(stream);gzip.write(bytes);gzip.finish();atomic.finishWrite(stream)
                        }catch(t:Throwable){atomic.failWrite(stream);throw t}
                        synchronized(lock) {
                            val old=loaded.firstOrNull {it.source.id==s.id}
                            loaded=loaded.filterNot {it.source.id==s.id}+replacement
                            old?.client?.close()
                        }
                        updated++
                    }catch(t:Throwable){replacement.client.close();throw t}
                }catch(c:CancellationException){throw c}
                catch(_:Exception){ /* Last-good filters remain active. */ }
            }
            synchronized(lock) {
                status="${loaded.sumOf {it.client.getFiltersCount()}} regras • "+
                    if(updated>0)"listas atualizadas" else "usando listas salvas"
            }
        }
        status
    }
    fun blocked(url:String,page:String,type:ResourceType,advanced:Boolean):Boolean=synchronized(lock) {
        var block=false
        for(item in loaded) {
            if(item.source.advanced && !advanced)continue
            val match=item.client.matches(url,page,type)
            // Explicit exceptions in subscriptions take priority across lists.
            if(match.matchedExceptionRule!=null)return@synchronized false
            block=block||match.shouldBlock
        }
        block
    }
    fun cosmetics(url:String,advanced:Boolean):String=synchronized(lock) {
        val css=StringBuilder();val extended=JSONArray();val scripts=JSONArray()
        for(item in loaded) {
            if(item.source.advanced && !advanced)continue
            item.client.getElementHidingSelectors(url)?.takeIf {it.isNotBlank()}?.let {
                css.append(it).append("{display:none!important;}\n")
            }
            if(advanced) {
                item.client.getExtendedCssSelectors(url)?.forEach {extended.put(it+"{display:none!important;}")}
                item.client.getCssRules(url)?.forEach {css.append(it).append('\n')}
                item.client.getScriptlets(url)?.forEach {raw -> parseArguments(raw)?.let {scripts.put(JSONArray(it))}}
            }
        }
        JSONObject().put("css",css.toString()).put("extended",extended).put("scripts",scripts).toString()
    }
    /** Parse strings only: filter list text never becomes arbitrary executable JavaScript. */
    internal fun parseArguments(raw:String):List<String>? {
        if(raw.length>8192)return null
        val result=mutableListOf<String>();var i=0
        while(i<raw.length) {
            while(i<raw.length && raw[i].isWhitespace())i++
            if(i==raw.length)break
            val quote=raw[i++];if(quote!='\'' && quote!='"')return null
            val value=StringBuilder();var closed=false
            while(i<raw.length) {
                val c=raw[i++]
                if(c==quote){closed=true;break}
                if(c=='\\' && i<raw.length) {
                    val next=raw[i++]
                    if(next!=quote && next!='\\')value.append('\\')
                    value.append(next)
                }else value.append(c)
            }
            if(!closed)return null
            result+=value.toString()
            while(i<raw.length && raw[i].isWhitespace())i++
            if(i<raw.length && raw[i++]!=',')return null
        }
        return result.takeIf {it.isNotEmpty()}
    }
}
