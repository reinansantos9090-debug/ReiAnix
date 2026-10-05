package com.reiflix.reiflix_local.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.Image
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.vector.ImageVector
import com.reiflix.reiflix_local.ui.artwork.ReiAnixEpisodeThumbnail
import com.reiflix.reiflix_local.ui.artwork.ReiAnixPoster
import com.reiflix.reiflix_local.ui.model.ReiAnixEpisodeUiModel
import com.reiflix.reiflix_local.ui.model.ReiAnixAnimeUiModel
import com.reiflix.reiflix_local.ui.theme.ReiAnixTokens

@Composable
fun ReiAnixSurface(
    modifier: Modifier = Modifier,
    shape: androidx.compose.ui.graphics.Shape = ReiAnixTokens.Shapes.card,
    color: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.surface,
    borderColor: androidx.compose.ui.graphics.Color? = null,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = shape,
        color = color,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = borderColor?.let {
            androidx.compose.foundation.BorderStroke(
                width = ReiAnixTokens.Dimensions.borderWidth,
                color = it,
            )
        },
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
        ) {
            content()
        }
    }
}

@Composable
fun ReiAnixDivider(
    modifier: Modifier = Modifier,
) {
    HorizontalDivider(
        modifier = modifier,
        thickness = ReiAnixTokens.Dimensions.dividerHeight,
        color = MaterialTheme.colorScheme.outlineVariant,
    )
}

@Composable
fun ReiAnixBadge(
    text: String,
    modifier: Modifier = Modifier,
    tone: ReiAnixBadgeTone = ReiAnixBadgeTone.Neutral,
) {
    val container = when (tone) {
        ReiAnixBadgeTone.Primary -> MaterialTheme.colorScheme.primaryContainer
        ReiAnixBadgeTone.Success -> ReiAnixTokens.Colors.success.copy(alpha = ReiAnixTokens.Colors.statusContainerAlpha)
        ReiAnixBadgeTone.Warning -> ReiAnixTokens.Colors.warning.copy(alpha = ReiAnixTokens.Colors.statusContainerAlpha)
        ReiAnixBadgeTone.Error -> ReiAnixTokens.Colors.errorContainer
        ReiAnixBadgeTone.Info -> ReiAnixTokens.Colors.secondaryContainer
        ReiAnixBadgeTone.Neutral -> ReiAnixTokens.Colors.surfaceRaised
    }
    val content = when (tone) {
        ReiAnixBadgeTone.Primary -> MaterialTheme.colorScheme.onPrimaryContainer
        ReiAnixBadgeTone.Success -> ReiAnixTokens.Colors.success
        ReiAnixBadgeTone.Warning -> ReiAnixTokens.Colors.warning
        ReiAnixBadgeTone.Error -> ReiAnixTokens.Colors.onErrorContainer
        ReiAnixBadgeTone.Info -> ReiAnixTokens.Colors.onSecondaryContainer
        ReiAnixBadgeTone.Neutral -> MaterialTheme.colorScheme.onSurface
    }
    Surface(
        modifier = modifier,
        shape = ReiAnixTokens.Shapes.chip,
        color = container,
        contentColor = content,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = content,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(
                horizontal = ReiAnixTokens.Spacing.sm,
                vertical = ReiAnixTokens.Spacing.xs,
            ),
        )
    }
}

enum class ReiAnixBadgeTone {
    Neutral,
    Primary,
    Success,
    Warning,
    Error,
    Info,
}

@Composable
fun ReiAnixCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier
            .heightIn(min = ReiAnixTokens.Dimensions.cardMinHeight)
            .semantics { },
        shape = ReiAnixTokens.Shapes.card,
        colors = CardDefaults.cardColors(
            containerColor = ReiAnixTokens.Colors.surfaceCard,
            contentColor = MaterialTheme.colorScheme.onSurface,
            disabledContainerColor = ReiAnixTokens.Colors.disabledSurface,
            disabledContentColor = ReiAnixTokens.Colors.textDisabled,
        ),
        border = androidx.compose.foundation.BorderStroke(
            width = ReiAnixTokens.Dimensions.borderWidth,
            color = MaterialTheme.colorScheme.outline.copy(alpha = ReiAnixTokens.Colors.subtleBorderAlpha),
        ),
        elevation = CardDefaults.cardElevation(
            defaultElevation = ReiAnixTokens.Elevation.card,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(ReiAnixTokens.Spacing.lg),
            content = content,
        )
    }
}

