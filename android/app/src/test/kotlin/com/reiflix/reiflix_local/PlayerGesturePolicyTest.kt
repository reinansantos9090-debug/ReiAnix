package com.reiflix.reiflix_local

import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerGesturePolicyTest {
    @Test
    fun verticalDominatesOnlyWhenDirectionIsClear() {
        assertEquals(
            NativePlayerActivity.PlayerGesturePolicy.Direction.VERTICAL,
            NativePlayerActivity.PlayerGesturePolicy.direction(
                dx = 12f,
                dy = 180f,
                touchSlop = 12f,
            ),
        )
        assertEquals(
            NativePlayerActivity.PlayerGesturePolicy.Direction.NONE,
            NativePlayerActivity.PlayerGesturePolicy.direction(
                dx = 100f,
                dy = 90f,
                touchSlop = 12f,
            ),
        )
    }

    @Test
    fun movementInsideTouchSlopIsNotAgesture() {
        assertEquals(
            NativePlayerActivity.PlayerGesturePolicy.Direction.NONE,
            NativePlayerActivity.PlayerGesturePolicy.direction(
                dx = 5f,
                dy = 7f,
                touchSlop = 12f,
            ),
        )
    }



    @Test
    fun zoomIsClampedToConfiguredRange() {
        assertEquals(
            1f,
            NativePlayerActivity.PlayerGesturePolicy.clampZoom(0.5f, 1f, 3f),
            0.0001f,
        )
        assertEquals(
            2.25f,
            NativePlayerActivity.PlayerGesturePolicy.clampZoom(2.25f, 1f, 3f),
            0.0001f,
        )
        assertEquals(
            3f,
            NativePlayerActivity.PlayerGesturePolicy.clampZoom(4f, 1f, 3f),
            0.0001f,
        )
    }

    @Test
    fun panBoundsAndTranslationClampUseViewportMath() {
        val bounds = NativePlayerActivity.PlayerGesturePolicy.panBounds(
            displayedWidth = 1600f,
            displayedHeight = 1200f,
            viewportWidth = 1000f,
            viewportHeight = 800f,
        )
        assertEquals(300f, bounds.maxX, 0.0001f)
        assertEquals(200f, bounds.maxY, 0.0001f)

        val clamped = NativePlayerActivity.PlayerGesturePolicy.clampTranslation(
            translationX = 500f,
            translationY = -350f,
            bounds = bounds,
        )
        assertEquals(300f, clamped.first, 0.0001f)
        assertEquals(-200f, clamped.second, 0.0001f)
    }

    @Test
    fun seekTargetIsAlwaysWithinMediaDuration() {
        assertEquals(
            0L,
            NativePlayerActivity.PlayerGesturePolicy.seekTarget(
                currentPositionMs = 5_000L,
                deltaMs = -10_000L,
                durationMs = 20_000L,
            ),
        )
        assertEquals(
            15_000L,
            NativePlayerActivity.PlayerGesturePolicy.seekTarget(
                currentPositionMs = 5_000L,
                deltaMs = 10_000L,
                durationMs = 20_000L,
            ),
        )
        assertEquals(
            20_000L,
            NativePlayerActivity.PlayerGesturePolicy.seekTarget(
                currentPositionMs = 15_000L,
                deltaMs = 10_000L,
                durationMs = 20_000L,
            ),
        )
    }

    @Test
    fun movementAndVerticalDeltaUseRelativeViewportValues() {
        assertEquals(
            true,
            NativePlayerActivity.PlayerGesturePolicy.isMeaningfulMovement(
                dx = 13f,
                dy = 0f,
                touchSlop = 12f,
            ),
        )
        assertEquals(
            false,
            NativePlayerActivity.PlayerGesturePolicy.isMeaningfulMovement(
                dx = 5f,
                dy = 7f,
                touchSlop = 12f,
            ),
        )
        assertEquals(
            0.12f,
            NativePlayerActivity.PlayerGesturePolicy.verticalDeltaFraction(-400f, 2000),
            0.0001f,
        )
        assertEquals(
            -0.12f,
            NativePlayerActivity.PlayerGesturePolicy.verticalDeltaFraction(400f, 2000),
            0.0001f,
        )
    }

    @Test
    fun verticalDeltaMapsUpToPositiveAndDownToNegativeAdjustments() {
        assertEquals(
            0.06f,
            NativePlayerActivity.PlayerGesturePolicy.verticalDeltaFraction(-100f, 1000),
            0.0001f,
        )
        assertEquals(
            -0.06f,
            NativePlayerActivity.PlayerGesturePolicy.verticalDeltaFraction(100f, 1000),
            0.0001f,
        )
    }

    @Test
    fun touchZonesUseRelativeWidth() {
        assertEquals(
            NativePlayerActivity.PlayerGesturePolicy.Side.LEFT,
            NativePlayerActivity.PlayerGesturePolicy.side(100f, 1000),
        )
        assertEquals(
            NativePlayerActivity.PlayerGesturePolicy.Side.CENTER,
            NativePlayerActivity.PlayerGesturePolicy.side(500f, 1000),
        )
        assertEquals(
            NativePlayerActivity.PlayerGesturePolicy.Side.RIGHT,
            NativePlayerActivity.PlayerGesturePolicy.side(900f, 1000),
        )
    }
}
