package com.reiflix.reiflix_local.downloads

import androidx.compose.foundation.layout.*
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
import com.reiflix.reiflix_local.ReiAnixAppDependencies
import kotlinx.coroutines.*
import org.json.JSONObject

@Composable
fun DownloadSettingsScreen(onBack: ()->Unit) {
    val context=LocalContext.current
    val scope=rememberCoroutineScope()
    val store=remember { DownloadStore(context) }
    val prefs=remember { ReiAnixAppDependencies.from(context).sqliteSettingsRepository }
    var parallel by remember { mutableIntStateOf(2) }
    var buffer by remember { mutableIntStateOf(128) }
    var connections by remember { mutableIntStateOf(1) }
    var wifi by remember { mutableStateOf(false) }
    var url by remember { mutableStateOf("") }
    var hash by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf("SHA-256") }
    var user by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var cookie by remember { mutableStateOf("") }
    var referer by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        try { withContext(Dispatchers.IO) {
            store.ready(); parallel=prefs.getPreference("downloads.parallel","2")?.toIntOrNull() ?: 2
            buffer=prefs.getPreference("downloads.buffer_kb","128")?.toIntOrNull() ?: 128
            connections=prefs.getPreference("downloads.connections","1")?.toIntOrNull() ?: 1
            wifi=prefs.getPreference("downloads.wifi_only","false")=="true"
        } } catch(_: Exception) { message="Não foi possível carregar as configurações" } finally { busy=false }
    }
    fun save(key: String,value: String) { scope.launch { try { withContext(Dispatchers.IO) { prefs.setPreference(key,value); if(key=="downloads.wifi_only")DownloadScheduling.reconfigure(context) } } catch(_: Exception) { message="Não foi possível salvar" } } }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
            IconButton(onClick=onBack) { Icon(Icons.Filled.ArrowBack,"Voltar") }
            Text("Downloads",style=MaterialTheme.typography.titleLarge)
        }
        Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            DownloadSection("Fila e rede",Icons.Filled.Download)
            Text("Downloads simultâneos")
            Row { (1..4).forEach { n -> FilterChip(selected=parallel==n,onClick={ parallel=n; save("downloads.parallel",n.toString()) },label={ Text("$n") },enabled=!busy) } }
            Row(verticalAlignment=Alignment.CenterVertically) { Text("Somente Wi-Fi",Modifier.weight(1f)); Switch(wifi,{ wifi=it; save("downloads.wifi_only",it.toString()) },enabled=!busy) }
            DownloadSection("Desempenho avançado",Icons.Filled.Speed)
            Text("Conexões por arquivo")
            Row { listOf(1,2,4).forEach { n -> FilterChip(selected=connections==n,onClick={ connections=n; save("downloads.connections",n.toString()) },label={ Text("$n") },enabled=!busy) } }
            Text("Mais conexões podem ajudar em servidores que aceitam faixas de bytes. O ReiAnix usa uma conexão quando isso não é seguro. Alterações valem na próxima transferência ou retomada.",style=MaterialTheme.typography.bodySmall)
            Text("Buffer de transferência")
            Row { listOf(64,128,256,512).forEach { n -> FilterChip(selected=buffer==n,onClick={ buffer=n; save("downloads.buffer_kb",n.toString()) },label={ Text("$n KB") },enabled=!busy) } }
            Text("Buffers maiores reduzem operações de escrita, mas usam mais memória. A velocidade também depende da rede e do servidor.",style=MaterialTheme.typography.bodySmall)
            DownloadSection("Opções por link",Icons.Filled.Link)
            Text("As credenciais e o hash são específicos do link informado, armazenados de forma cifrada. Salve antes de adicionar o download; para um download existente, pause e salve aqui para aplicá-los.",style=MaterialTheme.typography.bodySmall)
            OutlinedTextField(url,{ url=it },label={ Text("Link do arquivo") },modifier=Modifier.fillMaxWidth())
            TextButton(enabled=!busy && url.isNotBlank(),onClick={ scope.launch {
                busy=true
                try { val p=withContext(Dispatchers.IO) { store.profile(url.trim()) }; hash=p.optString("hash"); kind=p.optString("hashType","SHA-256"); user=p.optString("user"); password=p.optString("password"); cookie=p.optString("cookie"); referer=p.optString("referer"); message="Opções carregadas" }
                catch(_: Exception) { message="Não foi possível carregar este link" } finally { busy=false }
            } }) { Text("Carregar opções do link") }
            DownloadSection("Integridade",Icons.Filled.VerifiedUser)
            Row { listOf("SHA-256","SHA-1","MD5").forEach { k -> FilterChip(selected=kind==k,onClick={kind=k},label={Text(k)}) } }
            OutlinedTextField(hash,{hash=it},label={Text("Hash de referência (opcional)")},modifier=Modifier.fillMaxWidth())
            Text("Sem referência do usuário ou servidor, o SHA-256 é calculado, mas a integridade original não pode ser confirmada.",style=MaterialTheme.typography.bodySmall)
            DownloadSection("Autenticação",Icons.Filled.Lock)
            OutlinedTextField(user,{user=it},label={Text("Usuário HTTP Basic")},modifier=Modifier.fillMaxWidth())
            OutlinedTextField(password,{password=it},label={Text("Senha")},visualTransformation=PasswordVisualTransformation(),modifier=Modifier.fillMaxWidth())
            OutlinedTextField(cookie,{cookie=it},label={Text("Cookie da sessão autorizada")},visualTransformation=PasswordVisualTransformation(),modifier=Modifier.fillMaxWidth())
            OutlinedTextField(referer,{referer=it},label={Text("Referer, se exigido")},modifier=Modifier.fillMaxWidth())
            TextButton(onClick={user="";password="";cookie="";referer=""}) { Text("Limpar autenticação") }
            Button(enabled=!busy && url.isNotBlank(),onClick={ scope.launch {
                busy=true
                try { withContext(Dispatchers.IO) {
                    val data=JSONObject().put("hash",hash.trim()).put("hashType",kind).put("user",user).put("password",password).put("cookie",cookie).put("referer",referer)
                    store.saveProfile(url.trim(),data)
                    store.all().filter { it.state in setOf("ERROR","PAUSED") }.forEach { item ->
                        if(runCatching { store.secret(item).getString("url")==url.trim() }.getOrDefault(false)) {
                            store.updateSource(item.id,url.trim(),downloadProfileHeaders(data),hash.trim(),kind,item.json.optString("destination"))
                        }
                    }
                }; DownloadScheduling.schedule(context); message="Opções salvas" }
                catch(e: Exception) { message=e.message ?: "Não foi possível salvar" } finally { busy=false }
            } }) { Text("Salvar opções do link") }
            if(message.isNotBlank()) Text(message)
        }
    }
}

@Composable
private fun DownloadSection(title: String,icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(16.dp),modifier=Modifier.padding(top=12.dp)) {
        Icon(icon,null,Modifier.size(22.dp)); Text(title,color=MaterialTheme.colorScheme.primary,style=MaterialTheme.typography.titleSmall)
    }
}

internal fun downloadProfileHeaders(data: JSONObject): Map<String,String> = buildMap {
    data.optString("cookie").takeIf {it.isNotBlank()}?.let { put("Cookie",it) }
    data.optString("referer").takeIf {it.isNotBlank()}?.let { put("Referer",it) }
    data.optString("user").takeIf {it.isNotBlank()}?.let { user ->
        put("Authorization","Basic "+android.util.Base64.encodeToString("$user:${data.optString("password")}".toByteArray(),android.util.Base64.NO_WRAP))
    }
}