@Composable
fun ReiAnixPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leadingIcon: ImageVector? = null,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .heightIn(min = ReiAnixTokens.Dimensions.buttonMinHeight)
            .semantics { role = Role.Button },
        shape = ReiAnixTokens.Shapes.button,
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            disabledContainerColor = ReiAnixTokens.Colors.disabledSurface,
            disabledContentColor = ReiAnixTokens.Colors.textDisabled,
        ),
    ) {
        leadingIcon?.let {
            Icon(
                imageVector = it,
                contentDescription = null,
                modifier = Modifier.size(ReiAnixTokens.Dimensions.iconSmall),
            )
        }
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
fun ReiAnixSecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leadingIcon: ImageVector? = null,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .heightIn(min = ReiAnixTokens.Dimensions.buttonMinHeight)
            .semantics { role = Role.Button },
        shape = ReiAnixTokens.Shapes.button,
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = androidx.compose.ui.graphics.Color.Transparent,
            contentColor = MaterialTheme.colorScheme.onSurface,
            disabledContentColor = ReiAnixTokens.Colors.textDisabled,
        ),
        border = androidx.compose.foundation.BorderStroke(
            width = ReiAnixTokens.Dimensions.borderWidth,
            color = if (enabled) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.outlineVariant,
        ),
    ) {
        leadingIcon?.let {
            Icon(
                imageVector = it,
                contentDescription = null,
                modifier = Modifier.size(ReiAnixTokens.Dimensions.iconSmall),
            )
        }
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
fun ReiAnixCompactButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .heightIn(min = ReiAnixTokens.Dimensions.touchTarget)
            .semantics { role = Role.Button },
        shape = ReiAnixTokens.Shapes.button,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
fun ReiAnixTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: @Composable (() -> Unit)? = null,
    placeholder: @Composable (() -> Unit)? = null,
    singleLine: Boolean = true,
    enabled: Boolean = true,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = ReiAnixTokens.Dimensions.searchFieldHeight),
        label = label,
        placeholder = placeholder,
        singleLine = singleLine,
        enabled = enabled,
        shape = ReiAnixTokens.Shapes.textField,
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
            disabledContainerColor = ReiAnixTokens.Colors.disabledSurface,
            focusedBorderColor = MaterialTheme.colorScheme.primary,
            unfocusedBorderColor = MaterialTheme.colorScheme.outline,
            disabledBorderColor = MaterialTheme.colorScheme.outlineVariant,
            focusedTextColor = MaterialTheme.colorScheme.onSurface,
            unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
            disabledTextColor = ReiAnixTokens.Colors.textDisabled,
            focusedLabelColor = MaterialTheme.colorScheme.primary,
            unfocusedLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
            disabledLabelColor = ReiAnixTokens.Colors.textDisabled,
            focusedPlaceholderColor = MaterialTheme.colorScheme.onSurfaceVariant,
            unfocusedPlaceholderColor = MaterialTheme.colorScheme.onSurfaceVariant,
            disabledPlaceholderColor = ReiAnixTokens.Colors.textDisabled,
        ),
    )
}

