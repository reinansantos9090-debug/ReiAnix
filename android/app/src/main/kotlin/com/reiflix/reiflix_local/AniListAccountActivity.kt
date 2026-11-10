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
import com.reiflix.reiflix_local.ui.theme.ReiAnixComposeTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

class AniListAccountActivity : ComponentActivity() {
    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val account = ReiAnixAppDependencies.from(this).aniListAccountService
        account.retryPending()
        lifecycleScope.launch { account.refreshProfile() }
        setContent {
            ReiAnixComposeTheme {
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
                            startActivity(Intent(Intent.ACTION_VIEW, account.authorizationUri())
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
                        Modifier.windowInsetsPadding(WindowInsets.safeDrawing)
                            .verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
                    ) {
                        ReiAnixTopBar(
                            title = "Contas",
                            navigationIcon = Icons.AutoMirrored.Filled.ArrowBack,
                            onNavigationClick = { finish() },
                            applyResponsiveHorizontalPadding = false,
                            modifier = Modifier.padding(top = 12.dp, bottom = 12.dp),
                        )
                        Row(
                            Modifier.fillMaxWidth()
                                .clickable(enabled = !busy, role = Role.Button, onClick = {
                                    error = ""
                                    if (state.connected) showAccount = true
                                    else if (state.configured) connect()
                                    else error = "A conexão com o AniList está indisponível nesta versão."
                                })
                                .semantics {
                                    stateDescription = if (state.connected) "Conta conectada" else "Conta não conectada"
                                }
                                .heightIn(min = 76.dp).padding(horizontal = 16.dp, vertical = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(24.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(painterResource(R.drawable.ic_anilist), contentDescription = null,
                                modifier = Modifier.size(32.dp), tint = MaterialTheme.colorScheme.onSurface)
                            Text("AniList", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
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
        }
    }
}

@Composable
private fun AccountActionRow(label: String, enabled: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .heightIn(min = 72.dp).padding(vertical = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
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
            ReiAnixComposeTheme {
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.windowInsetsPadding(WindowInsets.safeDrawing).padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text("Conectando ao AniList…")
                        CircularProgressIndicator()
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
                    runCatching {
                        startActivity(Intent(this@AniListOAuthCallbackActivity, AniListAccountActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
                    }
                    finish()
                }
            }
        }
    }
}
