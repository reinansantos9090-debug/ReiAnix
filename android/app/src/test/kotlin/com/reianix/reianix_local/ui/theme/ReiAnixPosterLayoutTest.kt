package com.reianix.reianix_local.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReiAnixPosterLayoutTest {
    private val compact = ReiAnixResponsiveMetrics.compactDefaults()

    @Test
    fun balancedGridDensityKeepsTheSelectedPosterSize() {
        for (size in 0..16) {
            assertEquals(
                compact.posterCardWidth(size),
                compact.libraryPosterGridMinWidth(size, "medium"),
            )
        }
    }

    @Test
    fun densityPresetsActuallyChangeLibraryColumnWidth() {
        for (size in listOf(0, 5, 8, 16)) {
            val fewer = compact.libraryPosterGridMinWidth(size, "small")
            val balanced = compact.libraryPosterGridMinWidth(size, "medium")
            val more = compact.libraryPosterGridMinWidth(size, "large")
            assertTrue("More cards must have a smaller minimum width", more < balanced)
            assertTrue("Fewer cards must have a larger minimum width", balanced < fewer)
        }
    }

    @Test
    fun densityFallsBackToBalancedForUnknownValues() {
        assertEquals(
            compact.libraryPosterGridMinWidth(8, "medium"),
            compact.libraryPosterGridMinWidth(8, "unknown"),
        )
        assertEquals(
            compact.libraryPosterGridMinWidth(8, "small"),
            compact.libraryPosterGridMinWidth(8, " SMALL "),
        )
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