@Composable
fun ReiAnixSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: @Composable (() -> Unit)? = null,
    leadingIcon: @Composable (() -> Unit)? = null,
    trailingIcon: @Composable (() -> Unit)? = null,
    singleLine: Boolean = true,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = ReiAnixTokens.Dimensions.searchFieldHeight),
        singleLine = singleLine,
        placeholder = placeholder,
        leadingIcon = leadingIcon,
        trailingIcon = trailingIcon,
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        shape = ReiAnixTokens.Shapes.textField,
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
            disabledContainerColor = ReiAnixTokens.Colors.disabledSurface,
            focusedBorderColor = MaterialTheme.colorScheme.primary,
            unfocusedBorderColor = MaterialTheme.colorScheme.outline,
            disabledBorderColor = MaterialTheme.colorScheme.outlineVariant,
            focusedTextColor = MaterialTheme.colorScheme.onSurface,
            unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
            disabledTextColor = ReiAnixTokens.Colors.textDisabled,
            focusedPlaceholderColor = MaterialTheme.colorScheme.onSurfaceVariant,
            unfocusedPlaceholderColor = MaterialTheme.colorScheme.onSurfaceVariant,
            disabledPlaceholderColor = ReiAnixTokens.Colors.textDisabled,
        ),
    )
}

@Composable
fun ReiAnixChip(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    selected: Boolean = false,
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .heightIn(min = ReiAnixTokens.Dimensions.chipMinHeight)
            .semantics { role = Role.Button },
        label = {
            Text(
                text = text,
                style = MaterialTheme.typography.labelMedium,
                color = when {
                    !enabled -> ReiAnixTokens.Colors.textDisabled
                    selected -> MaterialTheme.colorScheme.onPrimaryContainer
                    else -> MaterialTheme.colorScheme.onSurface
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        shape = ReiAnixTokens.Shapes.chip,
        colors = FilterChipDefaults.filterChipColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            labelColor = MaterialTheme.colorScheme.onSurface,
            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
            disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = ReiAnixTokens.Colors.disabledContainerAlpha),
            disabledLabelColor = ReiAnixTokens.Colors.textDisabled,
        ),
        border = FilterChipDefaults.filterChipBorder(
            enabled = enabled,
            selected = selected,
            borderColor = MaterialTheme.colorScheme.outline,
            selectedBorderColor = MaterialTheme.colorScheme.primary,
            disabledBorderColor = MaterialTheme.colorScheme.outlineVariant,
        ),
    )
}

