package com.reiflix.reiflix_local.ui.shell

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
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
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.semantics
import com.reiflix.reiflix_local.ui.theme.ReiAnixTokens

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
        containerColor = androidx.compose.material3.MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets.safeDrawing.only(
            WindowInsetsSides.Top + WindowInsetsSides.Horizontal,
        ),
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
    NavigationBar(
        modifier = Modifier
            .fillMaxWidth()
            .semantics {
                isTraversalGroup = true
            },
        containerColor = ReiAnixTokens.Colors.surfaceNavigation,
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
                animationSpec = androidx.compose.animation.core.tween(
                    durationMillis = ReiAnixTokens.Motion.stateChangeMillis,
                ),
                label = "bottom-nav-color",
            )
            NavigationBarItem(
                selected = selected,
                onClick = { onDestinationClick(destination.route) },
                icon = {
                    Icon(
                        imageVector = if (selected) {
                            destination.selectedIcon
                        } else {
                            destination.unselectedIcon
                        },
                        contentDescription = null,
                        tint = contentColor,
                    )
                },
                label = {
                    Text(
                        text = destination.label,
                        color = contentColor,
                    )
                },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = MaterialTheme.colorScheme.primary,
                    selectedTextColor = MaterialTheme.colorScheme.primary,
                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                ),
            )
        }
    }
}
