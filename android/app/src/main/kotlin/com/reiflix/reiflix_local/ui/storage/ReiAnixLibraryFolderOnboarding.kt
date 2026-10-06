package com.reiflix.reiflix_local.ui.storage

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.reiflix.reiflix_local.ui.theme.ReiAnixTokens

/**
 * Native first-access library-folder gate.
 *
 * This surface is presentation-only. Persistence and authorization remain owned
 * by the existing Python LibraryStore + Android SAF flow.
 */
@Composable
fun ReiAnixLibraryFolderOnboarding(
    state: String,
    message: String?,
    error: String?,
    onSelectFolder: () -> Unit,
) {
    val normalizedState = state.trim().lowercase()
    val checking = normalizedState == "checking"
    val pickerOpen = normalizedState == "folder_picker_open"

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = ReiAnixTokens.Colors.background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(ReiAnixTokens.Spacing.xxxl),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                imageVector = Icons.Outlined.FolderOpen,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = ReiAnixTokens.Colors.primary,
            )

            Text(
                text = if (checking) "Verificando biblioteca" else "Sua biblioteca",
                modifier = Modifier.padding(top = ReiAnixTokens.Spacing.xl),
                style = MaterialTheme.typography.headlineMedium,
                color = ReiAnixTokens.Colors.text,
                textAlign = TextAlign.Center,
            )

            Text(
                text = when {
                    !error.isNullOrBlank() -> error
                    !message.isNullOrBlank() -> message
                    else -> "Selecione a pasta onde estão armazenados seus animes.\n\nO ReiAnix usará essa pasta para encontrar e organizar seus vídeos."
                },
                modifier = Modifier.padding(
                    top = ReiAnixTokens.Spacing.md,
                    bottom = ReiAnixTokens.Spacing.xxl,
                ),
                style = MaterialTheme.typography.bodyLarge,
                color = ReiAnixTokens.Colors.textMuted,
                textAlign = TextAlign.Center,
            )

            when {
                checking || pickerOpen -> {
                    CircularProgressIndicator(
                        modifier = Modifier.size(ReiAnixTokens.Dimensions.loadingIndicatorSize),
                        strokeWidth = ReiAnixTokens.Dimensions.loadingIndicatorStroke,
                        color = ReiAnixTokens.Colors.primary,
                    )
                }
                normalizedState == "error" -> {
                    Button(
                        onClick = onSelectFolder,
                        contentPadding = PaddingValues(horizontal = ReiAnixTokens.Spacing.xl),
                    ) {
                        Text("Tentar novamente")
                    }
                }
                else -> {
                    Button(
                        onClick = onSelectFolder,
                        contentPadding = PaddingValues(horizontal = ReiAnixTokens.Spacing.xl),
                    ) {
                        Text("Selecionar pasta")
                    }
                }
            }
        }
    }
}
