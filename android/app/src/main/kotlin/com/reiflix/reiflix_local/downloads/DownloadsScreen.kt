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
    fun refresh(context: Context) { runCatching { val store=DownloadStore(context); items.value=store.projectLinks(store.all()).map { item ->
        val art=store.artwork(item); if(art==null)item else DownloadItem(JSONObject(item.json.toString()).put("displayAnime",art.first).put("coverCache",art.second))
    } } }
}

class DownloadsActivity: ComponentActivity() {
    private var incoming by mutableStateOf("")
    private var incomingTitle by mutableStateOf("")
    private var incomingName by mutableStateOf("")
    private var incomingHeaders by mutableStateOf<Map<String,String>>(emptyMap())
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); accept(intent)
        setContent { ReiAnixComposeTheme {
            Surface(Modifier.fillMaxSize()) {
                Box(Modifier.windowInsetsPadding(WindowInsets.safeDrawing)) {
                    DownloadsScreen(incoming,incomingHeaders,incomingTitle,incomingName,onBack={ finish() })
                }
            }
        } }
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); accept(intent) }
    private fun accept(intent: Intent) {
        val raw=if(intent.action==Intent.ACTION_SEND) intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty() else intent.dataString.orEmpty()
        incoming=Regex("https?://[^\\s]+",RegexOption.IGNORE_CASE).find(raw)?.value.orEmpty()
        incomingTitle=sequenceOf(Intent.EXTRA_TITLE,Intent.EXTRA_SUBJECT,"title","name").mapNotNull { intent.getStringExtra(it) }.firstOrNull {it.isNotBlank()}.orEmpty()
        incomingName=intent.getStringExtra("downloadName").orEmpty()
        val bundle=intent.getBundleExtra("headers") ?: intent.getBundleExtra("com.android.browser.headers")
        incomingHeaders=bundle?.keySet()?.mapNotNull { key -> (bundle.get(key) as? String)?.let { key to it } }?.toMap().orEmpty()
        intent.data=null; intent.removeExtra(Intent.EXTRA_TEXT); intent.removeExtra("headers"); intent.removeExtra("com.android.browser.headers")
    }
}

