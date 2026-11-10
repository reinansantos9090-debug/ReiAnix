package com.reiflix.reiflix_local.downloads

import android.Manifest
import android.content.*
import android.net.Uri
import android.os.*
import android.text.format.Formatter
import android.webkit.*
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.reiflix.reiflix_local.NativePlayerActivity
import com.reiflix.reiflix_local.ReiAnixAppDependencies
import com.reiflix.reiflix_local.ui.theme.ReiAnixComposeTheme
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONObject

object DownloadSnapshots {
    val items=MutableStateFlow<List<DownloadItem>>(emptyList())
    fun refresh(context: Context) { runCatching { items.value=DownloadStore(context).all() } }
}

class DownloadsActivity: ComponentActivity() {
    private var incoming by mutableStateOf("")
    private var incomingHeaders by mutableStateOf<Map<String,String>>(emptyMap())
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); accept(intent)
        setContent { ReiAnixComposeTheme {
            Surface(Modifier.fillMaxSize()) {
                Box(Modifier.windowInsetsPadding(WindowInsets.safeDrawing)) {
                    DownloadsScreen(incoming,incomingHeaders,onBack={ finish() })
                }
            }
        } }
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); accept(intent) }
    private fun accept(intent: Intent) {
        val raw=if(intent.action==Intent.ACTION_SEND) intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty() else intent.dataString.orEmpty()
        incoming=Regex("https?://[^\\s]+",RegexOption.IGNORE_CASE).find(raw)?.value.orEmpty()
        val bundle=intent.getBundleExtra("headers") ?: intent.getBundleExtra("com.android.browser.headers")
        incomingHeaders=bundle?.keySet()?.mapNotNull { key -> (bundle.get(key) as? String)?.let { key to it } }?.toMap().orEmpty()
        intent.data=null; intent.removeExtra(Intent.EXTRA_TEXT); intent.removeExtra("headers"); intent.removeExtra("com.android.browser.headers")
    }
}

