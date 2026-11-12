package com.reianix.reianix_local.downloads

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
import com.reianix.reianix_local.ReiAnixAppDependencies
import kotlinx.coroutines.*
import org.json.JSONObject

@Composable
fun DownloadSettingsScreen(onBack: ()->Unit) {
    val context=LocalContext.current
    val scope=rememberCoroutineScope()
    val store=remember { DownloadStore(context) }
    val prefs=remember { ReiAnixAppDependencies.from(context).sqliteSettingsRepository }
    var parallel by remember { mutableIntStateOf(2) }
    var buffer by remember { mutableIntStateOf(256) }
    var connections by remember { mutableIntStateOf(4) }
    var wifi by remember { mutableStateOf(false) }
    var url by remember { mutableStateOf("") }
    var user by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var cookie by remember { mutableStateOf("") }
    var referer by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        try { withContext(Dispatchers.IO) {
            store.ready(); parallel=prefs.getPreference("downloads.parallel","2")?.toIntOrNull() ?: 2
            buffer=prefs.getPreference("downloads.buffer_kb","256")?.toIntOrNull() ?: 256
            connections=prefs.getPreference("downloads.connections","4")?.toIntOrNull() ?: 4
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
            DownloadSection("Fila e rede")
            DownloadOption("Downloads simultâneos",Icons.Filled.Queue)
            Row(Modifier.padding(start=38.dp)) { (1..4).forEach { n -> FilterChip(selected=parallel==n,onClick={ parallel=n; save("downloads.parallel",n.toString()) },label={ Text("$n") },enabled=!busy) } }
            Row(verticalAlignment=Alignment.CenterVertically) { Icon(Icons.Filled.Wifi,null,Modifier.padding(end=16.dp).size(22.dp)); Text("Somente Wi-Fi",Modifier.weight(1f)); Switch(wifi,{ wifi=it; save("downloads.wifi_only",it.toString()) },enabled=!busy) }
            DownloadSection("Desempenho avançado")
            DownloadOption("Número de partes",Icons.Filled.CallSplit)
            Row(Modifier.padding(start=38.dp)) { listOf(1,2,4,8,16).forEach { n -> FilterChip(selected=connections==n,onClick={ connections=n; save("downloads.connections",n.toString()) },label={ Text("$n") },enabled=!busy) } }
            Text("Mais conexões podem ajudar em servidores que aceitam faixas de bytes. O ReiAnix usa uma conexão quando isso não é seguro. Alterações valem na próxima transferência ou retomada.",style=MaterialTheme.typography.bodySmall)
            DownloadOption("Buffer de transferência",Icons.Filled.Memory)
            Row(Modifier.padding(start=38.dp)) { listOf(64,128,256,512).forEach { n -> FilterChip(selected=buffer==n,onClick={ buffer=n; save("downloads.buffer_kb",n.toString()) },label={ Text("$n KB") },enabled=!busy) } }
            Text("Buffers maiores reduzem operações de escrita, mas usam mais memória. A velocidade também depende da rede e do servidor.",style=MaterialTheme.typography.bodySmall)
            DownloadSection("Opções por link")
            Text("As credenciais são específicas do link informado, armazenadas de forma cifrada. Salve antes de adicionar o download; para um download existente, pause e salve aqui para aplicá-los.",style=MaterialTheme.typography.bodySmall)
            DownloadOption("Link do arquivo",Icons.Filled.Link)
            OutlinedTextField(url,{ url=it },label={ Text("Link do arquivo") },modifier=Modifier.fillMaxWidth())
            TextButton(enabled=!busy && url.isNotBlank(),onClick={ scope.launch {
                busy=true
                try { val p=withContext(Dispatchers.IO) { store.profile(url.trim()) }; user=p.optString("user"); password=p.optString("password"); cookie=p.optString("cookie"); referer=p.optString("referer"); message="Opções carregadas" }
                catch(_: Exception) { message="Não foi possível carregar este link" } finally { busy=false }
            } }) { Icon(Icons.Filled.FolderOpen,null,Modifier.size(20.dp));Spacer(Modifier.width(8.dp));Text("Carregar opções do link") }
            DownloadSection("Autenticação")
            DownloadOption("Usuário",Icons.Filled.Person)
            OutlinedTextField(user,{user=it},label={Text("Usuário HTTP Basic")},modifier=Modifier.fillMaxWidth())
            DownloadOption("Senha",Icons.Filled.Password)
            OutlinedTextField(password,{password=it},label={Text("Senha")},visualTransformation=PasswordVisualTransformation(),modifier=Modifier.fillMaxWidth())
            DownloadOption("Cookie da sessão",Icons.Filled.Cookie)
            OutlinedTextField(cookie,{cookie=it},label={Text("Cookie da sessão autorizada")},visualTransformation=PasswordVisualTransformation(),modifier=Modifier.fillMaxWidth())
            DownloadOption("Referer",Icons.Filled.Public)
            OutlinedTextField(referer,{referer=it},label={Text("Referer, se exigido")},modifier=Modifier.fillMaxWidth())
            TextButton(onClick={user="";password="";cookie="";referer=""}) { Icon(Icons.Filled.LockReset,null,Modifier.size(20.dp));Spacer(Modifier.width(8.dp));Text("Limpar autenticação") }
            Button(enabled=!busy && url.isNotBlank(),onClick={ scope.launch {
                busy=true
                try { withContext(Dispatchers.IO) {
                    val data=JSONObject().put("user",user).put("password",password).put("cookie",cookie).put("referer",referer)
                    store.saveProfile(url.trim(),data)
                    store.all().filter { it.state in setOf("ERROR","PAUSED") }.forEach { item ->
                        if(runCatching { store.secret(item).getString("url")==url.trim() }.getOrDefault(false)) {
                            val old=store.secret(item).optJSONObject("headers") ?: JSONObject()
                            val headers=old.keys().asSequence().filter {it.lowercase() !in setOf("authorization","cookie","referer")}.associateWith {old.getString(it)}+downloadProfileHeaders(data)
                            store.updateSource(item.id,url.trim(),headers,"","SHA-256",item.json.optString("destination"))
                        }
                    }
                }; DownloadScheduling.schedule(context); message="Opções salvas" }
                catch(e: Exception) { message=e.message ?: "Não foi possível salvar" } finally { busy=false }
            } }) { Icon(Icons.Filled.Save,null,Modifier.size(20.dp));Spacer(Modifier.width(8.dp));Text("Salvar opções do link") }
            if(message.isNotBlank()) Text(message)
        }
    }
}

@Composable
private fun DownloadSection(title: String) {
    Text(title,color=MaterialTheme.colorScheme.primary,style=MaterialTheme.typography.labelLarge,modifier=Modifier.padding(start=38.dp,top=24.dp,bottom=8.dp))
}

@Composable
private fun DownloadOption(title: String,icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(16.dp)) {
        Icon(icon,null,Modifier.size(22.dp));Text(title,style=MaterialTheme.typography.bodyLarge)
    }
}

internal fun downloadProfileHeaders(data: JSONObject): Map<String,String> = buildMap {
    data.optString("cookie").takeIf {it.isNotBlank()}?.let { put("Cookie",it) }
    data.optString("referer").takeIf {it.isNotBlank()}?.let { put("Referer",it) }
    data.optString("user").takeIf {it.isNotBlank()}?.let { user ->
        put("Authorization","Basic "+android.util.Base64.encodeToString("$user:${data.optString("password")}".toByteArray(),android.util.Base64.NO_WRAP))
    }
}