@Composable
fun ReiAnixSectionTitle(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (!subtitle.isNullOrBlank()) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = ReiAnixTokens.Spacing.xs),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
fun ReiAnixScreenTitle(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(
            ReiAnixTokens.Dimensions.sectionTitleGap,
        ),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.headlineLarge,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (!subtitle.isNullOrBlank()) {
            ReiAnixSecondaryText(
                text = subtitle,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
fun ReiAnixMetadata(
    text: String,
    modifier: Modifier = Modifier,
    maxLines: Int = 1,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
fun ReiAnixSecondaryText(
    text: String,
    modifier: Modifier = Modifier,
    maxLines: Int = 2,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
fun ReiAnixIconActionButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .size(ReiAnixTokens.Dimensions.touchTarget)
            .semantics {
                this.contentDescription = contentDescription
                role = Role.Button
            },
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (enabled) {
                MaterialTheme.colorScheme.onSurface
            } else {
                ReiAnixTokens.Colors.textDisabled
            },
        )
    }
}

@Composable
fun ReiAnixAnimeCard(
    anime: ReiAnixAnimeUiModel,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    maxDimensionPx: Int = 512,
    bottomBadgeText: String? = null,
) {
    ReiAnixAnimeCard(
        title = anime.title,
        artworkPath = anime.artwork?.localPath,
        metadata = buildList {
            anime.year?.let { add(it.toString()) }
            anime.episodeCountLabel.takeIf { anime.availableContentCount > 0 }?.let(::add)
        },
        progress = anime.contentEpisodes
            .firstOrNull { it.progressFraction > 0f && !it.isCompleted }
            ?.progressFraction,
        favorite = anime.favorite,
        watched = anime.contentEpisodes.any { it.isWatched },
        watching = anime.isWatching,
        completed = anime.isCompleted,
        modifier = modifier,
        onClick = onClick,
        maxDimensionPx = maxDimensionPx,
        artworkIdentity = anime.stableKey,
        bottomBadgeText = bottomBadgeText,
    )
}

@Composable
fun ReiAnixAnimeCard(
    title: String,
    artworkPath: String?,
    metadata: List<String> = emptyList(),
    progress: Float? = null,
    favorite: Boolean = false,
    watching: Boolean = false,
    watched: Boolean = false,
    completed: Boolean = false,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    onFavoriteClick: (() -> Unit)? = null,
    maxDimensionPx: Int = 512,
    artworkIdentity: String? = null,
    bottomBadgeText: String? = null,
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (onClick != null) {
                    Modifier
                        .clickable(
                            enabled = true,
                            onClick = onClick,
                        )
                        .semantics {
                            role = Role.Button
                            this.contentDescription = "Abrir $title"
                        }
                } else {
                    Modifier.semantics {
                        this.contentDescription = title
                    }
                },
            ),
        shape = ReiAnixTokens.Shapes.card,
        colors = CardDefaults.cardColors(
            containerColor = ReiAnixTokens.Colors.surfaceCard,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = ReiAnixTokens.Elevation.card),
    ) {
        Column {
            Box {
                ReiAnixPoster(
                    localPath = artworkPath,
                    contentDescription = title,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(ReiAnixTokens.Dimensions.posterAspectRatio)
                        .clip(ReiAnixTokens.Shapes.artwork),
                    identity = artworkIdentity,
                    maxDimensionPx = maxDimensionPx,
                )
                if (favorite || watching || watched || completed) {
                    Text(
                        text = when {
                            completed -> "Concluído"
                            watching -> "Assistindo"
                            watched -> "Assistido"
                            else -> "Minha lista"
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(ReiAnixTokens.Spacing.sm)
                            .background(
                                color = ReiAnixTokens.Colors.overlay,
                                shape = ReiAnixTokens.Shapes.chip,
                            )
                            .padding(
                                horizontal = ReiAnixTokens.Spacing.sm,
                                vertical = ReiAnixTokens.Spacing.xs,
                            ),
                    )
                }
                onFavoriteClick?.let { favoriteAction ->
                    androidx.compose.material3.IconButton(
                        onClick = favoriteAction,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .size(ReiAnixTokens.Dimensions.touchTarget)
                            .semantics {
                                this.contentDescription = if (favorite) {
                                    "Remover da Minha Lista"
                                } else {
                                    "Adicionar à Minha Lista"
                                }
                            },
                    ) {
                        Icon(
                            imageVector = if (favorite) {
                                Icons.Filled.Favorite
                            } else {
                                Icons.Filled.FavoriteBorder
                            },
                            contentDescription = null,
                            tint = if (favorite) {
                                ReiAnixTokens.Colors.warning
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                        )
                    }
                }
                if (!bottomBadgeText.isNullOrBlank()) {
                    Surface(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = ReiAnixTokens.Spacing.md),
                        shape = ReiAnixTokens.Shapes.chip,
                        color = MaterialTheme.colorScheme.surface.copy(alpha = ReiAnixTokens.Colors.surfaceOverlayAlpha),
                        contentColor = MaterialTheme.colorScheme.onSurface,
                    ) {
                        Text(
                            text = bottomBadgeText,
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(
                                horizontal = ReiAnixTokens.Spacing.md,
                                vertical = ReiAnixTokens.Spacing.xs,
                            ),
                        )
                    }
                }
                ReiAnixProgressIndicator(
                    progress = progress ?: 0f,
                    visible = progress != null && progress > 0f,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(horizontal = ReiAnixTokens.Spacing.sm),
                )
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        horizontal = ReiAnixTokens.Spacing.sm,
                        vertical = ReiAnixTokens.Spacing.md,
                    ),
                verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(
                    ReiAnixTokens.Spacing.xs,
                ),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (metadata.isNotEmpty()) {
                    ReiAnixMetadata(text = metadata.joinToString(" • "))
                }
            }
        }
    }
}

