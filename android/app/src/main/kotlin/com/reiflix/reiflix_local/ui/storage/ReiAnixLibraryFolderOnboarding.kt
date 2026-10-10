package com.reiflix.reiflix_local.ui.storage

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.reiflix.reiflix_local.ui.ReiAnixCard
import com.reiflix.reiflix_local.ui.ReiAnixSecondaryButton
import com.reiflix.reiflix_local.ui.ReiAnixProgressIndicator
import com.reiflix.reiflix_local.ui.theme.LocalReiAnixResponsiveMetrics
import com.reiflix.reiflix_local.ui.theme.ReiAnixTokens

/**
 * First-access surface for a library without an authorized source.
 *
 * Uses the ReiAnix theme and tokens while adapting the source reference's
 * horizontal action row to wide layouts. Compact layouts retain stacked,
 * touch-friendly actions. Storage authorization, persistence, scan coordination
 * and navigation remain owned by the existing Python LibraryStore + Android bridge.
 */
@Composable
fun ReiAnixLibraryFolderOnboarding(
    state: String,
    message: String?,
    error: String?,
    onCancel: () -> Unit,
    onSelectFolder: () -> Unit,
) {
    val normalizedState = state.trim().lowercase()
    val pickerOpen = normalizedState == "folder_picker_open"
    val errorMessage = error?.takeIf { it.isNotBlank() }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = ReiAnixTokens.Colors.background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    horizontal = LocalReiAnixResponsiveMetrics.current.horizontalPadding,
                    vertical = ReiAnixTokens.Spacing.xxxl,
                ),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            ReiAnixCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = ReiAnixTokens.Dimensions.onboardingCardMaxWidth),
            ) {
                BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                    val wideLayout = maxWidth >= 480.dp
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = if (wideLayout) Alignment.Start else Alignment.CenterHorizontally,
                    ) {
                        if (!wideLayout) {
                            Icon(
                                imageVector = if (pickerOpen) Icons.Filled.Folder else Icons.Filled.VideoLibrary,
                                contentDescription = null,
                                modifier = Modifier
                                    .align(Alignment.CenterHorizontally)
                                    .size(ReiAnixTokens.Dimensions.onboardingIconSize),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }

                        Text(
                            text = "Fonte da biblioteca necessária",
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = if (wideLayout) ReiAnixTokens.Spacing.xs else ReiAnixTokens.Spacing.lg),
                            style = ReiAnixTokens.TypographyTokens.display,
                            color = MaterialTheme.colorScheme.onSurface,
                            textAlign = if (wideLayout) TextAlign.Start else TextAlign.Center,
                        )

                        Text(
                            text = when {
                                errorMessage != null -> errorMessage
                                pickerOpen -> "Aguardando a escolha da pasta no Android…"
                                else -> "Escolha uma pasta que pertença à sua biblioteca do ReiAnix. " +
                                    "Somente vídeos dentro dessa pasta e de suas subpastas serão considerados."
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(
                                    top = ReiAnixTokens.Spacing.md,
                                    bottom = ReiAnixTokens.Spacing.xl,
                                ),
                            style = ReiAnixTokens.TypographyTokens.body,
                            color = ReiAnixTokens.Colors.textMuted,
                            textAlign = if (wideLayout) TextAlign.Start else TextAlign.Center,
                        )

                        if (errorMessage != null) {
                            Text(
                                text = "Você pode tentar novamente sem fechar o aplicativo.",
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = ReiAnixTokens.Spacing.lg),
                                style = MaterialTheme.typography.bodySmall,
                                color = ReiAnixTokens.Colors.error,
                                textAlign = if (wideLayout) TextAlign.Start else TextAlign.Center,
                            )
                        }

                        if (pickerOpen) {
                            ReiAnixProgressIndicator(
                                progress = 0f,
                                modifier = Modifier
                                    .align(if (wideLayout) Alignment.Start else Alignment.CenterHorizontally)
                                    .padding(bottom = ReiAnixTokens.Spacing.lg),
                                announceProgress = false,
                            )
                        }

                        if (wideLayout) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = ReiAnixTokens.Spacing.sm),
                                horizontalArrangement = Arrangement.spacedBy(
                                    ReiAnixTokens.Spacing.sm,
                                    Alignment.End,
                                ),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                ReiAnixSecondaryButton(
                                    text = "CANCELAR",
                                    onClick = onCancel,
                                    enabled = !pickerOpen,
                                    modifier = Modifier.semantics { role = Role.Button },
                                )
                                ReiAnixSecondaryButton(
                                    text = "ESCOLHER PASTA",
                                    onClick = onSelectFolder,
                                    enabled = !pickerOpen,
                                )
                            }
                        } else {
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(ReiAnixTokens.Spacing.sm),
                            ) {
                                ReiAnixSecondaryButton(
                                    text = "CANCELAR",
                                    onClick = onCancel,
                                    enabled = !pickerOpen,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .semantics { role = Role.Button },
                                )
                                ReiAnixSecondaryButton(
                                    text = "ESCOLHER PASTA",
                                    onClick = onSelectFolder,
                                    enabled = !pickerOpen,
                                    leadingIcon = Icons.Filled.Folder,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
