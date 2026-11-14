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
    @Volatile private var fallback:BrowserFallbackBlocker?=null
    @Volatile var diagnostic:String=""; private set
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
    private fun bundled(context:Context,s:ListSource):ByteArray =
        GZIPInputStream(context.assets.open("browser-filter/"+s.id+".filterdata")).use {it.readBytes()}

    private fun loadSource(context:Context,s:ListSource):Loaded {
        val local=cache(context,s)
        if(local.exists()) {
            try {
                // A complete gzip can still contain an invalid or stale filter list.
                // Validate it with the engine before trusting it over the APK copy.
                return client(s,GZIPInputStream(local.inputStream()).use {it.readBytes()})
            } catch(e:Exception) {
                android.util.Log.w("ReiAnixFilters","Cache inválido para "+s.id+"; restaurando lista embarcada",e)
            }
        }
        return client(s,bundled(context,s))
    }
    private fun client(s:ListSource,data:ByteArray):Loaded {
        require(data.size in 1000..MAX_BYTES)
        val text=data.toString(Charsets.UTF_8)
        val header=text.trimStart('\uFEFF',' ','\n','\r','\t')
        require(!header.startsWith('<') && !header.startsWith('{'))
        val engine=AdBlockClient(s.id)
        try {
            engine.loadBasicData(data,true)
            engine.isGenericElementHidingEnabled=true
            require(engine.getFiltersCount()>100)
            return Loaded(s,engine)
        }
        catch(t:Throwable) {engine.close();throw t}
    }
    suspend fun prepare(context:Context):String=withContext(Dispatchers.IO) {
        // A refresh can take time on a slow network. Already loaded engines are
        // immediately usable; opening another tab must not wait for that work.
        if(loaded.isEmpty() && fallback==null)updateLock.withLock {
            if(loaded.isEmpty() && fallback==null) {
                val items=mutableListOf<Loaded>()
                val inputs=sources(context)
                try {
                    for(source in inputs) {
                        try {
                            items+=loadSource(context,source)
                        } catch(c:CancellationException) {throw c}
                        catch(e:Exception) {
                            diagnostic=e.javaClass.simpleName+": "+e.message.orEmpty().take(180)
                            android.util.Log.e("ReiAnixFilters","Falha ao carregar lista "+source.id,e)
                        } catch(e:LinkageError) {
                            diagnostic=e.javaClass.simpleName+": "+e.message.orEmpty().take(180)
                            android.util.Log.e("ReiAnixFilters","Motor nativo indisponível para "+source.id,e)
                            break
                        }
                    }
                    check(items.any {!it.source.advanced}) {"Nenhuma lista básica de anúncios foi carregada"}
                    synchronized(lock) {
                        loaded=items.toList()
                        status=items.sumOf {it.client.getFiltersCount()}.toString()+
                            " regras • "+items.size+"/"+inputs.size+" listas"
                    }
                } catch(t:Throwable) {
                    items.forEach {runCatching {it.client.close()}}
                    if(t is CancellationException)throw t
                    diagnostic=(t.javaClass.simpleName+": "+t.message.orEmpty()).take(240)+
                        (if(diagnostic.isNotBlank())" • "+diagnostic else "")
                    android.util.Log.e("ReiAnixFilters","Motor nativo falhou; iniciando regras compatíveis",t)
                    try {
                        val readers=sources(context).map {source ->
                            source.advanced to GZIPInputStream(
                                context.assets.open("browser-filter/"+source.id+".filterdata")
                            ).reader(Charsets.UTF_8)
                        }
                        val compatible=BrowserFallbackBlocker.compile(readers)
                        check(compatible.ruleCount>100) {"Nenhuma regra compatível disponível"}
                        synchronized(lock) {
                            fallback=compatible
                            status="Bloqueio ativo (compatibilidade): "+compatible.ruleCount+
                                " regras • "+compatible.listCount+" listas"
                        }
                    } catch(fallbackFailure:Throwable) {
                        status="Bloqueador indisponível"
                        android.util.Log.e("ReiAnixFilters","Falha no bloqueador alternativo",fallbackFailure)
                        throw fallbackFailure
                    }
                }
            }
        }
        // Updates never delay opening the first tab or destroy working offline rules.
        if(System.currentTimeMillis()-lastAttempt>24*60*60*1000L)maintenance.launch {refresh(context,false)}
        status
    }
    suspend fun refresh(context:Context,force:Boolean=true):String=withContext(Dispatchers.IO) {
        if(loaded.isEmpty() && fallback==null)prepare(context)
        if(loaded.isEmpty() && fallback!=null)return@withContext status
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
                            GZIPOutputStream(object:java.io.FilterOutputStream(stream) {
                                override fun close() {flush()} // AtomicFile owns the descriptor.
                            }).use {it.write(bytes)}
                            atomic.finishWrite(stream)
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
        fallback?.let {
            val kind=when(type) {
                ResourceType.CSS -> "stylesheet"
                ResourceType.UNKNOWN -> "other"
                else -> type.name.lowercase()
            }
            return@synchronized it.blocks(url,page,kind,advanced)
        }
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
        fallback?.let {
            return@synchronized JSONObject().put("css",it.css(url,advanced))
                .put("extended",JSONArray()).put("scripts",JSONArray()).toString()
        }
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
