package com.reianix.reianix_local.scanner

import android.app.Application
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class DefaultReiAnixFolderScannerTest {
    @Test
    fun recognizesOnlyBuiltInDownloadDirectoryAndItsChildren() {
        assertTrue(DefaultReiAnixFolderScanner.isInFolder("Download/ReiAnix/"))
        assertTrue(DefaultReiAnixFolderScanner.isInFolder("download/reianix/Anime/Season 2/"))
        assertTrue(DefaultReiAnixFolderScanner.isInFolder("Download\\ReiAnix\\Anime"))
        assertFalse(DefaultReiAnixFolderScanner.isInFolder("Download/ReiAnixOther/"))
        assertFalse(DefaultReiAnixFolderScanner.isInFolder("Download/"))
        assertFalse(DefaultReiAnixFolderScanner.isInFolder("Movies/ReiAnix/"))
        assertFalse(DefaultReiAnixFolderScanner.isInFolder(""))
    }
}
