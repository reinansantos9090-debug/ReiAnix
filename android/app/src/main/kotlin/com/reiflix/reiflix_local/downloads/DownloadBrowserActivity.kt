package com.reiflix.reiflix_local.downloads

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
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

/** Full-screen browser. Only explicit user actions enqueue a transfer. No JS bridge or SSL bypass. */
class DownloadBrowserActivity: ComponentActivity() {
    private var requested by mutableStateOf("")
    private var web: WebView?=null
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
        var candidates by remember { mutableStateOf<List<PlayerMediaCandidate>>(emptyList()) }
        var error by remember { mutableStateOf("") }
        val observed=remember {ConcurrentHashMap<String,PlayerMediaCandidate>()}
        val browser=remember {
            WebView(this).apply {
                web=this
                BrowserMediaCapture.install(this) { frame,item ->
                    if(item==null)observed.remove(frame)
                    else if(observed.size<20 || observed.containsKey(frame))observed[frame]=item
                }
                settings.javaScriptEnabled=true; settings.domStorageEnabled=true
                settings.allowFileAccess=false; settings.allowContentAccess=false
                settings.useWideViewPort=true; settings.loadWithOverviewMode=true
                settings.setSupportMultipleWindows(true)
                settings.mixedContentMode=WebSettings.MIXED_CONTENT_NEVER_ALLOW
                CookieManager.getInstance().setAcceptCookie(true)
                CookieManager.getInstance().setAcceptThirdPartyCookies(this,true)
                webViewClient=object: WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView,request: WebResourceRequest): Boolean {
                        if(request.url.scheme !in setOf("http","https")) { return true }
                        return false
                    }
                    override fun onPageStarted(view: WebView,url: String,favicon: Bitmap?) { address=url; observed.clear();error="" }
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
        BackHandler { if(browser.canGoBack()) browser.goBack() else finish() }
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
                                val now=System.nanoTime()/1_000_000
                                val valid=observed.values.filter {now-it.receivedAt in 0..15000 && PlayerMediaCandidate.eligible(it)}
                                val largest=valid.maxOfOrNull {it.area} ?: 0.0
                                candidates=valid.filter {it.area>=largest*0.75}.distinctBy {it.url}.sortedByDescending {it.area}
                                showMedia=true
                            },2000)
                        }
                    }) { Icon(Icons.Filled.Download,"Vídeos encontrados") }
                    IconButton(onClick={ startActivity(Intent(this@DownloadBrowserActivity,DownloadsActivity::class.java)) }) { Icon(Icons.Filled.List,"Fila de downloads") }
                }
                if(progress<100) LinearProgressIndicator(progress={progress/100f},modifier=Modifier.fillMaxWidth())
                if(error.isNotBlank()) Text(error,Modifier.padding(8.dp),color=MaterialTheme.colorScheme.error)
                AndroidView(factory={browser},modifier=Modifier.weight(1f).fillMaxWidth())
            }
        }
        if(showMedia) AlertDialog(onDismissRequest={showMedia=false},title={Text("Vídeos encontrados")},text={
            if(candidates.isEmpty()) Text("Não foi possível confirmar o vídeo principal. Inicie o episódio e tente novamente, ou use o download do próprio site.")
            else LazyColumn { items(candidates) { item -> TextButton(onClick={showMedia=false; offer(item.url,browser.settings.userAgentString,null,null,title,item.page,item)}) {
                Text("Vídeo principal • ${item.width}×${item.height} • ${(item.duration/60).toInt()} min",maxLines=2)
            } } }
        },confirmButton={TextButton(onClick={showMedia=false}){Text("Fechar")}})
    }
    private fun offer(url: String,agent: String?,disposition: String?,mime: String?,title: String,page: String,capture: PlayerMediaCandidate? = null) {
        if(!url.startsWith("https://") && !url.startsWith("http://")) return
        CookieManager.getInstance().flush()
        val headers=Bundle().apply {
            CookieManager.getInstance().getCookie(url)?.let {putString("Cookie",it)}
            if(!agent.isNullOrBlank())putString("User-Agent",agent)
            if(page.isNotBlank())putString("Referer",page)
        }
        startActivity(Intent(this,DownloadsActivity::class.java).setData(Uri.parse(url))
            .putExtra("headers",headers).putExtra(Intent.EXTRA_TITLE,title).putExtra("playerCapture",capture?.evidence()?.toString())
            .putExtra("downloadName",if(capture!=null) DownloadIdentity.capturedFileName(title,url,mime) else URLUtil.guessFileName(url,disposition,mime)))
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