@Composable
fun DownloadsScreen(initialUrl: String = "", suppliedHeaders: Map<String,String> = emptyMap(),initialTitle: String = "",initialName: String = "",onBack: (() -> Unit)? = null) {
    val context=LocalContext.current
    val store=remember(context) { DownloadStore(context) }
    val scope=rememberCoroutineScope()
    val items by DownloadSnapshots.items.collectAsStateWithLifecycle()
    var initialized by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var add by remember { mutableStateOf(false) }
    var identify by remember { mutableStateOf<DownloadItem?>(null) }
    var identityTitle by remember { mutableStateOf("") }
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
                withContext(Dispatchers.IO) { if(command=="forget") store.forget(item.id).also {DownloadScheduling.clear(context,item.id)} else store.control(item.id,command); DownloadSnapshots.refresh(context) }
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
                        if(downloadPercent(item)!=null) LinearProgressIndicator(progress={ downloadPercent(item)!!/100f },modifier=Modifier.fillMaxWidth())
                        else if(item.state in DownloadState.pending) LinearProgressIndicator(Modifier.fillMaxWidth())
                        val size=Formatter.formatFileSize(context,item.bytes)
                        val total=if(item.total>=0) Formatter.formatFileSize(context,item.total) else "tamanho desconhecido"
                        val speed=item.json.optLong("speed")
                        Text("$size / $total"+(if(speed>0) " • ${Formatter.formatFileSize(context,speed)}/s"+(if(item.total>0) " • ~${((item.total-item.bytes).coerceAtLeast(0)/speed)/60} min" else "") else ""),style=MaterialTheme.typography.bodySmall)
                        item.json.optString("message").takeIf { it.isNotBlank() }?.let { Text(it,style=MaterialTheme.typography.bodySmall) }
                        if(item.state=="COMPLETE") {
                            Text(item.json.optString("integrity"),style=MaterialTheme.typography.bodySmall)
                            Text("SHA-256: ${item.json.optString("sha256")}",style=MaterialTheme.typography.labelSmall)
                        }
                        if(item.mime.startsWith("video/") && !item.json.optBoolean("identityKnown") && item.state!="CANCELLED") TextButton(onClick={identify=item;identityTitle=""}) {Text("Identificar anime")}
                        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(4.dp)) {
                            if(item.state in DownloadState.pending && item.state!="SAVING") IconButton(onClick={ action(item,"pause") }) { Icon(Icons.Filled.Pause,"Pausar") }
                            if(item.state in setOf("PAUSED","ERROR")) IconButton(onClick={ action(item,"resume") }) { Icon(Icons.Filled.PlayArrow,"Retomar") }
                            if(item.state=="CANCELLED" && item.output.isNotBlank()) IconButton(onClick={ action(item,"cancel") }) { Icon(Icons.Filled.DeleteOutline,"Limpar arquivo temporário") }
                            if(item.state in setOf("CORRUPT","CANCELLED")) TextButton(onClick={ action(item,"restart") }) { Text("Baixar novamente") }
                            if(item.state !in setOf("COMPLETE","CANCELLED","SAVING")) IconButton(onClick={ action(item,"cancel") }) { Icon(Icons.Filled.Close,"Cancelar") }
                            if(item.state in setOf("ERROR","PAUSED")) IconButton(onClick={ edit=item; add=true }) { Icon(Icons.Filled.Edit,"Atualizar link ou autenticação") }
                            if(item.mime.startsWith("video/") && item.bytes>128*1024 && item.state !in setOf("CANCELLED","ERROR","CORRUPT")) TextButton(onClick={
                                val uri=if(item.state=="COMPLETE") Uri.parse(item.output) else item.virtualUri
                                context.startActivity(Intent(context,NativePlayerActivity::class.java).putExtra("uri",uri.toString())
                                    .putExtra("episodeId",item.episodeId.takeIf { it>0 }?.toString().orEmpty()).putExtra("animeId",item.animeId.takeIf { it>0 }?.toString().orEmpty())
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
    if(identify!=null) AlertDialog(onDismissRequest={identify=null},title={Text("Nome do anime")},text={
        OutlinedTextField(identityTitle,{identityTitle=it},label={Text("Nome do anime")},singleLine=true)
    },confirmButton={TextButton(enabled=identityTitle.isNotBlank(),onClick={val item=identify!!;scope.launch {
        try {withContext(Dispatchers.IO){store.relink(item.id,identityTitle.trim());DownloadSnapshots.refresh(context)};identify=null}
        catch(_: Exception){error="Não foi possível identificar o anime"}
    }}){Text("Salvar")}},dismissButton={TextButton(onClick={identify=null}){Text("Cancelar")}})
    if(add) DownloadDialog(initialUrl,suppliedHeaders,initialTitle,initialName,edit,busy,error,onDismiss={ if(!busy) { add=false; edit=null } },onSubmit={ url,name,dest,hash,type,headers,anime,autoName ->
        busy=true; error=""
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val editing=edit
                    val profile=store.profile(url)
                    val combined=downloadProfileHeaders(profile).toMutableMap()
                    headers.forEach { (key,value) -> combined.keys.filter {it.equals(key,true)}.toList().forEach(combined::remove); combined[key]=value }
                    val expected=profile.optString("hash").ifBlank {hash}
                    val algorithm=if(profile.optString("hash").isNotBlank())profile.optString("hashType","SHA-256") else type
                    if(editing==null) store.enqueue(url,name,dest,expected,algorithm,combined,anime,autoName)
                    else {store.updateSource(editing.id,url,combined,expected,algorithm,dest); if(anime.isNotBlank())store.relink(editing.id,anime)}
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
private fun DownloadDialog(initialUrl: String,initialHeaders: Map<String,String>,initialTitle: String,initialName: String,editing: DownloadItem?,busy: Boolean,submissionError: String,onDismiss: ()->Unit,
    onSubmit: (String,String,String,String,String,Map<String,String>,String,Boolean)->Unit) {
    val context=LocalContext.current
    var url by remember { mutableStateOf(initialUrl) }
    var name by remember { mutableStateOf(editing?.name ?: initialName.takeIf { it.isNotBlank() } ?: runCatching { URLUtil.guessFileName(initialUrl,null,null) }.getOrDefault("episodio.mp4")) }
    var nameTouched by remember { mutableStateOf(editing!=null) }
    var anime by remember { mutableStateOf(editing?.json?.optString("animeTitle").orEmpty().ifBlank {DownloadIdentity.title(initialTitle)}) }
    var destination by remember { mutableStateOf(editing?.json?.optString("destination").orEmpty()) }
    var hash by remember { mutableStateOf(editing?.json?.optString("expectedHash").orEmpty()) }
    var hashType by remember { mutableStateOf(editing?.json?.optString("hashType","SHA-256") ?: "SHA-256") }
    var cookie by remember { mutableStateOf(initialHeaders.entries.firstOrNull { it.key.equals("cookie",true) }?.value.orEmpty()) }
    var user by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var referer by remember { mutableStateOf(initialHeaders.entries.firstOrNull { it.key.equals("referer",true) }?.value.orEmpty()) }
    var login by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var originalHeaders by remember { mutableStateOf(initialHeaders) }
    var sourceLoading by remember { mutableStateOf(editing!=null) }
    var sessionOrigin by remember { mutableStateOf(runCatching { java.net.URL(initialUrl) }.getOrNull()) }
    LaunchedEffect(editing?.id) {
        if(editing!=null) try {
            val secret=withContext(Dispatchers.IO) { DownloadStore(context).secret(editing) }
            url=secret.getString("url"); name=editing.name; destination=editing.json.optString("destination"); sessionOrigin=runCatching { java.net.URL(url) }.getOrNull()
            hash=editing.json.optString("expectedHash"); hashType=editing.json.optString("hashType","SHA-256")
            val h=secret.optJSONObject("headers") ?: JSONObject(); originalHeaders=h.keys().asSequence().associateWith { h.getString(it) }
            cookie=originalHeaders.entries.firstOrNull { it.key.equals("cookie",true) }?.value.orEmpty()
            referer=originalHeaders.entries.firstOrNull { it.key.equals("referer",true) }?.value.orEmpty()
        } catch(_: Exception) { originalHeaders=emptyMap(); cookie=""; user=""; password=""; error="Não foi possível recuperar a sessão. Informe novamente o link e as credenciais." }
        finally { sourceLoading=false }
    }
    val folder=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if(uri!=null) try {
            context.contentResolver.takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            destination=uri.toString()
        } catch(_: Exception) { error="A pasta não concedeu acesso de escrita persistente" }
    }
    AlertDialog(onDismissRequest={ if(!busy) onDismiss() },title={ Text(if(editing==null) "Adicionar download" else "Atualizar link / sessão") },
        text={ Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(url,{ next ->
                val nextOrigin=runCatching { java.net.URL(DownloadHttp.validateUrl(next).toASCIIString()) }.getOrNull()
                if(nextOrigin!=null) {
                    if(sessionOrigin?.let { !DownloadHttp.sameOrigin(it,nextOrigin) }==true) {
                        originalHeaders=originalHeaders.filterKeys { it.lowercase() !in setOf("authorization","cookie","referer") }
                        cookie=""; user=""; password=""; referer=""
                    }
                    sessionOrigin=nextOrigin
                }
                url=next; if(!nameTouched && editing==null) name=runCatching { URLUtil.guessFileName(next.trim(),null,null) }.getOrDefault("episodio.mp4") },label={ Text("Link direto HTTP/HTTPS") },singleLine=true,enabled=!busy && !sourceLoading)
            OutlinedTextField(name,{ name=it; nameTouched=true },label={ Text("Nome do arquivo, incluindo extensão") },singleLine=true,enabled=!busy && !sourceLoading && editing==null)
            run {
                OutlinedTextField(anime,{ anime=it },label={ Text("Nome do anime (opcional)") },singleLine=true,enabled=!busy && !sourceLoading)
            }
                Text(if(destination.isBlank()) "Destino: Downloads/ReiAnix" else "Destino: $destination",style=MaterialTheme.typography.bodySmall)
                Row { TextButton(onClick={ folder.launch(null) },enabled=!busy && !sourceLoading) { Text("Escolher pasta") }; TextButton(onClick={ destination="" },enabled=!busy && !sourceLoading) { Text("Padrão") } }
            TextButton(onClick={ try { DownloadHttp.validateUrl(url); context.startActivity(Intent(context,DownloadBrowserActivity::class.java).setData(Uri.parse(url.trim()))) } catch(_: Exception) { error="Informe um link válido primeiro" } },enabled=!busy && !sourceLoading) { Text("Entrar no site") }
            if(error.isNotBlank()) Text(error,color=MaterialTheme.colorScheme.error)
            if(submissionError.isNotBlank()) Text(submissionError,color=MaterialTheme.colorScheme.error)
        } },
        confirmButton={ TextButton(enabled=!busy && !sourceLoading,onClick={
            try {
                DownloadHttp.validateUrl(url)
                val headers=originalHeaders.filterKeys { it.lowercase() !in setOf("cookie","referer") }.toMutableMap()
                if(cookie.isNotBlank()) headers["Cookie"]=cookie.trim()
                if(referer.isNotBlank()) headers["Referer"]=referer.trim()
                if(user.isNotBlank()) { headers.keys.filter { it.equals("authorization",true) }.forEach(headers::remove); headers["Authorization"]="Basic "+android.util.Base64.encodeToString("$user:$password".toByteArray(),android.util.Base64.NO_WRAP) }
                onSubmit(url.trim(),name.trim(),destination,hash.trim(),hashType,headers,anime,!nameTouched)
            } catch(_: Exception) { error="Confira o link e os dados informados" }
        }) { Text(if(busy) "Adicionando…" else if(editing==null) "Baixar" else "Retomar") } },
        dismissButton={ TextButton(onClick=onDismiss,enabled=!busy && !sourceLoading) { Text("Cancelar") } })

}

@Composable
fun DownloadLibraryStatus(animeId: Long = 0,episodeId: Long = 0) {
    val label=downloadLibraryLabel(animeId,episodeId)
    label?.let { Text(it,style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.primary,modifier=Modifier.padding(horizontal=12.dp,vertical=2.dp)) }
}

@Composable
fun downloadLibraryLabel(animeId: Long = 0,episodeId: Long = 0): String? {
    val flow=remember(animeId,episodeId) { DownloadSnapshots.items.map { rows ->
        rows.firstOrNull { (if(episodeId>0) it.episodeId==episodeId else animeId>0 && it.animeId==animeId) && it.state!="COMPLETE" && it.state!="CANCELLED" }
            ?.let { (DownloadState.labels[it.state] ?: it.state)+(downloadPercent(it)?.let {percent -> " • $percent%"} ?: "") }
    }.distinctUntilChanged() }
    val label by flow.collectAsStateWithLifecycle(initialValue=null)
    return label
}
