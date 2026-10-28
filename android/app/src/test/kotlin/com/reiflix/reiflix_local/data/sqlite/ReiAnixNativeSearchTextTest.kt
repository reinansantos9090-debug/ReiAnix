package com.reiflix.reiflix_local.data.sqlite

import org.junit.Assert.assertEquals
import org.junit.Test

class ReiAnixNativeSearchTextTest {
    @Test
    fun nullableMetadataTextDoesNotAbortLibrarySearchProjection() {
        // Schema v29 permits absent optional titles/aliases and genre/search text.
        // Android Cursor.getString() returns null for those SQL NULL cells.
        assertEquals("", normalizeNativeSearchText(null))
        assertEquals("", normalizeNativeSearchText(""))
        assertEquals("cafe naruto", normalizeNativeSearchText("  Café—NARUTO! "))
    }
}