@Composable
fun DownloadsScreen(initialUrl: String = "", suppliedHeaders: Map<String,String> = emptyMap(),onBack: (() -> Unit)? = null) {
    val context=LocalContext.current
    val store=remember(context) { DownloadStore(context) }
    val scope=rememberCoroutineScope()
    val items by DownloadSnapshots.items.collectAsStateWithLifecycle()
    var initialized by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var add by remember { mutableStateOf(false) }
    var edit by remember { mutableStateOf<DownloadItem?>(null) }
    var parallel by remember { mutableIntStateOf(2) }
    var busy by remember { mutableStateOf(false) }
    val notificationPermission=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(initialUrl) { if(initialUrl.isNotBlank()) add=true }
    LaunchedEffect(store) {
        try {
            withContext(Dispatchers.IO) { store.ready() }
            parallel=(ReiAnixAppDependencies.from(context).sqliteSettingsRepository.getPreference("downloads.parallel","2")?.toIntOrNull() ?: 2).coerceIn(1,4)
            initialized=true
            while(isActive) { withContext(Dispatchers.IO) { DownloadSnapshots.refresh(context) }; delay(1000) }
        } catch(cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { error="Não foi possível acessar os downloads. Abra o app normalmente e tente novamente." }
    }
    fun action(item: DownloadItem,command: String) {
        scope.launch {
            try {
                withContext(Dispatchers.IO) { if(command=="forget") store.forget(item.id) else store.control(item.id,command); DownloadSnapshots.refresh(context) }
                if(command in setOf("resume","restart")) DownloadScheduling.schedule(context)
            } catch(_: Exception) { error="Não foi possível executar esta ação. Aguarde o salvamento ou tente novamente." }
        }
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal=12.dp,vertical=8.dp),verticalAlignment=Alignment.CenterVertically) {
            if(onBack!=null) IconButton(onClick=onBack) { Icon(Icons.Filled.ArrowBack,"Voltar") }
            Text("Downloads",style=MaterialTheme.typography.titleLarge,modifier=Modifier.weight(1f))
            IconButton(onClick={ add=true },enabled=initialized && !busy) { Icon(Icons.Filled.Add,"Adicionar download") }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal=20.dp),verticalAlignment=Alignment.CenterVertically) {
            Text("Downloads simultâneos",Modifier.weight(1f),style=MaterialTheme.typography.bodyMedium)
            IconButton(enabled=initialized && parallel>1,onClick={ parallel--; scope.launch { try { ReiAnixAppDependencies.from(context).sqliteSettingsRepository.setPreference("downloads.parallel",parallel.toString()) } catch(_: Exception) { error="Não foi possível salvar o limite" } } }) { Icon(Icons.Filled.Remove,"Diminuir limite") }
            Text(parallel.toString())
            IconButton(enabled=initialized && parallel<4,onClick={ parallel++; scope.launch { try { ReiAnixAppDependencies.from(context).sqliteSettingsRepository.setPreference("downloads.parallel",parallel.toString()) } catch(_: Exception) { error="Não foi possível salvar o limite" } } }) { Icon(Icons.Filled.Add,"Aumentar limite") }
        }
        if(error.isNotBlank()) Text(error,color=MaterialTheme.colorScheme.error,modifier=Modifier.padding(20.dp))
        if(!initialized && error.isBlank()) LinearProgressIndicator(Modifier.fillMaxWidth())
        if(initialized && items.isEmpty()) {
            Column(Modifier.fillMaxWidth().padding(24.dp),horizontalAlignment=Alignment.CenterHorizontally) {
                Icon(Icons.Filled.Download,null,Modifier.size(48.dp))
                Text("Seus downloads aparecem aqui",style=MaterialTheme.typography.titleMedium)
                Text("Cole um link direto ou compartilhe um link de outro aplicativo.")
                TextButton(onClick={ add=true }) { Text("Adicionar download") }
            }
        }
        LazyColumn(Modifier.weight(1f),contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            items(items,key={ it.id }) { item ->
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                        Text(item.name,style=MaterialTheme.typography.titleMedium)
                        Text(DownloadState.labels[item.state] ?: item.state,style=MaterialTheme.typography.bodyMedium)
                        if(item.total>0) LinearProgressIndicator(progress={ (item.bytes.toFloat()/item.total).coerceIn(0f,1f) },modifier=Modifier.fillMaxWidth())
                        else if(item.state in DownloadState.pending) LinearProgressIndicator(Modifier.fillMaxWidth())
                        val size=Formatter.formatFileSize(context,item.bytes)
                        val total=if(item.total>=0) Formatter.formatFileSize(context,item.total) else "tamanho desconhecido"
                        val speed=item.json.optLong("speed")
                        Text("$size / $total"+(if(speed>0) " • ${Formatter.formatFileSize(context,speed)}/s • ~${((item.total-item.bytes).coerceAtLeast(0)/speed)/60} min" else ""),style=MaterialTheme.typography.bodySmall)
                        item.json.optString("message").takeIf { it.isNotBlank() }?.let { Text(it,style=MaterialTheme.typography.bodySmall) }
                        if(item.state=="COMPLETE") {
                            Text(item.json.optString("integrity"),style=MaterialTheme.typography.bodySmall)
                            Text("SHA-256: ${item.json.optString("sha256")}",style=MaterialTheme.typography.labelSmall)
                        }
                        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(4.dp)) {
                            if(item.state in DownloadState.pending && item.state!="SAVING") IconButton(onClick={ action(item,"pause") }) { Icon(Icons.Filled.Pause,"Pausar") }
                            if(item.state in setOf("PAUSED","ERROR")) IconButton(onClick={ action(item,"resume") }) { Icon(Icons.Filled.PlayArrow,"Retomar") }
                            if(item.state in setOf("CORRUPT","CANCELLED")) TextButton(onClick={ action(item,"restart") }) { Text("Baixar novamente") }
                            if(item.state !in setOf("COMPLETE","CANCELLED","SAVING")) IconButton(onClick={ action(item,"cancel") }) { Icon(Icons.Filled.Close,"Cancelar") }
                            if(item.state in setOf("ERROR","PAUSED")) IconButton(onClick={ edit=item; add=true }) { Icon(Icons.Filled.Edit,"Atualizar link ou autenticação") }
                            if(item.episodeId>0 && item.bytes>128*1024 && item.state !in setOf("CANCELLED","ERROR","CORRUPT")) TextButton(onClick={
                                val uri=if(item.state=="COMPLETE") Uri.parse(item.output) else item.virtualUri
                                context.startActivity(Intent(context,NativePlayerActivity::class.java).putExtra("uri",uri.toString())
                                    .putExtra("episodeId",item.episodeId.toString()).putExtra("animeId",item.animeId.toString())
                                    .putExtra("title",item.name).putExtra("autoplay",true))
                            }) { Text("Assistir") }
                            if(item.state in setOf("COMPLETE","CANCELLED","CORRUPT") && (item.state=="COMPLETE" || item.output.isBlank())) IconButton(onClick={ action(item,"forget") }) { Icon(Icons.Filled.DeleteOutline,"Remover da lista; manter arquivo concluído") }
                        }
                        if(item.state!="COMPLETE" && item.episodeId>0) Text("Você pode assistir enquanto baixa quando o formato permitir. Avançar para trechos ainda não recebidos pode exigir espera.",style=MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
    if(add) DownloadDialog(initialUrl,suppliedHeaders,edit,busy,error,onDismiss={ if(!busy) { add=false; edit=null } },onSubmit={ url,name,dest,hash,type,headers,anime,autoName ->
        busy=true; error=""
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val editing=edit
                    if(editing==null) store.enqueue(url,name,dest,hash,type,headers,anime,autoName)
                    else store.updateSource(editing.id,url,headers,hash,type,dest)
                    DownloadSnapshots.refresh(context)
                }
                add=false; edit=null
                if(Build.VERSION.SDK_INT>=33) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                DownloadScheduling.schedule(context)
            } catch(e: IllegalArgumentException) { error=e.message ?: "Confira os dados do download" }
            catch(_: Exception) { error="Não foi possível adicionar o download. Confira o link, a pasta e o nome do arquivo." }
            finally { busy=false }
        }
    })
}

