package com.reiflix.reiflix_local.ui.scroll

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import org.junit.Assert.assertEquals
import org.junit.Test

class ReiAnixScrollAwareHeaderStateTest {

    @Test
    fun returningToTopDoesNotSnapPartiallyCollapsedHeaderOpen() {
        val state = ReiAnixScrollAwareHeaderState()
        state.updateMeasuredHeight(120)

        state.nestedScrollConnection.onPreScroll(
            Offset(0f, -65f),
            NestedScrollSource.UserInput,
        )
        assertEquals(55f, state.visibleHeightPx, 0.01f)

        state.updateContentAtTop(false)
        state.updateContentAtTop(true)

        // A list returning to offset zero must not move the header by itself.
        assertEquals(55f, state.visibleHeightPx, 0.01f)

        val consumed = state.nestedScrollConnection.onPreScroll(
            Offset(0f, 20f),
            NestedScrollSource.UserInput,
        )
        assertEquals(20f, consumed.y, 0.01f)
        assertEquals(75f, state.visibleHeightPx, 0.01f)
    }

    @Test
    fun collapsedHeaderWaitsUntilListIsAtTopBeforeExpanding() {
        val state = ReiAnixScrollAwareHeaderState()
        state.updateMeasuredHeight(120)
        state.nestedScrollConnection.onPreScroll(
            Offset(0f, -120f),
            NestedScrollSource.UserInput,
        )
        state.updateContentAtTop(false)

        val consumedAwayFromTop = state.nestedScrollConnection.onPreScroll(
            Offset(0f, 40f),
            NestedScrollSource.UserInput,
        )
        assertEquals(Offset.Zero, consumedAwayFromTop)
        assertEquals(0f, state.visibleHeightPx, 0.01f)

        state.updateContentAtTop(true)
        val consumedAtTop = state.nestedScrollConnection.onPreScroll(
            Offset(0f, 40f),
            NestedScrollSource.UserInput,
        )
        assertEquals(40f, consumedAtTop.y, 0.01f)
        assertEquals(40f, state.visibleHeightPx, 0.01f)
    }

    @Test
    fun headerConsumesOnlyDistanceRequiredToFullyCollapseOrExpand() {
        val state = ReiAnixScrollAwareHeaderState()
        state.updateMeasuredHeight(120)

        val collapse = state.nestedScrollConnection.onPreScroll(
            Offset(0f, -200f),
            NestedScrollSource.UserInput,
        )
        assertEquals(-120f, collapse.y, 0.01f)
        assertEquals(0f, state.visibleHeightPx, 0.01f)

        val expand = state.nestedScrollConnection.onPreScroll(
            Offset(0f, 200f),
            NestedScrollSource.UserInput,
        )
        assertEquals(120f, expand.y, 0.01f)
        assertEquals(120f, state.visibleHeightPx, 0.01f)
    }
}
