package com.reiflix.reiflix_local.downloads

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.Manifest
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import com.reiflix.reiflix_local.ui.theme.ReiAnixComposeTheme
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
)

/** Full-screen browser. Only explicit user actions enqueue a transfer. No JS bridge or SSL bypass. */
class DownloadBrowserActivity: ComponentActivity() {
    private var requested by mutableStateOf("")
    private var pendingOffer by mutableStateOf<BrowserDownloadOffer?>(null)
    private var offerError by mutableStateOf("")
    private var web: WebView?=null
    private val mediaRequests=ConcurrentHashMap<String,Pair<Long,Map<String,String>>>()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); accept(intent)
        setContent { ReiAnixComposeTheme { Browser() } }
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); accept(intent) }
    private fun accept(intent: Intent) {
        val raw=if(intent.action==Intent.ACTION_SEND) intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty() else intent.dataString.orEmpty()
        requested=Regex("https?://[^\\s]+",RegexOption.IGNORE_CASE).find(raw)?.value.orEmpty()
        intent.data=null; intent.removeExtra(Intent.EXTRA_TEXT)
    }
    @Composable private fun Browser() {
        var address by remember { mutableStateOf(requested) }
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
            candidates=BrowserMediaResults.combine(main,visible)
        }
        val browser=remember {
            WebView(this).apply {
                web=this
                BrowserMediaCapture.install(
                    this,
                    onMedia = { frame,item ->
                        if(item==null)observed.remove(frame)
                        else if(observed.size<20 || observed.containsKey(frame))observed[frame]=item
                    },
                    onOption = { item ->
                        val key=item.frame+"::"+item.url
                        if(sourceOptions.size<96 || sourceOptions.containsKey(key))sourceOptions[key]=item
                        if(showMedia)updateCandidates()
                    },
                )
                settings.javaScriptEnabled=true; settings.domStorageEnabled=true
                settings.allowFileAccess=false; settings.allowContentAccess=false
                settings.useWideViewPort=true; settings.loadWithOverviewMode=true
                settings.setSupportMultipleWindows(true)
                settings.mixedContentMode=WebSettings.MIXED_CONTENT_NEVER_ALLOW
                CookieManager.getInstance().setAcceptCookie(true)
                CookieManager.getInstance().setAcceptThirdPartyCookies(this,true)
                webViewClient=object: WebViewClient() {
                    override fun shouldInterceptRequest(view: WebView,request: WebResourceRequest): WebResourceResponse? {
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
                        return false
                    }
                    override fun onPageStarted(view: WebView,url: String,favicon: Bitmap?) { address=url; observed.clear();sourceOptions.clear();mediaRequests.clear();error="" }
                    override fun onPageFinished(view: WebView,url: String) { address=url; title=view.title.orEmpty() }

                }
                webChromeClient=object: WebChromeClient() {
                    override fun onProgressChanged(view: WebView,n: Int) { progress=n }
                    // Open a requested new window within this browser instead of silently dropping download links.
                    override fun onCreateWindow(view: WebView,isDialog: Boolean,isUserGesture: Boolean,resultMsg: android.os.Message): Boolean {
                        if(!isUserGesture) return false
                        val popup=WebView(this@DownloadBrowserActivity)
                        popup.webViewClient=object: WebViewClient() {
                            override fun shouldOverrideUrlLoading(v: WebView,r: WebResourceRequest): Boolean {
                                if(r.url.scheme in setOf("http","https")) view.loadUrl(r.url.toString())
                                popup.destroy(); return true
                            }
                        }
                        popup.setDownloadListener { url,ua,cd,mime,_ -> offer(url,ua,cd,mime,title,view.url.orEmpty()); popup.destroy() }
                        (resultMsg.obj as WebView.WebViewTransport).webView=popup; resultMsg.sendToTarget(); return true
                    }
                }
                setDownloadListener { url,ua,cd,mime,_ -> offer(url,ua,cd,mime,title,this.url.orEmpty()) }
            }
        }
        LaunchedEffect(requested) { if(requested.isNotBlank()) try { DownloadHttp.validateUrl(requested); browser.loadUrl(requested) } catch(_: Exception) { error="Endereço inválido" } }
        DisposableEffect(browser) { onDispose { browser.stopLoading(); browser.destroy(); web=null } }
        BackHandler {
            when {
                pendingOffer!=null -> { if(!submitting)pendingOffer=null }
                showMedia -> showMedia=false
                browser.canGoBack() -> browser.goBack()
                else -> finish()
            }
        }
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                    OutlinedTextField(address,{address=it},singleLine=true,modifier=Modifier.weight(1f),label={Text("Endereço")},
                        keyboardOptions=KeyboardOptions(imeAction=ImeAction.Go),keyboardActions=KeyboardActions(onGo={
                            try {val url=if(address.startsWith("http"))address else "https://$address";DownloadHttp.validateUrl(url);browser.loadUrl(url)}catch(_: Exception){error="Endereço inválido"}
                        }))
                    IconButton(onClick={
                        browser.evaluateJavascript("window.__reiScanPlayers && window.__reiScanPlayers(true)") {
                            browser.postDelayed({
                                updateCandidates()
                                showMedia=true
                            },700)
                            // A same-origin HLS master can finish loading after
                            // the first results. Update the open list without a
                            // second user tap or starting video playback.
                            browser.postDelayed({ if(showMedia)updateCandidates() },2600)
                        }
                    }) { Icon(Icons.Filled.Download,"Vídeos encontrados") }
                    IconButton(onClick={ startActivity(Intent(this@DownloadBrowserActivity,DownloadsActivity::class.java)) }) { Icon(Icons.Filled.List,"Fila de downloads") }
                }
                if(progress<100) LinearProgressIndicator(progress={progress/100f},modifier=Modifier.fillMaxWidth())
                if(error.isNotBlank()) Text(error,Modifier.padding(8.dp),color=MaterialTheme.colorScheme.error)
                AndroidView(factory={browser},modifier=Modifier.weight(1f).fillMaxWidth())
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
            )
        }
        if(showMedia) AlertDialog(
            onDismissRequest={showMedia=false},
            title={Text("Fontes e qualidades disponíveis")},
            text={
                if(candidates.isEmpty()) {
                    Text("Nenhum link de vídeo foi encontrado na página atual. Algumas fontes só são disponibilizadas após abrir o player ou iniciar a reprodução.")
                } else LazyColumn {
                    items(candidates,key={it.url}) { item ->
                        val supported=item.format!="dash" && !item.separateAudio
                        val formatLabel=when(item.format) {
                            "hls" -> "HLS"
                            "dash" -> "DASH"
                            else -> "Vídeo"
                        }
                        TextButton(
                            enabled=supported,
                            onClick={
                                showMedia=false
                                offer(item.url,browser.settings.userAgentString,null,
                                    if(item.format=="hls")"application/vnd.apple.mpegurl" else null,
                                    title,item.page,item.capture)
                            },
                        ) {
                            Column(Modifier.fillMaxWidth()) {
                                Text("${item.quality} • $formatLabel" +
                                    if(item.capture!=null) " • Player confirmado" else " • Fonte encontrada",
                                    maxLines=2)
                                if(!supported) Text(
                                    if(item.separateAudio) "Faixas de áudio separadas ainda não suportadas"
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
    private fun offer(url: String,agent: String?,disposition: String?,mime: String?,title: String,page: String,capture: PlayerMediaCandidate? = null) {
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
        }
        val filename=if(title.isNotBlank() && !title.equals("Animes Online",ignoreCase=true))
            DownloadIdentity.capturedFileName(title,url,mime)
        else URLUtil.guessFileName(url,disposition,mime)
        offerError=""
        pendingOffer=BrowserDownloadOffer(
            url=url,headers=headers.keySet().associateWith {headers.getString(it).orEmpty()},
            title=title,filename=filename,capture=capture,
            hlsHint=mime?.contains("mpegurl",ignoreCase=true)==true,
        )
    }
    override fun onPause() { web?.onPause(); super.onPause() }
    override fun onResume() { super.onResume(); web?.onResume() }
    companion object {
        fun isVideoLink(url: String): Boolean {
            val path=Uri.parse(url).path.orEmpty().lowercase()
            return listOf(".mp4",".mkv",".webm",".m4v",".mov",".avi",".ts",".m2ts",".flv",".wmv",".m3u8",".mpd").any {path.endsWith(it)}
        }
    }
}