@Composable
private fun DownloadDialog(initialUrl: String,initialHeaders: Map<String,String>,editing: DownloadItem?,busy: Boolean,submissionError: String,onDismiss: ()->Unit,
    onSubmit: (String,String,String,String,String,Map<String,String>,String,Boolean)->Unit) {
    val context=LocalContext.current
    var url by remember { mutableStateOf(initialUrl) }
    var name by remember { mutableStateOf(runCatching { URLUtil.guessFileName(initialUrl,null,null) }.getOrDefault("episodio.mp4")) }
    var nameTouched by remember { mutableStateOf(editing!=null) }
    var anime by remember { mutableStateOf("") }
    var destination by remember { mutableStateOf("") }
    var hash by remember { mutableStateOf("") }
    var hashType by remember { mutableStateOf("SHA-256") }
    var cookie by remember { mutableStateOf(initialHeaders.entries.firstOrNull { it.key.equals("cookie",true) }?.value.orEmpty()) }
    var user by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var referer by remember { mutableStateOf(initialHeaders.entries.firstOrNull { it.key.equals("referer",true) }?.value.orEmpty()) }
    var login by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var originalHeaders by remember { mutableStateOf(initialHeaders) }
    LaunchedEffect(editing?.id) {
        if(editing!=null) try {
            val secret=withContext(Dispatchers.IO) { DownloadStore(context).secret(editing) }
            url=secret.getString("url"); name=editing.name; destination=editing.json.optString("destination")
            hash=editing.json.optString("expectedHash"); hashType=editing.json.optString("hashType","SHA-256")
            val h=secret.optJSONObject("headers") ?: JSONObject(); originalHeaders=h.keys().asSequence().associateWith { h.getString(it) }
            cookie=originalHeaders.entries.firstOrNull { it.key.equals("cookie",true) }?.value.orEmpty()
            referer=originalHeaders.entries.firstOrNull { it.key.equals("referer",true) }?.value.orEmpty()
        } catch(_: Exception) { error="Não foi possível recuperar a sessão. Informe novamente o link e as credenciais." }
    }
    val folder=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if(uri!=null) try {
            context.contentResolver.takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            destination=uri.toString()
        } catch(_: Exception) { error="A pasta não concedeu acesso de escrita persistente" }
    }
    AlertDialog(onDismissRequest={ if(!busy) onDismiss() },title={ Text(if(editing==null) "Adicionar download" else "Atualizar link / sessão") },
        text={ Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(url,{ url=it; if(!nameTouched && editing==null) name=runCatching { URLUtil.guessFileName(it.trim(),null,null) }.getOrDefault("episodio.mp4") },label={ Text("Link direto HTTP/HTTPS") },singleLine=true,enabled=!busy)
            OutlinedTextField(name,{ name=it; nameTouched=true },label={ Text("Nome do arquivo, incluindo extensão") },singleLine=true,enabled=!busy && editing==null)
            if(editing==null) {
                OutlinedTextField(anime,{ anime=it },label={ Text("Nome do anime (opcional)") },singleLine=true,enabled=!busy)
            }
                Text(if(destination.isBlank()) "Destino: Downloads/ReiAnix" else "Destino: $destination",style=MaterialTheme.typography.bodySmall)
                Row { TextButton(onClick={ folder.launch(null) },enabled=!busy) { Text("Escolher pasta") }; TextButton(onClick={ destination="" },enabled=!busy) { Text("Padrão") } }
            Text("Integridade",style=MaterialTheme.typography.titleSmall)
            Row { listOf("SHA-256","SHA-1","MD5").forEach { kind -> TextButton(onClick={ hashType=kind },enabled=!busy) { Text(if(hashType==kind) "✓ $kind" else kind) } } }
            OutlinedTextField(hash,{ hash=it },label={ Text("Hash de referência (opcional)") },enabled=!busy)
            Text("Sem referência fornecida pelo usuário ou servidor, calculamos o SHA-256, mas não podemos confirmar que o original está correto.",style=MaterialTheme.typography.bodySmall)
            Text("Autenticação (opcional)",style=MaterialTheme.typography.titleSmall)
            OutlinedTextField(user,{ user=it },label={ Text("Usuário (HTTP Basic)") },singleLine=true,enabled=!busy)
            OutlinedTextField(password,{ password=it },label={ Text("Senha") },singleLine=true,enabled=!busy,visualTransformation=PasswordVisualTransformation())
            OutlinedTextField(cookie,{ cookie=it },label={ Text("Cookie da sessão autorizada") },enabled=!busy,visualTransformation=PasswordVisualTransformation())
            OutlinedTextField(referer,{ referer=it },label={ Text("Referer, se exigido pelo servidor") },singleLine=true,enabled=!busy)
            TextButton(onClick={ originalHeaders=originalHeaders.filterKeys { it.lowercase() !in setOf("authorization","cookie","referer") }; cookie=""; user=""; password=""; referer="" },enabled=!busy) { Text("Limpar autenticação") }
            TextButton(onClick={ try { DownloadHttp.validateUrl(url); login=true } catch(_: Exception) { error="Informe um link válido primeiro" } },enabled=!busy) { Text("Entrar no site para obter a sessão") }
            if(error.isNotBlank()) Text(error,color=MaterialTheme.colorScheme.error)
            if(submissionError.isNotBlank()) Text(submissionError,color=MaterialTheme.colorScheme.error)
        } },
        confirmButton={ TextButton(enabled=!busy,onClick={
            try {
                DownloadHttp.validateUrl(url)
                val headers=originalHeaders.filterKeys { it.lowercase() !in setOf("cookie","referer") }.toMutableMap()
                if(cookie.isNotBlank()) headers["Cookie"]=cookie.trim()
                if(referer.isNotBlank()) headers["Referer"]=referer.trim()
                if(user.isNotBlank()) { headers.keys.filter { it.equals("authorization",true) }.forEach(headers::remove); headers["Authorization"]="Basic "+android.util.Base64.encodeToString("$user:$password".toByteArray(),android.util.Base64.NO_WRAP) }
                onSubmit(url.trim(),name.trim(),destination,hash.trim(),hashType,headers,anime,!nameTouched)
            } catch(_: Exception) { error="Confira o link e os dados informados" }
        }) { Text(if(busy) "Adicionando…" else if(editing==null) "Baixar" else "Retomar") } },
        dismissButton={ TextButton(onClick=onDismiss,enabled=!busy) { Text("Cancelar") } })
    if(login) WebSessionDialog(url,onDismiss={ login=false },onCookie={ cookie=it; login=false })
}

