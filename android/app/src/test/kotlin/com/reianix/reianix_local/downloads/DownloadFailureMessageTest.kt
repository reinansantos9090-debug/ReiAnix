package com.reianix.reianix_local.downloads

import org.junit.Assert.*
import org.junit.Test
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.io.IOException

class DownloadFailureMessageTest {
    @Test fun noBytesDueToDnsIsNotCalledSizeCalculation() {
        val text=DownloadFailureMessage.pending(UnknownHostException("cdn"),
            DownloadFailureMessage.Phase.CONNECTION,4,0)
        assertTrue(text.contains("DNS"))
        assertTrue(text.contains("Ainda não recebi dados"))
        assertTrue(text.contains("nova tentativa"))
        assertFalse(text.contains("tamanho"))
    }
    @Test fun destinationFailureIsNotMisdiagnosedAsNetwork() {
        val text=DownloadFailureMessage.pending(IOException("storage"),
            DownloadFailureMessage.Phase.DESTINATION,1,0)
        assertTrue(text.contains("destino"))
        assertTrue(text.contains("pasta"))
    }
    @Test fun timeoutAfterPartialDownloadStillAllowsRetry() {
        val text=DownloadFailureMessage.pending(SocketTimeoutException("timeout"),
            DownloadFailureMessage.Phase.TRANSFER,2,9_000_000)
        assertTrue(text.contains("servidor"))
        assertFalse(text.contains("Ainda não recebi dados"))
    }
}
