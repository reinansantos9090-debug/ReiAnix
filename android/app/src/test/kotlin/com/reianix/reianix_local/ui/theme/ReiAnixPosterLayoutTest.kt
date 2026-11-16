package com.reianix.reianix_local.ui.theme

import org.junit.Assert.assertTrue
import org.junit.Test

class ReiAnixPosterLayoutTest {
    private val compact = ReiAnixResponsiveMetrics.compactDefaults()

    @Test
    fun posterSizeAloneDeterminesTheGridCardWidth() {
        for (widthClass in ReiAnixWindowWidthClass.values()) {
            val metrics = compact.copy(widthClass = widthClass)
            var previous = metrics.posterCardWidth(0)
            for (size in 1..16) {
                val current = metrics.posterCardWidth(size)
                assertTrue("Poster sizes must increase on $widthClass", current > previous)
                previous = current
            }
        }
    }

    @Test
    fun cardSizeChoicesStayDistinctAcrossPhoneAndTabletWidths() {
        for (widthClass in ReiAnixWindowWidthClass.values()) {
            val metrics = compact.copy(widthClass = widthClass)
            val small = metrics.gridItemMinWidth("small")
            val medium = metrics.gridItemMinWidth("medium")
            val large = metrics.gridItemMinWidth("large")
            assertTrue("Small cards should be narrower on $widthClass", small < medium)
            assertTrue("Large cards should be wider on $widthClass", medium < large)
        }
    }
}