@Composable
fun ReiAnixEpisodeCard(
    episode: ReiAnixEpisodeUiModel,
    modifier: Modifier = Modifier,
    onPlay: (() -> Unit)? = null,
    trailingContent: (@Composable () -> Unit)? = null,
) {
    val playable = episode.isPlayable && onPlay != null
    Card(
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (playable) {
                    Modifier.clickable(
                        onClick = { onPlay?.invoke() },
                    )
                } else {
                    Modifier
                },
            )
            .semantics {
                this.contentDescription = episode.displayTitle + " • " + episode.playbackActionLabel
                if (playable) role = Role.Button
            },
        shape = ReiAnixTokens.Shapes.card,
        colors = CardDefaults.cardColors(
            containerColor = ReiAnixTokens.Colors.surfaceCard,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = ReiAnixTokens.Elevation.card),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(ReiAnixTokens.Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .width(ReiAnixTokens.Dimensions.episodeThumbnailWidth)
                    .height(ReiAnixTokens.Dimensions.episodeThumbnailHeight)
                    .clip(ReiAnixTokens.Shapes.small),
                contentAlignment = Alignment.Center,
            ) {
                ReiAnixEpisodeThumbnail(
                    localPath = episode.artwork?.localPath,
                    contentDescription = episode.displayTitle,
                    modifier = Modifier.fillMaxSize(),
                    identity = episode.stableKey,
                )
                episode.durationSeconds
                    ?.takeIf { it.isFinite() && it >= 0.0 }
                    ?.let { duration ->
                        Surface(
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(ReiAnixTokens.Spacing.xs),
                            shape = ReiAnixTokens.Shapes.chip,
                            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.86f),
                            contentColor = MaterialTheme.colorScheme.onSurface,
                        ) {
                            Text(
                                text = formatDurationLabel(duration),
                                style = MaterialTheme.typography.labelMedium,
                                maxLines = 1,
                                modifier = Modifier.padding(
                                    horizontal = ReiAnixTokens.Spacing.xs,
                                    vertical = 2.dp,
                                ),
                            )
                        }
                    }
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = ReiAnixTokens.Spacing.md),
                verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(
                    ReiAnixTokens.Spacing.xs,
                ),
            ) {
                Text(
                    text = episode.displayTitle,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                ReiAnixMetadata(
                    text = buildString {
                        episode.number?.let {
                            append(
                                if (it % 1.0 == 0.0) {
                                    "E" + it.toInt().toString().padStart(2, '0')
                                } else {
                                    "E" + it.toString()
                                },
                            )
                        }
                        episode.durationSeconds?.takeIf { it >= 0 }?.let {
                            if (isNotEmpty()) append(" • ")
                            append(formatDurationLabel(it))
                        }
                        episode.progressPercent?.let {
                            if (isNotEmpty()) append(" • ")
                            append("$it%")
                        }
                    }.ifBlank { "Episódio" },
                )
                ReiAnixBadge(
                    text = episode.playbackActionLabel,
                    tone = when {
                        !episode.isPlayable -> ReiAnixBadgeTone.Error
                        episode.isCompleted -> ReiAnixBadgeTone.Success
                        episode.consumptionState == com.reiflix.reiflix_local.ui.model.ReiAnixConsumptionState.IN_PROGRESS -> ReiAnixBadgeTone.Primary
                        else -> ReiAnixBadgeTone.Neutral
                    },
                    modifier = Modifier.semantics {
                        contentDescription = "Ação: " + episode.playbackActionLabel
                    },
                )
                ReiAnixProgressIndicator(
                    progress = episode.progressFraction,
                    visible = episode.progressFraction > 0f,
                )
                Text(
                    text = if (episode.isPlayable) "Episódio local" else "Arquivo indisponível",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (episode.isPlayable) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Box(
                modifier = Modifier.width(ReiAnixTokens.Dimensions.touchTarget),
                contentAlignment = Alignment.Center,
            ) {
                if (trailingContent != null) {
                    trailingContent()
                } else if (playable) {
                    Icon(
                        imageVector = Icons.Filled.ArrowForward,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(ReiAnixTokens.Dimensions.iconMedium),
                    )
                }
            }
        }
    }
}

