package com.reianix.reianix_local.downloads

import org.junit.Assert.*
import org.junit.Test

class BrowserFilterArgumentsTest {
    @Test fun acceptsOnlyQuotedScriptletArguments() {
        assertEquals(listOf("prevent-window-open", "", "1"),
            BrowserFilterLists.parseArguments("'prevent-window-open', '', '1'"))
        assertNull(BrowserFilterLists.parseArguments("'name', alert(document.cookie)"))
        assertNull(BrowserFilterLists.parseArguments("'unterminated"))
        assertNull(BrowserFilterLists.parseArguments("window.location"))
    }
}
