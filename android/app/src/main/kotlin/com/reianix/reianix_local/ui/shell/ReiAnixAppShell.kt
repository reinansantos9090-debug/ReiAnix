package com.reianix.reianix_local.ui.shell

import com.reianix.reianix_local.ui.theme.reiAnixContentBackground

import com.reianix.reianix_local.ui.theme.reiAnixScreenBackground

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.snap
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarDefaults
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import com.reianix.reianix_local.ui.motion.ReiAnixMotionPolicy
import com.reianix.reianix_local.ui.theme.ReiAnixTokens

/**
 * Global Compose chrome for the ReiAnix incremental native cutover.
 *
 * The shell owns presentation/orchestration only: scaffold, bottom navigation
 * and the system-safe content boundary. Domain state and route definitions stay
 * outside this component.
 */
data class ReiAnixBottomNavDestination(
    val route: String,
    val label: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector = selectedIcon,
    val contentDescription: String = label,
)

/** Keep only the horizontal cutout clearance on the shared viewport. */
internal fun reiAnixAppContentInsets(safeInsets: WindowInsets): WindowInsets =
    safeInsets.only(WindowInsetsSides.Horizontal)

@Composable
fun ReiAnixAppShell(
    selectedRoute: String?,
    bottomDestinations: List<ReiAnixBottomNavDestination>,
    onBottomDestinationClick: (String) -> Unit,
    navigationContent: @Composable (PaddingValues) -> Unit,
    modifier: Modifier = Modifier,
    showBottomNavigation: Boolean = true,
) {
    Scaffold(
        modifier = modifier,
        containerColor = reiAnixScreenBackground(),
        contentColor = MaterialTheme.colorScheme.onBackground,
        // Every destination owns its top inset at the actual header/scrolling
        // content. Do not reserve a permanent black strip in the viewport.
        contentWindowInsets = if (showBottomNavigation) {
            reiAnixAppContentInsets(WindowInsets.safeDrawing)
        } else {
            // No app bottom bar to consume the visible Android navigation bar.
            WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom)
        },
        bottomBar = {
            if (showBottomNavigation) {
                ReiAnixBottomNavigation(
                    selectedRoute = selectedRoute,
                    destinations = bottomDestinations,
                    onDestinationClick = onBottomDestinationClick,
                )
            }
        },
    ) { innerPadding ->
        navigationContent(innerPadding)
    }
}

@Composable
private fun ReiAnixBottomNavigation(
    selectedRoute: String?,
    destinations: List<ReiAnixBottomNavDestination>,
    onDestinationClick: (String) -> Unit,
) {
    val navigationContainerColor =
        if (MaterialTheme.colorScheme.background == ReiAnixTokens.Colors.background) {
            ReiAnixTokens.Colors.surfaceNavigation
        } else {
            MaterialTheme.colorScheme.surface
        }

    NavigationBar(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = ReiAnixTokens.Dimensions.bottomNavigationMinHeight)
            .semantics {
                isTraversalGroup = true
            },
        containerColor = reiAnixContentBackground(navigationContainerColor),
        contentColor = MaterialTheme.colorScheme.onSurface,
        tonalElevation = ReiAnixTokens.Elevation.none,
        windowInsets = NavigationBarDefaults.windowInsets,
    ) {
        destinations.forEach { destination ->
            val selected = selectedRoute == destination.route
            val contentColor by animateColorAsState(
                targetValue = if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                animationSpec = if (ReiAnixMotionPolicy.systemAnimationsEnabled()) {
                    androidx.compose.animation.core.tween(
                        durationMillis = ReiAnixTokens.Motion.stateChangeMillis,
                    )
                } else {
                    snap()
                },
                label = "bottom-nav-color",
            )
            NavigationBarItem(
                selected = selected,
                onClick = { onDestinationClick(destination.route) },
                modifier = Modifier.semantics {
                    contentDescription = destination.contentDescription
                    stateDescription = if (selected) "Selecionado" else "Não selecionado"
                    role = Role.Tab
                },
                icon = {
                    Icon(
                        imageVector = if (selected) {
                            destination.selectedIcon
                        } else {
                            destination.unselectedIcon
                        },
                        contentDescription = null,
                        tint = contentColor,
                        modifier = Modifier.size(ReiAnixTokens.Dimensions.iconMedium),
                    )
                },
                label = {
                    Text(
                        text = destination.label,
                        style = ReiAnixTokens.TypographyTokens.navigationLabel,
                        color = contentColor,
                        maxLines = 1,
                    )
                },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = MaterialTheme.colorScheme.primary,
                    selectedTextColor = MaterialTheme.colorScheme.primary,
                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    indicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                ),
            )
        }
    }
}
