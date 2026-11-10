package com.reiflix.reiflix_local

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.reiflix.reiflix_local.ui.theme.ReiAnixComposeTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

class AniListAccountActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val account = ReiAnixAppDependencies.from(this).aniListAccountService
        account.retryPending()
        setContent {
            ReiAnixComposeTheme {
                val state by account.state.collectAsStateWithLifecycle()
                val scope = rememberCoroutineScope()
                var busy by remember { mutableStateOf(false) }
                var error by remember { mutableStateOf("") }
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.windowInsetsPadding(WindowInsets.safeDrawing).verticalScroll(rememberScrollState()).padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        TextButton(onClick = { finish() }) { Text("Voltar") }
                        Text("Contas e sincronização", style = MaterialTheme.typography.headlineSmall)
                        Text("AniList", style = MaterialTheme.typography.titleLarge)
                        Text(if (state.connected) "Conectado como ${state.name}" else "Conta não conectada")
                        if (!state.configured) Text("A conexão com o AniList ainda não está disponível nesta versão.")
                        Text("Conecte sua conta para atualizar sua lista enquanto assiste. Sua senha é informada somente na página oficial do AniList.")
                        if (!state.connected) {
                            Button(enabled = state.configured && !busy, onClick = {
                                scope.launch {
                                    busy = true; error = ""
                                    try { startActivity(Intent(Intent.ACTION_VIEW, account.authorizationUri()).addCategory(Intent.CATEGORY_BROWSABLE)) }
                                    catch (cancelled: CancellationException) { throw cancelled }
                                    catch (_: Exception) { error = "Não foi possível abrir a conexão. Tente novamente." }
                                    finally { busy = false }
                                }
                            }) { Text("Conectar ao AniList") }
                        } else {
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text("Atualizar episódios automaticamente", Modifier.weight(1f))
                                Switch(checked = state.autoSync, enabled = !busy, onCheckedChange = { enabled ->
                                    scope.launch {
                                        busy = true; error = ""
                                        try { account.setAutoSync(enabled) }
                                        catch (cancelled: CancellationException) { throw cancelled }
                                        catch (_: Exception) { error = "Não foi possível alterar a sincronização." }
                                        finally { busy = false }
                                    }
                                })
                            }
                            Text("Ao atingir 80% do episódio, atualiza o progresso no AniList. Ao terminar o anime, marca como concluído quando o total de episódios é conhecido.")
                            Text("Para sincronizar, confirme a associação ao AniList na tela de detalhes de cada anime. Especiais e numeração incompatível não são enviados.")
                            OutlinedButton(enabled = !busy, onClick = {
                                scope.launch {
                                    busy = true
                                    try { account.disconnect() }
                                    catch (cancelled: CancellationException) { throw cancelled }
                                    catch (_: Exception) { error = "Não foi possível desconectar. Tente novamente." }
                                    finally { busy = false }
                                }
                            }) { Text("Desconectar conta") }
                        }
                        if (state.message.isNotBlank()) Text(state.message)
                        if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
                        if (busy) CircularProgressIndicator()
                    }
                }
            }
        }
    }
}

/** Dedicated callback: OAuth fragments never enter the player/diagnostic intent handlers. */
class AniListOAuthCallbackActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val callback = intent.data
        // Clear the credential-bearing URI immediately; never log or save it in instance state.
        intent.data = null
        lifecycleScope.launch {
            try {
                if (callback != null) ReiAnixAppDependencies.from(applicationContext)
                    .aniListAccountService.acceptCallback(callback)
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: Exception) {
                android.widget.Toast.makeText(this@AniListOAuthCallbackActivity,
                    "Não foi possível conectar ao AniList. Tente novamente.", android.widget.Toast.LENGTH_LONG).show()
            } finally {
                if (!isFinishing && !isDestroyed) {
                    startActivity(Intent(this@AniListOAuthCallbackActivity, AniListAccountActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
                    finish()
                }
            }
        }
    }
}