private fun formatDurationLabel(seconds: Double): String {
    val total = seconds.toLong().coerceAtLeast(0L)
    val hours = total / 3600L
    val minutes = (total % 3600L) / 60L
    val rest = total % 60L
    return if (hours > 0L) {
        "%d:%02d:%02d".format(java.util.Locale.ROOT, hours, minutes, rest)
    } else {
        "%d:%02d".format(java.util.Locale.ROOT, minutes, rest)
    }
}

@Composable
fun ReiAnixSettingCard(
    title: String,
    description: String? = null,
    icon: ImageVector? = null,
    value: String? = null,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    trailingContent: (@Composable () -> Unit)? = null,
) {
    Card(
        onClick = onClick ?: {},
        enabled = onClick != null && enabled,
        shape = ReiAnixTokens.Shapes.card,
        colors = CardDefaults.cardColors(
            containerColor = ReiAnixTokens.Colors.surfaceCard,
            disabledContainerColor = ReiAnixTokens.Colors.disabledSurface,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .semantics {
                this.contentDescription = if (description.isNullOrBlank()) title else "$title. $description"
                if (onClick != null) role = Role.Button
            },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = ReiAnixTokens.Spacing.lg,
                    vertical = ReiAnixTokens.Spacing.md,
                ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(
                ReiAnixTokens.Spacing.md,
            ),
        ) {
            icon?.let {
                Icon(
                    imageVector = it,
                    contentDescription = null,
                    tint = if (enabled) MaterialTheme.colorScheme.onSurface else ReiAnixTokens.Colors.textDisabled,
                    modifier = Modifier.size(ReiAnixTokens.Dimensions.touchTarget),
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = if (enabled) MaterialTheme.colorScheme.onSurface else ReiAnixTokens.Colors.textDisabled,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                description?.takeIf { it.isNotBlank() }?.let {
                    ReiAnixSecondaryText(text = it, maxLines = 2)
                }
                value?.takeIf { it.isNotBlank() }?.let {
                    ReiAnixMetadata(text = it)
                }
            }
            trailingContent?.invoke()
        }
    }
}

@Composable
fun ReiAnixArtwork(
    painter: Painter?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    shape: androidx.compose.ui.graphics.Shape = ReiAnixTokens.Shapes.artwork,
) {
    Box(
        modifier = modifier
            .aspectRatio(0.7f)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        if (painter != null) {
            Image(
                painter = painter,
                contentDescription = contentDescription,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(shape),
                contentScale = contentScale,
            )
        } else {
            Text(
                text = "Sem arte",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
fun ReiAnixProgressIndicator(
    progress: Float,
    modifier: Modifier = Modifier,
    visible: Boolean = true,
) {
    val clampedProgress = if (progress.isFinite()) {
        progress.coerceIn(0f, 1f)
    } else {
        0f
    }
    LinearProgressIndicator(
        progress = { clampedProgress },
        modifier = modifier
            .fillMaxWidth()
            .height(ReiAnixTokens.Dimensions.progressHeight),
        color = if (visible) MaterialTheme.colorScheme.primary else androidx.compose.ui.graphics.Color.Transparent,
        trackColor = MaterialTheme.colorScheme.surfaceVariant,
    )
}

@Composable
fun ReiAnixScreen(
    modifier: Modifier = Modifier,
    applySafeDrawing: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (applySafeDrawing) Modifier.safeDrawingPadding() else Modifier,
            )
            .padding(
                horizontal = ReiAnixTokens.Dimensions.screenHorizontalPadding,
                vertical = ReiAnixTokens.Dimensions.screenTopPadding,
            ),
        content = content,
    )
}
