package com.reiflix.reiflix_local

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.reiflix.reiflix_local.ui.ReiAnixTopBar
import com.reiflix.reiflix_local.ui.account.ReiAnixAccountAvatar
import com.reiflix.reiflix_local.ui.settings.settingsClickable
import com.reiflix.reiflix_local.ui.settings.ReiAnixSettingsRoot
import com.reiflix.reiflix_local.ui.settings.ReiAnixSettingsTheme
import com.reiflix.reiflix_local.ui.settings.SettingsHeader
import com.reiflix.reiflix_local.ui.theme.ReiAnixComposeTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

class AniListAccountActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            ReiAnixComposeTheme {
                ReiAnixSettingsRoot {
                    AniListAccountSettingsScreen(onBack = { finish() }, useSystemInsets = true)
                }
            }
        }
    }
}

/** Shared by the main Settings navigator and the standalone OAuth/account entry point. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AniListAccountSettingsScreen(onBack: () -> Unit, useSystemInsets: Boolean = false) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val account = remember(context) { ReiAnixAppDependencies.from(context).aniListAccountService }
    LaunchedEffect(account) {
        account.retryPending()
        account.refreshProfile()
    }
                val state by account.state.collectAsStateWithLifecycle()
                val scope = rememberCoroutineScope()
                var busy by remember { mutableStateOf(false) }
                var error by remember { mutableStateOf("") }
                var showAccount by rememberSaveable { mutableStateOf(false) }
                val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
                val connect: () -> Unit = {
                    if (!busy) scope.launch {
                        busy = true
                        error = ""
                        try {
                            context.startActivity(Intent(Intent.ACTION_VIEW, account.authorizationUri())
                                .addCategory(Intent.CATEGORY_BROWSABLE))
                            showAccount = false
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            error = "Não foi possível abrir a conexão. Tente novamente."
                            showAccount = false
                        } finally {
                            busy = false
                        }
                    }
                }
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Column(
                        Modifier
                            .then(if (useSystemInsets) Modifier.windowInsetsPadding(WindowInsets.safeDrawing) else Modifier)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        SettingsHeader(title = "Contas e Avançado", subtitle = "", onBack = onBack)
                        Row(
                            Modifier.fillMaxWidth()
                                .settingsClickable(enabled = !busy, role = Role.Button, onClick = {
                                    error = ""
                                    if (state.connected) showAccount = true
                                    else if (state.configured) connect()
                                    else error = "A conexão com o AniList está indisponível nesta versão."
                                })
                                .semantics {
                                    stateDescription = if (state.connected) "Conta conectada" else "Conta não conectada"
                                }
                                .heightIn(min = 64.dp).padding(horizontal = 16.dp, vertical = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(24.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(painterResource(R.drawable.ic_anilist), contentDescription = null,
                                modifier = Modifier.size(32.dp), tint = MaterialTheme.colorScheme.onSurface)
                            Text("AniList", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                            if (state.connected) Icon(Icons.Filled.CheckCircle, contentDescription = "Conta conectada",
                                tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
                        }
                        if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp))
                        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp))
                    }
                }
                if (showAccount && state.connected) {
                    ModalBottomSheet(
                        onDismissRequest = { showAccount = false },
                        sheetState = sheetState,
                        dragHandle = null,
                        containerColor = MaterialTheme.colorScheme.background,
                    ) {
                        Column(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 28.dp, bottom = 20.dp)) {
                            Row(Modifier.fillMaxWidth().padding(bottom = 24.dp),
                                horizontalArrangement = Arrangement.spacedBy(20.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                ReiAnixAccountAvatar(pictureUrl = state.avatarUrl,
                                    contentDescription = "Foto de ${state.name}")
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(state.name, style = MaterialTheme.typography.titleLarge,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text("AniList", style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            AccountActionRow("Trocar conta", enabled = !busy, onClick = connect)
                            AccountActionRow("Sair", enabled = !busy, onClick = {
                                scope.launch {
                                    busy = true
                                    error = ""
                                    try {
                                        account.disconnect()
                                        showAccount = false
                                    } catch (cancelled: CancellationException) {
                                        throw cancelled
                                    } catch (_: Exception) {
                                        error = "Não foi possível desconectar. Tente novamente."
                                        showAccount = false
                                    } finally {
                                        busy = false
                                    }
                                }
                            })
                        }
                    }
                }
}

@Composable
private fun AccountActionRow(label: String, enabled: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().settingsClickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .heightIn(min = 56.dp).padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        com.reiflix.reiflix_local.ui.settings.SettingsLeadingIcon(label)
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null,
            modifier = Modifier.size(28.dp))
    }
}

/** Dedicated callback: OAuth fragments never enter the player/diagnostic intent handlers. */
class AniListOAuthCallbackActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val callback = intent.data
        // Clear the credential-bearing URI immediately; never log or save it in instance state.
        intent.data = null
        setContent {
            ReiAnixComposeTheme { ReiAnixSettingsTheme {
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.windowInsetsPadding(WindowInsets.safeDrawing).padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text("Conectando ao AniList…")
                        CircularProgressIndicator()
                    }
                }
            }
            }
        }
        lifecycleScope.launch {
            try {
                val connected = callback != null && ReiAnixAppDependencies.from(applicationContext)
                    .aniListAccountService.acceptCallback(callback)
                if (!connected) android.widget.Toast.makeText(this@AniListOAuthCallbackActivity,
                    "Conexão não concluída. Tente novamente.", android.widget.Toast.LENGTH_LONG).show()
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: Exception) {
                android.widget.Toast.makeText(this@AniListOAuthCallbackActivity,
                    "Não foi possível conectar ao AniList. Tente novamente.", android.widget.Toast.LENGTH_LONG).show()
            } finally {
                if (!isFinishing && !isDestroyed) {
                    // Return to the already open Settings page instead of pushing a second
                    // Activity over it. When restored as a task root, reopen the app safely.
                    if (isTaskRoot) {
                        runCatching {
                            startActivity(Intent(this@AniListOAuthCallbackActivity, MainActivity::class.java)
                                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
                        }
                    }
                    finish()
                }
            }
        }
    }
}
