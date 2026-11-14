package com.reianix.reianix_local.ui.storage

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.RocketLaunch
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.reianix.reianix_local.ui.ReiAnixProgressIndicator
import com.reianix.reianix_local.ui.theme.LocalReiAnixResponsiveMetrics
import com.reianix.reianix_local.ui.theme.ReiAnixTokens

/**
 * The user can authorize either source or skip both with "Começar".
 * Paths appear only after the SAF grant and canonical folder registration succeed.
 */
@Composable
fun ReiAnixLibraryFolderOnboarding(
    state: String,
    error: String?,
    selectedFolderPath: String?,
    reiAnixFolderPath: String?,
    onStart: () -> Unit,
    onSelectReiAnixFolder: () -> Unit,
    onSelectFolder: () -> Unit,
) {
    val pickerOpen = state.equals("folder_picker_open", ignoreCase = true)
    // Error may be a diagnostic identifier; do not display internal codes.
    val errorMessage = error?.trim()?.takeIf { value ->
        value.isNotBlank() && value.any { it.isWhitespace() }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = ReiAnixTokens.Colors.background,
    ) {
        Column(
            modifier = Modifier.fillMaxSize()
                .padding(horizontal = LocalReiAnixResponsiveMetrics.current.horizontalPadding)
                .padding(bottom = 12.dp),
            horizontalAlignment = Alignment.Start,
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(top = 44.dp, bottom = 20.dp),
                horizontalAlignment = Alignment.Start,
            ) {
                Icon(
                    imageVector = Icons.Filled.RocketLaunch,
                    contentDescription = null,
                    modifier = Modifier.size(44.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = "Bem-vindo(a) ao ReiAnix!",
                    modifier = Modifier.padding(top = ReiAnixTokens.Spacing.sm),
                    style = ReiAnixTokens.TypographyTokens.display,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = "Vamos definir algumas coisas primeiro. Você sempre pode fazer alterações nas configurações depois também.",
                    modifier = Modifier.padding(
                        top = ReiAnixTokens.Spacing.md,
                        bottom = ReiAnixTokens.Spacing.lg,
                    ),
                    style = ReiAnixTokens.TypographyTokens.body,
                    color = ReiAnixTokens.Colors.textMuted,
                )

                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            text = "Selecione uma pasta que já contém seus animes.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        if (!selectedFolderPath.isNullOrBlank()) {
                            Text(
                                text = "Pasta selecionada: $selectedFolderPath",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Button(
                            onClick = onSelectFolder,
                            enabled = !pickerOpen,
                            modifier = Modifier.fillMaxWidth().height(48.dp),
                        ) {
                            Text("Selecionar uma pasta")
                        }

                        HorizontalDivider(
                            modifier = Modifier.padding(vertical = 4.dp),
                            color = MaterialTheme.colorScheme.outlineVariant,
                        )

                        Text(
                            text = "Autorize a pasta padrão Downloads/ReiAnix para guardar seus animes e downloads.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = "O Android solicitará confirmação de acesso somente a essa pasta.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (!reiAnixFolderPath.isNullOrBlank()) {
                            Text(
                                text = "Pasta selecionada: $reiAnixFolderPath",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Button(
                            onClick = onSelectReiAnixFolder,
                            enabled = !pickerOpen,
                            modifier = Modifier.fillMaxWidth().height(48.dp),
                        ) {
                            Text("Autorizar pasta ReiAnix")
                        }

                        if (pickerOpen) {
                            ReiAnixProgressIndicator(
                                progress = 0f,
                                modifier = Modifier.align(Alignment.CenterHorizontally),
                                announceProgress = false,
                            )
                        }
                        if (errorMessage != null) {
                            Text(
                                text = errorMessage,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            }

            Button(
                onClick = onStart,
                enabled = !pickerOpen,
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) {
                Text("Começar", textAlign = TextAlign.Center)
            }
        }
    }
}