@Composable
private fun WebSessionDialog(url: String,onDismiss: ()->Unit,onCookie: (String)->Unit) {
    val context=LocalContext.current
    val origin=remember(url) { val uri=Uri.parse(url); "${uri.scheme}://${uri.encodedAuthority}/" }
    val web=remember { WebView(context).apply {
        settings.javaScriptEnabled=true; settings.allowFileAccess=false; settings.allowContentAccess=false
        settings.mixedContentMode=WebSettings.MIXED_CONTENT_NEVER_ALLOW
        webViewClient=object: WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView,request: WebResourceRequest): Boolean = request.url.scheme !in setOf("https","http")
        }
        loadUrl(origin)
    } }
    DisposableEffect(web) { onDispose { web.stopLoading(); web.destroy() } }
    Dialog(onDismissRequest=onDismiss) { Surface {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.9f)) {
            Text("Entre no site. Apenas os cookies deste endereço serão usados no download.",Modifier.padding(12.dp),style=MaterialTheme.typography.bodySmall)
            AndroidView(factory={ web },modifier=Modifier.weight(1f).fillMaxWidth())
            Row { TextButton(onClick=onDismiss) { Text("Cancelar") }; TextButton(onClick={ CookieManager.getInstance().flush(); onCookie(CookieManager.getInstance().getCookie(url).orEmpty()) }) { Text("Usar sessão") } }
        }
    } }
}

@Composable
fun DownloadLibraryStatus(animeId: Long = 0,episodeId: Long = 0) {
    val label=downloadLibraryLabel(animeId,episodeId)
    label?.let { Text(it,style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.primary,modifier=Modifier.padding(horizontal=12.dp,vertical=2.dp)) }
}

@Composable
fun downloadLibraryLabel(animeId: Long = 0,episodeId: Long = 0): String? {
    val flow=remember(animeId,episodeId) { DownloadSnapshots.items.map { rows ->
        rows.firstOrNull { (if(episodeId>0) it.episodeId==episodeId else it.animeId==animeId) && it.state!="COMPLETE" && it.state!="CANCELLED" }
            ?.let { (DownloadState.labels[it.state] ?: it.state)+(if(it.total>0) " • ${(it.bytes*100/it.total).coerceIn(0,100)}%" else "") }
    }.distinctUntilChanged() }
    val label by flow.collectAsStateWithLifecycle(initialValue=null)
    return label
}
