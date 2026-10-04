package com.reiflix.reiflix_local.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.vector.ImageVector
import com.reiflix.reiflix_local.ui.theme.ReiAnixTokens

/**
 * Presentation-only state components for the existing local-library flows.
 *
 * Operations stay owned by their existing repositories/ViewModels. Callbacks
 * are supplied by the owner so these components never invent retry behavior.
 */

@Composable
fun ReiAnixLoadingState(
    title: String = "Carregando",
    message: String = "Carregando…",
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .padding(ReiAnixTokens.Spacing.xxl)
            .semantics { contentDescription = "ReiAnixLoadingState" },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(ReiAnixTokens.Spacing.md),
    ) {
        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
fun ReiAnixEmptyState(
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    icon: ImageVector? = null,
) {
    Column(
        modifier = modifier
            .padding(ReiAnixTokens.Spacing.xxl)
            .semantics { contentDescription = "ReiAnixEmptyState" },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(ReiAnixTokens.Spacing.sm),
    ) {
        icon?.let {
            Icon(
                imageVector = it,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(ReiAnixTokens.Dimensions.touchTarget),
            )
        }
        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
                    )
        Text(
            text = message,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (actionLabel != null && onAction != null) {
            ReiAnixSecondaryButton(
                text = actionLabel,
                onClick = onAction,
            )
        }
    }
}

@Composable
fun ReiAnixEmptyLibraryState(
    title: String = "Biblioteca vazia",
    message: String = "Nenhum conteúdo local disponível.",
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    ReiAnixEmptyState(
        title = title,
        message = message,
        modifier = modifier,
        actionLabel = actionLabel,
        onAction = onAction,
    )
}

@Composable
fun ReiAnixScannerInProgressState(
    scanState: String,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    val normalizedState = scanState.trim().ifBlank { "SCANNING" }
    if (compact) {
        Row(
            modifier = modifier
                .fillMaxWidth()
                .padding(ReiAnixTokens.Spacing.md)
                .semantics { contentDescription = "ReiAnixScannerInProgressState" },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ReiAnixTokens.Spacing.md),
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(ReiAnixTokens.Dimensions.loadingIndicatorSize),
                strokeWidth = ReiAnixTokens.Dimensions.loadingIndicatorStroke,
                color = MaterialTheme.colorScheme.primary,
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Varredura em andamento",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = normalizedState,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    } else {
        ReiAnixLoadingState(
            title = "Varredura em andamento",
            message = normalizedState,
            modifier = modifier,
        )
    }
}

@Composable
fun ReiAnixSourceUnavailableState(
    title: String = "Fonte local indisponível",
    message: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = "Verificar acesso",
    onAction: (() -> Unit)? = null,
) {
    ReiAnixEmptyState(
        title = title,
        message = message,
        modifier = modifier.semantics {
            contentDescription = "ReiAnixSourceUnavailableState"
        },
        actionLabel = actionLabel,
        onAction = onAction,
    )
}

@Composable
fun ReiAnixFileUnavailableState(
    title: String = "Arquivo indisponível",
    message: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    compact: Boolean = false,
) {
    if (compact) {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .padding(vertical = ReiAnixTokens.Spacing.xs)
                .semantics { contentDescription = "ReiAnixFileUnavailableState" },
            verticalArrangement = Arrangement.spacedBy(ReiAnixTokens.Spacing.xs),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                color = ReiAnixTokens.Colors.warning,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (actionLabel != null && onAction != null) {
                ReiAnixSecondaryButton(
                    text = actionLabel,
                    onClick = onAction,
                )
            }
        }
    } else {
        ReiAnixEmptyState(
            title = title,
            message = message,
            modifier = modifier.semantics {
                contentDescription = "ReiAnixFileUnavailableState"
            },
            actionLabel = actionLabel,
            onAction = onAction,
        )
    }
}

@Composable
fun ReiAnixArtworkMissingState(
    label: String = "Sem arte",
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.semantics {
            contentDescription = "ReiAnixArtworkMissingState"
        },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
fun ReiAnixRecoverableErrorState(
    title: String,
    message: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    retryLabel: String = "Tentar novamente",
    icon: ImageVector? = Icons.Filled.ErrorOutline,
) {
    Column(
        modifier = modifier
            .padding(ReiAnixTokens.Spacing.xxl)
            .semantics { contentDescription = "ReiAnixRecoverableErrorState" },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(ReiAnixTokens.Spacing.md),
    ) {
        icon?.let {
            Icon(
                imageVector = it,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(ReiAnixTokens.Dimensions.touchTarget),
            )
        }
        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = message,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        ReiAnixPrimaryButton(
            text = retryLabel,
            onClick = onRetry,
        )
    }
}
