package com.reianix.reianix_local.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.reianix.reianix_local.ui.theme.LocalReiAnixResponsiveMetrics
import com.reianix.reianix_local.ui.theme.ReiAnixTokens
import com.reianix.reianix_local.ui.theme.reiAnixScreenBackground

internal enum class ReiAnixLegalDocument(val title: String, val asset: String, val publicUrl: String) {
    PRIVACY("Política de privacidade", "legal/privacy.txt", "https://reinansantos9090-debug.github.io/ReiAnix/privacy.html"),
    TERMS("Termos de uso", "legal/terms.txt", "https://reinansantos9090-debug.github.io/ReiAnix/terms.html"),
}

@Composable
internal fun ReiAnixLegalDocumentScreen(document: ReiAnixLegalDocument, onBack: () -> Unit) {
    val context = LocalContext.current
    val paragraphs = remember(document) {
        runCatching {
            context.assets.open(document.asset).bufferedReader(Charsets.UTF_8).use { it.readText() }
                .trim().split("\n\n").filter { it.isNotBlank() }
        }.getOrElse { listOf("Não foi possível ler o documento incluído no aplicativo.") }
    }
    var openFailed by remember(document) { mutableStateOf(false) }
    ReiAnixSettingsRoot {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = reiAnixScreenBackground(),
            contentColor = MaterialTheme.colorScheme.onBackground,
        ) {
            LazyColumn(
                modifier = Modifier.widthIn(max = LocalReiAnixResponsiveMetrics.current.settingsMaxWidth).fillMaxSize(),
                contentPadding = PaddingValues(bottom = ReiAnixTokens.Spacing.xl),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item(key = "legal:header") {
                    SettingsHeader(title = document.title, subtitle = "", onBack = onBack)
                }
                items(paragraphs) { paragraph ->
                    if (paragraph.startsWith("# ")) {
                        val heading = paragraph.substringBefore('\n').removePrefix("# ")
                        val body = paragraph.substringAfter('\n', "")
                        androidx.compose.foundation.layout.Column(
                            modifier = Modifier.padding(horizontal = 20.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(heading, style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.semantics { heading() })
                            Text(body, style = MaterialTheme.typography.bodyMedium)
                        }
                    } else {
                        Text(paragraph, modifier = Modifier.padding(horizontal = 20.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                item(key = "legal:online") {
                    ReiAnixSettingsRow(
                        icon = settingsIcon("legal:online"),
                        title = "Abrir versão pública",
                        description = document.publicUrl,
                        onClick = {
                            openFailed = runCatching {
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(document.publicUrl)))
                            }.isFailure
                        },
                    )
                }
                if (openFailed) item(key = "legal:error") {
                    Text("Não foi possível abrir o navegador. O documento permanece disponível acima.",
                        modifier = Modifier.padding(horizontal = 20.dp),
                        color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}
