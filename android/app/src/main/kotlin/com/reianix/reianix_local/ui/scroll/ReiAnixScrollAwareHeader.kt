package com.reianix.reianix_local.ui.scroll

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import kotlin.math.roundToInt

/**
 * Keeps a custom top chrome synchronized with the user's vertical scroll gesture.
 *
 * Downward content movement collapses the header. Upward movement can expand it
 * only while the associated list/grid is actually at its first item and offset zero.
 * The connection consumes only the drag distance needed to change header height.
 */
@Stable
class ReiAnixScrollAwareHeaderState internal constructor() {
    private var measuredHeightPx by mutableIntStateOf(0)
    private var contentAtTop by mutableStateOf(true)

    var visibleHeightPx by mutableFloatStateOf(0f)
        private set

    val collapseOffsetPx: Float
        get() = (measuredHeightPx - visibleHeightPx).coerceAtLeast(0f)

    val nestedScrollConnection: NestedScrollConnection = object : NestedScrollConnection {
        override fun onPreScroll(
            available: Offset,
            source: NestedScrollSource,
        ): Offset {
            val totalHeight = measuredHeightPx.toFloat()
            if (totalHeight <= 0f || available.y == 0f) return Offset.Zero
            if (available.y > 0f && !contentAtTop) return Offset.Zero

            val previousHeight = visibleHeightPx
            val boundedHeight = (previousHeight + available.y).coerceIn(0f, totalHeight)
            val nextHeight = when {
                boundedHeight <= 1f -> 0f
                totalHeight - boundedHeight <= 1f -> totalHeight
                else -> boundedHeight
            }
            val consumedY = nextHeight - previousHeight
            if (consumedY == 0f) return Offset.Zero

            visibleHeightPx = nextHeight
            return Offset(x = 0f, y = consumedY)
        }
    }

    internal fun updateContentAtTop(isAtTop: Boolean) {
        // Returning to the first item must not expand the header instantly:
        // doing so shifts the list during a scroll and looks like a "jump".
        // The next downward gesture expands it smoothly via onPreScroll.
        contentAtTop = isAtTop
    }

    internal fun updateMeasuredHeight(heightPx: Int) {
        if (heightPx <= 0 || heightPx == measuredHeightPx) return

        val previousHeight = measuredHeightPx.toFloat()
        val collapsedFraction = if (previousHeight > 0f) {
            ((previousHeight - visibleHeightPx) / previousHeight).coerceIn(0f, 1f)
        } else {
            0f
        }

        measuredHeightPx = heightPx
        visibleHeightPx = heightPx * (1f - collapsedFraction)
    }
}

@Composable
fun rememberReiAnixScrollAwareHeaderState(): ReiAnixScrollAwareHeaderState =
    remember { ReiAnixScrollAwareHeaderState() }

/**
 * Measures its content at its natural height while exposing only the portion
 * that remains visible. The occupied layout height shrinks with the drag, so
 * the list/grid below immediately takes over the released space.
 */
@Composable
fun ReiAnixScrollAwareHeader(
    state: ReiAnixScrollAwareHeaderState,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val visibleHeight = with(density) { state.visibleHeightPx.toDp() }
    val collapseOffsetPx = state.collapseOffsetPx

    SubcomposeLayout(
        modifier = modifier
            .fillMaxWidth()
            .height(visibleHeight)
            .clipToBounds(),
    ) { constraints ->
        val header = subcompose("scroll-aware-header") {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .onSizeChanged { size -> state.updateMeasuredHeight(size.height) },
            ) {
                content()
            }
        }.single().measure(
            constraints.copy(
                minHeight = 0,
                maxHeight = Constraints.Infinity,
            ),
        )

        val layoutHeight = visibleHeight.roundToPx().coerceAtMost(constraints.maxHeight)
        layout(constraints.maxWidth, layoutHeight) {
            header.placeRelative(
                x = 0,
                y = -collapseOffsetPx.roundToInt(),
            )
        }
    }
}
