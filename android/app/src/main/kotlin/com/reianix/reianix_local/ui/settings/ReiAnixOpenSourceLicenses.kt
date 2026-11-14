package com.reianix.reianix_local.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.reianix.reianix_local.ui.theme.LocalReiAnixResponsiveMetrics
import com.reianix.reianix_local.ui.theme.ReiAnixTokens
import androidx.compose.foundation.layout.widthIn

/** Credits for the app and direct runtime dependencies declared in app/build.gradle.kts.
 * Test-only dependencies are intentionally excluded. Each item opens the license
 * text packaged in this APK; Google Sans retains its own OFL and modification notice.
 * Transitive dependencies should also be audited when publishing a new release.
 */
internal data class ReiAnixLicenseCredit(
    val id: String,
    val title: String,
    val attribution: String,
    val licenseName: String,
    val licenseAsset: String = "licenses/apache_2_0.txt",
    val noticeAsset: String? = null,
)

internal val ReiAnixLicenseCredits = listOf(
    ReiAnixLicenseCredit("reianix", "ReiAnix", "Copyright © 2026 Reinan Santos", "Apache License 2.0",
        noticeAsset = "licenses/reianix_NOTICE.txt"),
    ReiAnixLicenseCredit("kotlin", "Kotlin e biblioteca padrão", "JetBrains e colaboradores Kotlin", "Apache License 2.0"),
    ReiAnixLicenseCredit("coroutines", "kotlinx.coroutines", "JetBrains e colaboradores Kotlin", "Apache License 2.0"),
    ReiAnixLicenseCredit("activity", "AndroidX Activity", "Android Open Source Project", "Apache License 2.0"),
    ReiAnixLicenseCredit("compose", "Jetpack Compose e Material", "Android Open Source Project", "Apache License 2.0"),
    ReiAnixLicenseCredit("lifecycle", "AndroidX Lifecycle", "Android Open Source Project", "Apache License 2.0"),
    ReiAnixLicenseCredit("navigation", "AndroidX Navigation", "Android Open Source Project", "Apache License 2.0"),
    ReiAnixLicenseCredit("documentfile", "AndroidX DocumentFile", "Android Open Source Project", "Apache License 2.0"),
    ReiAnixLicenseCredit("webkit", "AndroidX WebKit", "Android Open Source Project", "Apache License 2.0"),
    ReiAnixLicenseCredit("workmanager", "AndroidX WorkManager", "Android Open Source Project", "Apache License 2.0"),
    ReiAnixLicenseCredit("media3", "AndroidX Media3 (ExoPlayer)", "Android Open Source Project", "Apache License 2.0"),
    ReiAnixLicenseCredit("coil", "Coil e Coil Compose", "Coil Contributors", "Apache License 2.0"),
    ReiAnixLicenseCredit("google-sans", "Google Sans — fonte das configurações",
        "Copyright 2025 The Google Sans Project Authors", "SIL Open Font License 1.1",
        licenseAsset = "licenses/google_sans_OFL.txt"),
)

@Composable
internal fun ReiAnixOpenSourceLicensesScreen(onBack: () -> Unit) {
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    val selected = ReiAnixLicenseCredits.firstOrNull { it.id == selectedId }
    val context = LocalContext.current
    val listState = rememberLazyListState()
    BackHandler(enabled = selected != null) { selectedId = null }
    LaunchedEffect(selectedId) { listState.scrollToItem(0) }

    // License texts are bundled offline, not fetched through a network request.
    val licenseText = remember(selected?.licenseAsset) {
        selected?.let { credit ->
            runCatching {
                context.assets.open(credit.licenseAsset).bufferedReader(Charsets.UTF_8).use { it.readText() }
            }.getOrElse { "Não foi possível ler o texto da licença incluído no APK." }
        }.orEmpty()
    }
    val noticeText = remember(selected?.noticeAsset) {
        selected?.noticeAsset?.let { asset ->
            runCatching {
                context.assets.open(asset).bufferedReader(Charsets.UTF_8).use { it.readText() }
            }.getOrElse { "Não foi possível ler os avisos de atribuição incluídos no APK." }
        }
    }

    ReiAnixSettingsRoot {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            LazyColumn(
                modifier = Modifier
                    .widthIn(max = LocalReiAnixResponsiveMetrics.current.settingsMaxWidth)
                    .fillMaxSize(),
                state = listState,
                contentPadding = PaddingValues(bottom = ReiAnixTokens.Spacing.xl),
            ) {
                item(key = "licenses:header") {
                    SettingsHeader(
                        title = selected?.title ?: "Licenças",
                        subtitle = "",
                        onBack = { if (selected == null) onBack() else selectedId = null },
                        backContentDescription = "Voltar",
                    )
                }
                if (selected == null) {
                    item(key = "licenses:intro") {
                        Text(
                            "Licenças e créditos das bibliotecas diretamente utilizadas pelo ReiAnix.",
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    items(ReiAnixLicenseCredits, key = { "license:" + it.id }) { credit ->
                        Row(
                            modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp)
                                .settingsClickable(role = Role.Button) { selectedId = credit.id }
                                .padding(horizontal = 20.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    credit.title,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    credit.licenseName,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Icon(
                                Icons.Filled.ChevronRight,
                                contentDescription = null,
                                modifier = Modifier.size(24.dp),
                            )
                        }
                    }
                    item(key = "licenses:footnote") {
                        Text(
                            "As dependências transitivas podem exigir avisos adicionais. " +
                                "Os arquivos de licença originais também permanecem disponíveis no repositório.",
                            modifier = Modifier.padding(20.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    item(key = "licenses:detail") {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(20.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Text(selected.licenseName, style = MaterialTheme.typography.titleMedium)
                            Text(
                                selected.attribution,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            noticeText?.let { notice ->
                                Text(notice, style = MaterialTheme.typography.bodySmall)
                            }
                            Text(licenseText, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
}
