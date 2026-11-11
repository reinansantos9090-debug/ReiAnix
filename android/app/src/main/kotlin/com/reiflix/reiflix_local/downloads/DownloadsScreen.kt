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
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.style.TextOverflow
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
    private var incomingCapture by mutableStateOf<String?>(null)
    private var incomingName by mutableStateOf("")
    private var incomingHeaders by mutableStateOf<Map<String,String>>(emptyMap())
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); accept(intent)
        setContent { ReiAnixComposeTheme {
            Surface(Modifier.fillMaxSize()) {
                Box(Modifier.windowInsetsPadding(WindowInsets.safeDrawing)) {
                    DownloadsScreen(incoming,incomingHeaders,incomingTitle,incomingName,playerCapture=incomingCapture,onBack={ finish() })
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
        incomingCapture=intent.getStringExtra("playerCapture")?.takeIf {it.length<=32768}
        intent.removeExtra("playerCapture")
        val bundle=intent.getBundleExtra("headers") ?: intent.getBundleExtra("com.android.browser.headers")
        incomingHeaders=bundle?.keySet()?.mapNotNull { key -> (bundle.get(key) as? String)?.let { key to it } }?.toMap().orEmpty()
        intent.data=null; intent.removeExtra(Intent.EXTRA_TEXT); intent.removeExtra("headers"); intent.removeExtra("com.android.browser.headers")
    }
}

@Composable
fun DownloadsScreen(initialUrl: String = "", suppliedHeaders: Map<String,String> = emptyMap(),initialTitle: String = "",initialName: String = "",playerCapture: String? = null,onBack: (() -> Unit)? = null) {
    val context=LocalContext.current
    val store=remember(context) { DownloadStore(context) }
    val scope=rememberCoroutineScope()
    val items by DownloadSnapshots.items.collectAsStateWithLifecycle()
    var queueMenu by remember { mutableStateOf(false) }
    var newestFirst by remember { mutableStateOf(false) }
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
            Text("Fila de download",style=MaterialTheme.typography.titleLarge,modifier=Modifier.weight(1f))
            Box {
                IconButton(onClick={queueMenu=true},enabled=initialized && !busy) {Icon(Icons.Filled.Sort,"Opções da fila")}
                DropdownMenu(expanded=queueMenu,onDismissRequest={queueMenu=false}) {
                    DropdownMenuItem(text={Text("Adicionar download")},leadingIcon={Icon(Icons.Filled.Add,null)},onClick={queueMenu=false;add=true})
                    DropdownMenuItem(text={Text(if(newestFirst)"Mais antigos primeiro" else "Mais recentes primeiro")},leadingIcon={Icon(Icons.Filled.SwapVert,null)},onClick={newestFirst=!newestFirst;queueMenu=false})
                }
            }
        }
        if(error.isNotBlank()) Text(error,color=MaterialTheme.colorScheme.error,modifier=Modifier.padding(20.dp))
        if(!initialized && error.isBlank()) LinearProgressIndicator(Modifier.fillMaxWidth())
        if(initialized && items.none {it.state !in setOf("COMPLETE","CANCELLED")}) {
            Column(Modifier.fillMaxWidth().padding(24.dp),horizontalAlignment=Alignment.CenterHorizontally) {
                Icon(Icons.Filled.Download,null,Modifier.size(48.dp))
                Text("Seus downloads aparecem aqui",style=MaterialTheme.typography.titleMedium)
                Text("Cole um link direto ou compartilhe um link de outro aplicativo.")
                TextButton(onClick={ add=true }) { Text("Adicionar download") }
            }
        }
        LazyColumn(Modifier.weight(1f),contentPadding=PaddingValues(horizontal=20.dp,vertical=12.dp)) {
            val pending=items.filter {it.state !in setOf("COMPLETE","CANCELLED")}.let {rows -> if(newestFirst)rows else rows.reversed()}
            items(pending,key={it.id}) { item ->
                var menu by remember(item.id) {mutableStateOf(false)}
                Row(Modifier.fillMaxWidth().heightIn(min=64.dp).clickable {menu=true}.padding(vertical=10.dp),verticalAlignment=Alignment.CenterVertically) {
                    Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(2.dp)) {
                        val parsed=com.reiflix.reiflix_local.data.media.MediaFilenameParser.parseVideoPath(item.name)
                        val episode=parsed.episodeNumber
                        val label=if(episode!=null) "Episódio "+(if(episode%1.0==0.0)episode.toLong().toString() else episode.toString()) else item.name.substringBeforeLast('.',item.name)
                        Text(label,style=MaterialTheme.typography.bodyMedium,maxLines=1,overflow=TextOverflow.Ellipsis)
                        val received=Formatter.formatFileSize(context,item.bytes)
                        val total=if(item.total>0)Formatter.formatFileSize(context,item.total) else if(item.json.optInt("hlsCount")>0) "${item.json.optInt("hlsIndex")} / ${item.json.optInt("hlsCount")} partes • ${downloadPercent(item) ?: 0}%" + if(item.json.optLong("hlsEstimatedTotal")>0) " • total estimado ≈ ${Formatter.formatFileSize(context,item.json.optLong("hlsEstimatedTotal"))}" else "" else "calculando tamanho…"
                        val subtitle=if(item.state=="QUEUED" && item.bytes==0L) item.json.optString("displayAnime").ifBlank {"Na fila"} else if(item.total<=0 && item.json.optInt("hlsCount")>0) "$received • $total" else "$received / $total"
                        Text(subtitle,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant,maxLines=1,overflow=TextOverflow.Ellipsis)
                        if(item.state in setOf("ERROR","CORRUPT","PAUSED","WAITING")) Text(item.json.optString("message").ifBlank {DownloadState.labels[item.state].orEmpty()},style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant,maxLines=2)
                    }
                    Box(Modifier.padding(start=16.dp)) {
                        val percent=downloadPercent(item)
                        if(percent==null && item.state=="DOWNLOADING") CircularProgressIndicator(Modifier.size(28.dp),color=Color.White,strokeWidth=2.dp)
                        else Canvas(Modifier.size(28.dp)) {
                            drawCircle(Color.White,style=Stroke(1.8.dp.toPx()))
                            if(percent!=null && percent>0)drawArc(Color.White,-90f,360f*percent/100f,true)
                        }
                        DropdownMenu(expanded=menu,onDismissRequest={menu=false}) {
                            if(item.state in DownloadState.pending && item.state!="SAVING") DropdownMenuItem(text={Text("Pausar")},leadingIcon={Icon(Icons.Filled.Pause,null)},onClick={menu=false;action(item,"pause")})
                            if(item.state in setOf("PAUSED","ERROR")) DropdownMenuItem(text={Text("Retomar")},leadingIcon={Icon(Icons.Filled.PlayArrow,null)},onClick={menu=false;action(item,"resume")})
                            if(item.state=="CORRUPT") DropdownMenuItem(text={Text("Baixar novamente")},onClick={menu=false;action(item,"restart")})
                            if(item.state!="SAVING") DropdownMenuItem(text={Text("Cancelar")},leadingIcon={Icon(Icons.Filled.Close,null)},onClick={menu=false;action(item,"cancel")})
                            if(!item.json.optBoolean("identityKnown")) DropdownMenuItem(text={Text("Identificar anime")},leadingIcon={Icon(Icons.Filled.Edit,null)},onClick={menu=false;identify=item;identityTitle=""})
                            if(item.state in setOf("ERROR","PAUSED")) DropdownMenuItem(text={Text("Atualizar link")},onClick={menu=false;edit=item;add=true})
                            if(item.mime.startsWith("video/") && item.bytes>128*1024 && item.state !in setOf("ERROR","CORRUPT")) DropdownMenuItem(text={Text("Assistir")},leadingIcon={Icon(Icons.Filled.PlayCircle,null)},onClick={
                                menu=false
                                context.startActivity(Intent(context,NativePlayerActivity::class.java).putExtra("uri",item.virtualUri.toString())
                                    .putExtra("episodeId",item.episodeId.takeIf {it>0}?.toString().orEmpty()).putExtra("animeId",item.animeId.takeIf {it>0}?.toString().orEmpty()).putExtra("title",item.name).putExtra("autoplay",true))
                            })
                        }
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
    if(add) key(initialUrl,initialTitle,initialName,edit?.id) { DownloadDialog(initialUrl,suppliedHeaders,initialTitle,initialName,edit,busy,error,onDismiss={ if(!busy) { add=false; edit=null } },onSubmit={ url,name,dest,hash,type,headers,anime,autoName ->
        busy=true; error=""
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val editing=edit
                    val profile=store.profile(url)
                    val combined=downloadProfileHeaders(profile).toMutableMap()
                    headers.forEach { (key,value) -> combined.keys.filter {it.equals(key,true)}.toList().forEach(combined::remove); combined[key]=value }
                    val expected=""
                    val algorithm="SHA-256"
                    if(editing==null) store.enqueue(url,name,dest,expected,algorithm,combined,anime,autoName,playerCapture?.let {JSONObject(it)}?.takeIf {it.optString("url")==url})
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
    }) }
}

@Composable
private fun DownloadDialog(initialUrl: String,initialHeaders: Map<String,String>,initialTitle: String,initialName: String,editing: DownloadItem?,busy: Boolean,submissionError: String,onDismiss: ()->Unit,
    onSubmit: (String,String,String,String,String,Map<String,String>,String,Boolean)->Unit) {
    val context=LocalContext.current
    var url by remember { mutableStateOf(initialUrl) }
    var name by remember { mutableStateOf(editing?.name ?: initialName.takeIf { it.isNotBlank() } ?: runCatching { URLUtil.guessFileName(initialUrl,null,null) }.getOrDefault("episodio.mp4")) }
    // Names passed by the ReiAnix browser are deliberate; a CDN header must not replace them.
    var nameTouched by remember { mutableStateOf(editing!=null || initialName.isNotBlank()) }
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
            if(error.isNotBlank()) Text(error,color=MaterialTheme.colorScheme.error)
            if(submissionError.isNotBlank()) Text(submissionError,color=MaterialTheme.colorScheme.error)
        } },
        confirmButton={ TextButton(colors=ButtonDefaults.textButtonColors(contentColor=MaterialTheme.colorScheme.primary),modifier=Modifier.heightIn(min=48.dp),enabled=!busy && !sourceLoading,onClick={
            try {
                DownloadHttp.validateUrl(url)
                val headers=originalHeaders.filterKeys { it.lowercase() !in setOf("cookie","referer") }.toMutableMap()
                if(cookie.isNotBlank()) headers["Cookie"]=cookie.trim()
                if(referer.isNotBlank()) headers["Referer"]=referer.trim()
                if(user.isNotBlank()) { headers.keys.filter { it.equals("authorization",true) }.forEach(headers::remove); headers["Authorization"]="Basic "+android.util.Base64.encodeToString("$user:$password".toByteArray(),android.util.Base64.NO_WRAP) }
                onSubmit(url.trim(),name.trim(),destination,hash.trim(),hashType,headers,anime,!nameTouched)
            } catch(_: Exception) { error="Confira o link e os dados informados" }
        }) { Icon(Icons.Filled.Download,null,Modifier.size(18.dp));Spacer(Modifier.width(6.dp));Text(if(busy) "Adicionando…" else if(editing==null) "Baixar" else "Retomar",fontSize=14.sp) } },
        dismissButton={ TextButton(colors=ButtonDefaults.textButtonColors(contentColor=MaterialTheme.colorScheme.primary),modifier=Modifier.heightIn(min=48.dp),onClick=onDismiss,enabled=!busy && !sourceLoading) { Icon(Icons.Filled.Close,null,Modifier.size(18.dp));Spacer(Modifier.width(6.dp));Text("Cancelar",fontSize=14.sp) } })

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
