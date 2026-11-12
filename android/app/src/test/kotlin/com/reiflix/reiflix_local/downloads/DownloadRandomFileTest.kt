package com.reiflix.reiflix_local.downloads

import android.os.ParcelFileDescriptor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DownloadRandomFileTest {
    @Test fun publicDescriptorSupportsPauseAndSeekResumeWithoutRewritingEarlierBytes() {
        val file=File.createTempFile("reianix-public-part",".tmp")
        try {
            val descriptor=ParcelFileDescriptor.open(file,ParcelFileDescriptor.MODE_READ_WRITE)
            DownloadRandomFile.openDescriptor(descriptor,true).use {
                it.write("abcdefgh".toByteArray())
                it.sync()
            }
            val resumed=ParcelFileDescriptor.open(file,ParcelFileDescriptor.MODE_READ_WRITE)
            DownloadRandomFile.openDescriptor(resumed,true).use {
                assertEquals(8L,it.length())
                it.setLength(5L)
                it.seek(5L)
                it.write("XYZ".toByteArray())
                it.sync()
            }
            assertArrayEquals("abcdeXYZ".toByteArray(),file.readBytes())
            val reader=ParcelFileDescriptor.open(file,ParcelFileDescriptor.MODE_READ_ONLY)
            DownloadRandomFile.openDescriptor(reader,false).use {
                it.seek(5L)
                val bytes=ByteArray(3)
                assertEquals(3,it.read(bytes,0,3))
                assertArrayEquals("XYZ".toByteArray(),bytes)
            }
        } finally {file.delete()}
    }

    @Test fun legacyPrivateFileRemainsReadableForAlreadyStartedDownloads() {
        val file=File.createTempFile("reianix-legacy-part",".tmp")
        try {
            DownloadRandomFile.openLegacy(file,true).use {
                it.setLength(0)
                it.write("legacy".toByteArray())
                it.sync()
            }
            DownloadRandomFile.openLegacy(file,false).use {
                assertEquals(6L,it.length())
                val bytes=ByteArray(6)
                assertEquals(6,it.read(bytes,0,6))
                assertArrayEquals("legacy".toByteArray(),bytes)
            }
        } finally {file.delete()}
    }
}
