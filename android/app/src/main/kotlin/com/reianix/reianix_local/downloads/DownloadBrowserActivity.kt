package com.reianix.reianix_local.downloads

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.Manifest
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.webkit.*
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.reianix.reianix_local.ui.theme.ReiAnixComposeTheme
import org.json.JSONArray
import java.util.concurrent.ConcurrentHashMap

/** The selected URL is edited and enqueued over the web page, never via navigation. */
private data class BrowserDownloadOffer(
    val url: String,
    val headers: Map<String,String>,
    val title: String,
    val filename: String,
    val capture: PlayerMediaCandidate?,
    val hlsHint: Boolean,
    val browserLength: Long? = null,
    val providerEstimatedSize:Long?=null,
)

/** Full-screen browser. Only explicit user actions enqueue a transfer. No JS bridge or SSL bypass. */
class DownloadBrowserActivity: ComponentActivity() {
    private val tabs=mutableStateListOf<BrowserTab>()
    private var activeTabId by mutableStateOf("")
    private var requested by mutableStateOf("")
    private var sourceHeaders by mutableStateOf<Map<String,String>>(emptyMap())
    private var pendingOffer by mutableStateOf<BrowserDownloadOffer?>(null)
    private var offerError by mutableStateOf("")
    private var web: WebView?=null
    private val mediaRequests=ConcurrentHashMap<String,Pair<Long,Map<String,String>>>()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); accept(intent)
        val restored=savedInstanceState?.getStringArrayList("browser.urls").orEmpty()
        if(restored.isNotEmpty())restored.forEach {tabs.add(BrowserTab(it))}
        if(tabs.isEmpty())tabs.add(BrowserTab(requested,sourceHeaders))
        activeTabId=tabs.getOrElse(savedInstanceState?.getInt("browser.selected") ?: 0){tabs.first()}.id
        setContent { ReiAnixComposeTheme { BrowserShell() } }
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent);accept(intent)
        if(requested.isNotBlank()) {val tab=BrowserTab(requested,sourceHeaders);tabs.add(tab);activeTabId=tab.id}
    }
    private fun accept(intent: Intent) {
        // Nuvio's external-player ACTION_VIEW can also land in the browser,
        // which has a separate Android VIEW intent filter. Preserve the same
        // String[] headers here as in DownloadsActivity.
        sourceHeaders=DownloadIncomingHeaders.parse(intent)
        val raw=if(intent.action==Intent.ACTION_SEND) intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty() else intent.dataString.orEmpty()
        val candidates=DownloadSourceSelection.choose(
            texts=listOf(raw,intent.getStringExtra(Intent.EXTRA_SUBJECT).orEmpty()),
            explicitUrls=listOfNotNull(intent.dataString,
                intent.getStringExtra("url"),
                intent.getStringExtra("downloadUrl"),
                intent.getStringExtra("mediaUrl"))
        )
        requested=candidates.firstOrNull()?.url.orEmpty()
        intent.data=null; intent.removeExtra(Intent.EXTRA_TEXT)
    }
    override fun onSaveInstanceState(outState:Bundle) {
        super.onSaveInstanceState(outState)
        outState.putStringArrayList("browser.urls",ArrayList(tabs.map {if(it.home)"" else it.url}))
        outState.putInt("browser.selected",tabs.indexOfFirst {it.id==activeTabId}.coerceAtLeast(0))
    }
    @Composable private fun BrowserShell() {
        var overview by remember {mutableStateOf(false)}
        fun newTab(url:String="") {
            val tab=BrowserTab(url,if(url==requested)sourceHeaders else emptyMap())
            tabs.add(tab);activeTabId=tab.id;overview=false
        }
        LaunchedEffect(requested) {
            if(requested.isNotBlank() && tabs.none {it.initialUrl==requested})newTab(requested)
        }
        if(overview) BrowserTabOverview(tabs.toList(),activeTabId,
            onSelect={activeTabId=it.id;overview=false},
            onClose={tab ->
                val index=tabs.indexOf(tab);tabs.remove(tab)
                if(tabs.isEmpty())newTab()
                else if(activeTabId==tab.id)activeTabId=tabs[(index-1).coerceAtLeast(0).coerceAtMost(tabs.lastIndex)].id
            },onNew={newTab()},onBack={overview=false})
        else tabs.firstOrNull {it.id==activeTabId}?.let {tab ->
            key(tab.id) {Browser(tab,onTabs={overview=true},onNewTab={newTab()})}
        }
    }
    @Composable private fun Browser(tab:BrowserTab,onTabs:()->Unit,onNewTab:()->Unit) {
        val requested=tab.initialUrl
        var menu by remember {mutableStateOf(false)}
        var showSettings by remember {mutableStateOf(false)}
        var protectionReady by remember {mutableStateOf(false)}
        var protectionError by remember {mutableStateOf("")}
        var rememberedMode by remember {mutableStateOf(BrowserAdBlock.mode)}
        var address by remember { mutableStateOf(if(tab.home)"" else tab.url) }
        var backAvailable by remember {mutableStateOf(false)}
        var forwardAvailable by remember {mutableStateOf(false)}
        var restoreScroll by remember {mutableStateOf(!tab.saved.isEmpty)}
        var title by remember { mutableStateOf("") }
        var progress by remember { mutableIntStateOf(0) }
        var showMedia by remember { mutableStateOf(false) }
        var submitting by remember { mutableStateOf(false) }
        val downloadStore=remember {DownloadStore(applicationContext)}
        val scope=rememberCoroutineScope()
        val notificationPermission=rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission(),
        ) { }
        var candidates by remember { mutableStateOf<List<BrowserDownloadChoice>>(emptyList()) }
        var error by remember { mutableStateOf("") }
        val observed=remember {ConcurrentHashMap<String,PlayerMediaCandidate>()}
        val sourceOptions=remember {ConcurrentHashMap<String,BrowserMediaOption>()}
        val inspectedHls=remember {mutableSetOf<String>()}
        val hlsJobs=remember {mutableListOf<Job>()}
        var discoveryRequested by remember {mutableStateOf(false)}
        var discoveryGeneration by remember {mutableIntStateOf(0)}
        var discoveringHls by remember {mutableIntStateOf(0)}
        fun updateCandidates() {
            val now=System.nanoTime()/1_000_000
            val confirmed=observed.values.filter {
                now-it.receivedAt in 0..20000 && PlayerMediaCandidate.eligible(it)
            }
            // Keep the existing largest-visible-player filter to avoid ad players.
            val largest=confirmed.maxOfOrNull {it.area} ?: 0.0
            val main=confirmed.filter {it.area>=largest*0.75}
            val visible=sourceOptions.values.filter {
                now-it.receivedAt in 0..60000 && BrowserMediaOption.eligible(it)
            }
            candidates=BrowserMediaResults.combine(main,visible).filter {
                Regex("""\b\d{2,4}p\b""").containsMatchIn(it.quality)
            }
        }
        fun discoverHls() {
            if(!discoveryRequested)return
            val generation=discoveryGeneration
            sourceOptions.values.filter {it.format=="hls" && it.masterUrl.isBlank() &&
                BrowserMediaOption.eligible(it)}.sortedBy {it.url}.forEach { source ->
                val key=source.frame+"::"+source.url
                if(inspectedHls.size>=4 || !inspectedHls.add(key))return@forEach
                discoveringHls++
                hlsJobs+=scope.launch {
                    try {
                        val provided=mediaRequests[source.url]?.second.orEmpty().toMutableMap()
                        if(provided.keys.none {it.equals("Referer",true)})provided["Referer"]=source.page
                        web?.settings?.userAgentString?.let {provided["User-Agent"]=it}
                        val headers=DownloadSessionHeaders.resolve(applicationContext,source.url,provided)
                        val variants=withTimeoutOrNull(8_000) {
                            BrowserHlsDiscovery.inspect(source,headers)
                        }.orEmpty()
                        if(generation==discoveryGeneration && sourceOptions.containsKey(key)) {
                            variants.forEach { variant ->
                                val variantKey=variant.frame+"::"+variant.url
                                if(sourceOptions.size<96 || sourceOptions.containsKey(variantKey)) {
                                    val previous=sourceOptions[variantKey]
                                    sourceOptions[variantKey]=variant.copy(
                                        estimatedBytes=previous?.estimatedBytes)
                                }
                            }
                            updateCandidates()
                        }
                    } catch(c:CancellationException) {throw c}
                    catch(_:Exception) { /* Keep other resolved sources; allow a new toolbar retry. */ }
                    finally {if(generation==discoveryGeneration)discoveringHls--}
                }
            }
        }
        val browser=remember {
            WebView(this).apply {
                web=this
                BrowserAdBlock.install(applicationContext,this,scope)
                BrowserMediaCapture.install(
                    this,
                    onMedia = { frame,item ->
                        if(item==null)observed.remove(frame)
                        else if(observed.size<20 || observed.containsKey(frame))observed[frame]=item
                    },
                    onOption = { item ->
                        val key=item.frame+"::"+item.url
                        val previous=sourceOptions[key]
                        // A later scan without metadata must not erase a known resolution.
                        if(sourceOptions.size<96 || previous!=null)sourceOptions[key]=item.copy(
                            quality=if(item.quality=="Qualidade não informada" && previous!=null)
                                previous.quality else item.quality,
                            masterUrl=item.masterUrl.ifBlank {previous?.masterUrl.orEmpty()},
                            estimatedBytes=item.estimatedBytes ?: previous?.estimatedBytes,
                        )
                        discoverHls()
                        if(showMedia)updateCandidates()
                    },
                )
                settings.javaScriptEnabled=true; settings.domStorageEnabled=true
                settings.allowFileAccess=false; settings.allowContentAccess=false
                settings.useWideViewPort=true; settings.loadWithOverviewMode=true
                settings.setSupportMultipleWindows(true)
                settings.mixedContentMode=WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                CookieManager.getInstance().setAcceptCookie(true)
                CookieManager.getInstance().setAcceptThirdPartyCookies(this,true)
                webViewClient=object: WebViewClient() {
                    override fun shouldInterceptRequest(view: WebView,request: WebResourceRequest): WebResourceResponse? {
                        if(!request.isForMainFrame && BrowserAdBlock.blocked(request,tab.url))
                            return WebResourceResponse("text/plain","UTF-8",java.io.ByteArrayInputStream(byteArrayOf()))
                        if(request.method=="GET" && request.url.scheme in setOf("http","https")) {
                            val headers=request.requestHeaders.filter { (key,value) ->
                                key.lowercase() in setOf("origin","referer","user-agent","authorization","cookie") &&
                                    value.length<=16384 && value.none {it=='\r'||it=='\n'}
                            }
                            if(mediaRequests.size>=128) mediaRequests.entries.minByOrNull {it.value.first}?.let {mediaRequests.remove(it.key,it.value)}
                            mediaRequests[request.url.toString()]=android.os.SystemClock.elapsedRealtime() to headers
                        }
                        return null
                    }
                    override fun shouldOverrideUrlLoading(view: WebView,request: WebResourceRequest): Boolean {
                        if(request.url.scheme !in setOf("http","https")) { return true }
                        if(BrowserAdBlock.blocked(request,tab.url))return true
                        return false
                    }
                    override fun onPageStarted(view: WebView,url: String,favicon: Bitmap?) {
                        discoveryGeneration++;discoveryRequested=false
                        hlsJobs.forEach {it.cancel()};hlsJobs.clear();inspectedHls.clear();discoveringHls=0
                        if(!tab.home)address=url;tab.url=url;observed.clear();sourceOptions.clear();mediaRequests.clear();error=""
                    }
                    override fun onPageFinished(view: WebView,url: String) {
                        if(!tab.home)address=url
                        tab.url=url;title=view.title.orEmpty();tab.title=title.ifBlank {BrowserAdBlock.host(url)}
                        backAvailable=view.canGoBack();forwardAvailable=view.canGoForward()
                        if(restoreScroll) {restoreScroll=false;view.post {view.scrollTo(tab.scrollX,tab.scrollY)}}
                    }
                    override fun onReceivedError(view:WebView,request:WebResourceRequest,failure:WebResourceError) {
                        if(request.isForMainFrame)error="Não foi possível carregar a página: ${failure.description}"
                    }

                }
                webChromeClient=object: WebChromeClient() {
                    override fun onProgressChanged(view: WebView,n: Int) { progress=n }
                    // Open a requested new window within this browser instead of silently dropping download links.
                    override fun onCreateWindow(view: WebView,isDialog: Boolean,isUserGesture: Boolean,resultMsg: android.os.Message): Boolean {
                        if(!isUserGesture) return false
                        val popup=WebView(this@DownloadBrowserActivity)
                        popup.settings.javaScriptEnabled=true
                        popup.settings.domStorageEnabled=true
                        popup.settings.allowFileAccess=false;popup.settings.allowContentAccess=false
                        popup.webViewClient=object: WebViewClient() {
                            override fun shouldOverrideUrlLoading(v: WebView,r: WebResourceRequest): Boolean {
                                if(r.url.toString()=="about:blank")return false
                                val target=r.url.toString()
                                if(r.url.scheme in setOf("http","https") && !BrowserAdBlock.blocked(target,
                                    page=tab.url,type=io.github.edsuns.adblockclient.ResourceType.POPUP)) {
                                    when {
                                        BrowserAdBlock.mode=="off" || BrowserAdBlock.related(view.url.orEmpty(),target) -> view.loadUrl(target)
                                        // Unknown third-party popup windows are silently denied.
                                        else -> Unit
                                    }
                                }
                                popup.destroy(); return true
                            }
                        }
                        popup.setDownloadListener { url,ua,cd,mime,length ->
                            offer(url,ua,cd,mime,title,view.url.orEmpty(),browserLength=length.takeIf{it>0});
                            popup.destroy()
                        }
                        (resultMsg.obj as WebView.WebViewTransport).webView=popup; resultMsg.sendToTarget(); return true
                    }
                }
                setDownloadListener { url,ua,cd,mime,length ->
                    offer(url,ua,cd,mime,title,this.url.orEmpty(),browserLength=length.takeIf{it>0})
                }
            }
        }
        LaunchedEffect(tab.id) {
            try {
                val deps=com.reianix.reianix_local.ReiAnixAppDependencies.from(applicationContext)
                BrowserAdBlock.mode=withContext(Dispatchers.IO) {
                    deps.bootstrap.awaitStarted()
                    deps.sqliteSettingsRepository.getPreference("browser.ad_mode","balanced") ?: "balanced"
                }
                rememberedMode=BrowserAdBlock.mode
                if(BrowserAdBlock.mode!="off")BrowserAdBlock.prepare(applicationContext)
                protectionReady=true
                if(tab.saved.isEmpty || browser.restoreState(tab.saved)==null) {
                    if(tab.url.isNotBlank()) {DownloadHttp.validateUrl(tab.url);browser.loadUrl(tab.url)}
                }
            }catch(c:CancellationException){throw c}
            catch(_:Exception){protectionError="Não foi possível iniciar o bloqueador. Abra Configurações para desativar ou tentar novamente."}
        }
        LaunchedEffect(showSettings) {
            if(!showSettings && rememberedMode!=BrowserAdBlock.mode) {
                protectionReady=false
                try {
                    if(BrowserAdBlock.mode!="off")BrowserAdBlock.prepare(applicationContext)
                    rememberedMode=BrowserAdBlock.mode;protectionError="";protectionReady=true
                    if(!tab.home)browser.reload()
                }catch(c:CancellationException){throw c}
                catch(_:Exception){protectionError="Não foi possível carregar os filtros. Tente atualizar em Configurações."}
            }
        }
        DisposableEffect(browser) {onDispose {
            tab.saved=Bundle();browser.saveState(tab.saved)
            tab.scrollX=browser.scrollX;tab.scrollY=browser.scrollY
            browser.onPause();browser.stopLoading();browser.destroy();web=null
            mediaRequests.clear();pendingOffer=null
        }}
        fun captureTab() {
            if(browser.width>0 && browser.height>0 && !tab.home)runCatching {
                val bitmap=Bitmap.createBitmap(240,420,Bitmap.Config.RGB_565)
                val canvas=android.graphics.Canvas(bitmap)
                canvas.scale(240f/browser.width,420f/browser.height)
                browser.draw(canvas);tab.preview=bitmap
            }
        }
        BackHandler {
            when {
                showSettings -> showSettings=false
                tab.home && browser.url!=null -> {tab.home=false;browser.onResume();address=tab.url}
                pendingOffer!=null -> { if(!submitting)pendingOffer=null }
                showMedia -> showMedia=false
                browser.canGoBack() -> browser.goBack()
                else -> finish()
            }
        }
        if(showSettings) {
            DownloadSettingsScreen {showSettings=false}
            return
        }
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                    OutlinedTextField(address,{address=it},singleLine=true,modifier=Modifier.weight(1f),label={Text("Endereço")},
                        keyboardOptions=KeyboardOptions(imeAction=ImeAction.Go),keyboardActions=KeyboardActions(onGo={
                            try {val url=if(address.startsWith("http"))address else "https://$address";DownloadHttp.validateUrl(url);tab.home=false;browser.onResume();browser.loadUrl(url)}catch(_: Exception){error="Endereço inválido"}
                        }))
                    IconButton(onClick={
                        discoveryGeneration++
                        hlsJobs.forEach {it.cancel()};hlsJobs.clear();inspectedHls.clear();discoveringHls=0
                        discoveryRequested=true
                        val generation=discoveryGeneration
                        discoverHls()
                        browser.evaluateJavascript("""
                            (() => {
                              window.__reiScanPlayers && window.__reiScanPlayers(true);
                              const headings=Array.from(document.querySelectorAll('h1,h2,.entry-title,.post-title')).slice(0,30);
                              const episode=/(?:epis[oó]dio|episode|ep)[\s._-]*\d+|(?:dublado|legendado)\s+\d+\s*$/i;
                              const heading=headings.find(h=>h.getClientRects().length && episode.test(h.textContent||''));
                              return heading ? heading.textContent.trim().slice(0,240) : document.title;
                            })()
                        """.trimIndent()) { raw ->
                            val detected=runCatching {JSONArray("[$raw]").optString(0)}.getOrDefault("")
                            if(detected.isNotBlank())title=detected
                            browser.postDelayed({
                                if(generation==discoveryGeneration) {
                                    discoverHls();updateCandidates();showMedia=true
                                }
                            },700)
                            // A same-origin HLS master can finish loading after
                            // the first results. Update the open list without a
                            // second user tap or starting video playback.
                            browser.postDelayed({ if(showMedia && generation==discoveryGeneration) {
                                discoverHls();updateCandidates()
                            } },2600)
                        }
                    }) { Icon(Icons.Filled.Download,"Vídeos encontrados") }
                    Box {
                        IconButton(onClick={menu=true}) {Icon(Icons.Filled.MoreVert,"Menu do navegador")}
                        DropdownMenu(expanded=menu,onDismissRequest={menu=false}) {
                            DropdownMenuItem(text={Text("Nova guia")},onClick={menu=false;captureTab();onNewTab()})
                            DropdownMenuItem(text={Text("Downloads")},onClick={menu=false;startActivity(Intent(this@DownloadBrowserActivity,DownloadsActivity::class.java))})
                            DropdownMenuItem(text={Text("Configurações")},onClick={menu=false;showSettings=true})
                            DropdownMenuItem(text={Text("Recarregar")},onClick={menu=false;error="";browser.reload()})
                        }
                    }
                }
                if(progress<100) LinearProgressIndicator(progress={progress/100f},modifier=Modifier.fillMaxWidth())
                if(error.isNotBlank()) Text(error,Modifier.padding(8.dp),color=MaterialTheme.colorScheme.error)
                if(protectionError.isNotBlank())Text(protectionError,Modifier.padding(16.dp),color=MaterialTheme.colorScheme.error)
                if(!protectionReady) Box(Modifier.weight(1f).fillMaxWidth(),contentAlignment=Alignment.Center) {
                    CircularProgressIndicator()
                } else if(tab.home) Column(Modifier.weight(1f).fillMaxWidth().padding(24.dp),
                    verticalArrangement=Arrangement.Center,horizontalAlignment=Alignment.CenterHorizontally) {
                    Icon(Icons.Filled.Public,null,Modifier.size(64.dp),tint=MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(16.dp))
                    Text("ReiAnix Browser",style=MaterialTheme.typography.headlineMedium)
                    Text("Digite um endereço acima para navegar",style=MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(24.dp))
                    TextButton(onClick={startActivity(Intent(this@DownloadBrowserActivity,DownloadsActivity::class.java))}) {
                        Icon(Icons.Filled.Download,null);Spacer(Modifier.width(8.dp));Text("Downloads")
                    }
                    TextButton(onClick=onNewTab) {Icon(Icons.Filled.Add,null);Text("Nova guia")}
                } else AndroidView(factory={browser},modifier=Modifier.weight(1f).fillMaxWidth())
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceEvenly,verticalAlignment=Alignment.CenterVertically) {
                    IconButton(enabled=backAvailable || tab.home,onClick={
                        if(tab.home){tab.home=false;address=tab.url;browser.onResume()}else browser.goBack()
                    }){Icon(Icons.Filled.ArrowBack,"Voltar página")}
                    IconButton(enabled=forwardAvailable && !tab.home,onClick={browser.goForward()}){Icon(Icons.Filled.ArrowForward,"Avançar página")}
                    IconButton(onClick={captureTab();tab.home=true;address="";browser.onPause()}){Icon(Icons.Filled.Home,"Início do navegador")}
                    OutlinedButton(onClick={captureTab();onTabs()}){Icon(Icons.Filled.Tab,"Guias");Spacer(Modifier.width(6.dp));Text("${tabs.size}")}
                    IconButton(onClick={captureTab();onNewTab()}){Icon(Icons.Filled.Add,"Nova guia")}
                }
            }
        }
        val offer=pendingOffer
        if(offer!=null) key(offer.url,offer.filename) {
            DownloadDialog(
                initialUrl=offer.url,
                initialHeaders=offer.headers,
                initialTitle=offer.title,
                initialName=offer.filename,
                editing=null,
                busy=submitting,
                submissionError=offerError,
                onDismiss={ if(!submitting){pendingOffer=null;offerError=""} },
                onSubmit={url,name,destination,_,_,headers,anime,autoName,detected ->
                    if(!submitting) {
                        submitting=true
                        offerError=""
                        scope.launch {
                            try {
                                withContext(Dispatchers.IO) {
                                    downloadStore.enqueue(
                                        url=url,name=name,destination=destination,
                                        expectedHash="",hashType="SHA-256",headers=headers,
                                        animeTitle=anime,automaticName=autoName,
                                        playerCapture=offer.capture?.evidence()?.takeIf {
                                            it.optString("url")==url
                                        },
                                        hlsHint=offer.hlsHint && url==offer.url,
                                        detectedSize=detected?.bytes,detectedExact=detected?.exact==true,
                                    )
                                    DownloadSnapshots.refresh(applicationContext)
                                }
                                DownloadScheduling.schedule(applicationContext)
                                if(Build.VERSION.SDK_INT>=33)
                                    notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                                pendingOffer=null
                                Toast.makeText(this@DownloadBrowserActivity,
                                    "Download adicionado à fila",Toast.LENGTH_SHORT).show()
                            } catch(e: Exception) {
                                offerError=e.message ?: "Não foi possível iniciar este download"
                            } finally { submitting=false }
                        }
                    }
                },
                hlsHint=offer.hlsHint,
                initialKnownSize=offer.browserLength,
                initialEstimatedSize=offer.providerEstimatedSize,
            )
        }
        if(showMedia) AlertDialog(
            onDismissRequest={showMedia=false},
            title={Text("Fontes e qualidades disponíveis")},
            text={
                if(candidates.isEmpty()) {
                    if(discoveringHls>0) Row(verticalAlignment=Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(20.dp),strokeWidth=2.dp)
                        Spacer(Modifier.width(12.dp))
                        Text("Buscando qualidades disponíveis…")
                    } else Text("Não foi possível identificar as resoluções. Toque novamente no botão de download para tentar outra consulta.")
                } else LazyColumn {
                    items(candidates,key={it.url}) { item ->
                        val supported=true // DASH VOD now uses Media3 MP4 export when supported
                        val formatLabel=when(item.format) {
                            "hls" -> "HLS"
                            "dash" -> "DASH"
                            else -> "Vídeo"
                        }
                        TextButton(
                            enabled=supported,
                            onClick={
                                showMedia=false
                                if(item.capture==null &&
                                    DownloadSourceSelection.clearlyPage(item.url)) {
                                    // Follow the page inside the authorized WebView,
                                    // then rediscover the actual media used by the player.
                                    browser.loadUrl(item.url)
                                    Toast.makeText(this@DownloadBrowserActivity,
                                        "Página intermediária: abra o player para encontrar o arquivo",
                                        Toast.LENGTH_LONG).show()
                                } else {
                                    offer(item.url,browser.settings.userAgentString,null,
                                        when(item.format) {
                                            "hls" -> "application/vnd.apple.mpegurl"
                                            "dash" -> "application/dash+xml"
                                            else -> null
                                        },
                                        title,item.page,item.capture,
                                        providerEstimatedSize=item.estimatedBytes ?: DownloadSizeIntelligence
                                            .fromSharedMetadata(item.quality)?.bytes)
                                }
                            },
                        ) {
                            Column(Modifier.fillMaxWidth()) {
                                val mediaKind=DownloadSourceSelection.kind(item.url)
                                val sourceLabel=when {
                                    item.capture!=null -> "Player confirmado"
                                    mediaKind==DownloadSourceSelection.Kind.DIRECT_FILE -> "Arquivo direto"
                                    mediaKind==DownloadSourceSelection.Kind.PAGE_OR_GATEWAY ->
                                        "Página intermediária"
                                    else -> "Fonte encontrada"
                                }
                                Text("${item.quality} • $formatLabel • $sourceLabel",
                                    maxLines=2)
                                if(!supported) Text(
                                    if(item.separateAudio) "Faixas HLS separadas: MP4 quando o dispositivo suportar"
                                    else "DASH ainda não é compatível com o downloader",
                                    style=MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                }
            },
            confirmButton={TextButton(onClick={showMedia=false}){Text("Fechar")}},
        )
    }
    private fun offer(url: String,agent: String?,disposition: String?,mime: String?,title: String,page: String,
        capture: PlayerMediaCandidate? = null,browserLength:Long?=null,
        providerEstimatedSize:Long?=null) {
        if(!url.startsWith("https://") && !url.startsWith("http://")) return
        CookieManager.getInstance().flush()
        val headers=Bundle().apply {
            CookieManager.getInstance().getCookie(url)?.let {putString("Cookie",it)}
            if(!agent.isNullOrBlank())putString("User-Agent",agent)
            if(page.isNotBlank())putString("Referer",page)
            // Only use headers actually observed for this exact URL, never those of another CDN.
            mediaRequests[url]?.takeIf {android.os.SystemClock.elapsedRealtime()-it.first<120000}?.second?.forEach { (key,value) ->
                val canonical=when(key.lowercase()) {"origin"->"Origin";"referer"->"Referer";"user-agent"->"User-Agent";"authorization"->"Authorization";"cookie"->"Cookie";else->key}
                putString(canonical,value)
            }
            val sameSource=runCatching {
                DownloadHttp.sameOrigin(java.net.URL(tabs.firstOrNull {it.id==activeTabId}?.initialUrl.orEmpty()),java.net.URL(url))
            }.getOrDefault(false)
            if(sameSource)tabs.firstOrNull {it.id==activeTabId}?.headers.orEmpty().forEach {(key,value)->
                // Observed WebView request headers take precedence for their
                // exact resource; Nuvio's headers fill missing values.
                if(!keySet().any{it.equals(key,true)})putString(key,value)
            }
        }
        // Prefer the actual page (top frame) when the video URL belongs to an
        // embedded player or an opaque CDN. Only explicit episode evidence from
        // the page title/URL may supply an automatic anime filename.
        val pageTitle=DownloadIdentity.episodeTitleFromPage(title, web?.url.orEmpty())
            .ifBlank { DownloadIdentity.episodeTitleFromPage(title, page) }
        val filename=if(pageTitle.isNotBlank())
            DownloadIdentity.capturedFileName(pageTitle,url,mime)
        else DownloadResourceIdentity.bestName(disposition,url,mime)
            ?: URLUtil.guessFileName(url,disposition,mime)
        offerError=""
        pendingOffer=BrowserDownloadOffer(
            url=url,headers=headers.keySet().associateWith {headers.getString(it).orEmpty()},
            title=pageTitle.ifBlank { title },filename=filename,capture=capture,
            hlsHint=mime?.contains("mpegurl",ignoreCase=true)==true,
            browserLength=browserLength?.takeIf{
                val extension=url.substringBefore('?').lowercase()
                it>0 && mime?.contains("mpegurl",true)!=true &&
                    mime?.contains("dash+xml",true)!=true &&
                    !listOf(".m3u8",".mpd",".meta4",".metalink",".torrent")
                        .any{suffix -> extension.endsWith(suffix)}
            },
            providerEstimatedSize=providerEstimatedSize?.takeIf {it>0},
        )
    }
    override fun onPause() { web?.onPause(); super.onPause() }
    override fun onResume() { super.onResume(); web?.onResume() }
    companion object {
        fun isVideoLink(url: String): Boolean {
            val path=Uri.parse(url).path.orEmpty().lowercase()
            return listOf(".mp4",".mkv",".webm",".m4v",".mov",".avi",".ts",".m2ts",".flv",".wmv",".3gp",".m3u8",".mpd").any {path.endsWith(it)}
        }
    }
}
