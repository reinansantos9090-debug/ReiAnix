package com.reiflix.reiflix_local

import com.reiflix.reiflix_local.scanner.ScanResultClassifier
import org.junit.Assert.assertEquals
import org.junit.Test

class ScanResultClassifierTest {
    @Test fun broadStorageCompletedIsSuccess() {
        assertEquals("COMPLETED", ScanResultClassifier.classifyBroadStorage("completed", false, false))
    }

    @Test fun broadStorageEmptyCompleteIsPreserved() {
        assertEquals("EMPTY_COMPLETE", ScanResultClassifier.classifyBroadStorage("EMPTY_COMPLETE", false, false))
    }

    @Test fun broadStoragePartialIsPartial() {
        assertEquals("PARTIAL", ScanResultClassifier.classifyBroadStorage("partial", false, false))
    }

    @Test fun broadStorageCancellationIsCancelled() {
        assertEquals("CANCELLED", ScanResultClassifier.classifyBroadStorage("cancelled", true, false))
        assertEquals("CANCELLED", ScanResultClassifier.classifyBroadStorage("completed", true, false))
    }

    @Test fun broadStorageBlockedIsNeverSuccessEvenWithGenericFlags() {
        assertEquals("FAILED", ScanResultClassifier.classifyBroadStorage("BLOCKED", false, false))
        assertEquals("FAILED", ScanResultClassifier.classifyBroadStorage("BLOCKED", false, true))
        assertEquals("FAILED", ScanResultClassifier.classifyBroadStorage("BLOCKED", true, false))
    }

    @Test fun broadStorageKnownFailuresAndUnknownStatesFailClosed() {
        listOf("FAILED", "UNAVAILABLE", "REVOKED", "DENIED", "ERROR", "MYSTERY", "").forEach { status ->
            assertEquals("broad status $status", "FAILED",
                ScanResultClassifier.classifyBroadStorage(status, false, false))
        }
        assertEquals("FAILED", ScanResultClassifier.classifyBroadStorage(null, false, false))
    }

    @Test fun broadStoragePartialFlagCannotMakeUnknownStateSuccessful() {
        assertEquals("FAILED", ScanResultClassifier.classifyBroadStorage("NEW_FUTURE_STATE", false, true))
    }

    @Test fun mediaStoreCompletedIsSuccess() {
        assertEquals("COMPLETED", ScanResultClassifier.classifyMediaStore("completed", false, false))
    }

    @Test fun mediaStoreEmptyCompleteIsPreserved() {
        assertEquals("EMPTY_COMPLETE", ScanResultClassifier.classifyMediaStore("empty_complete", false, false))
    }

    @Test fun mediaStorePartialIsPartial() {
        assertEquals("PARTIAL", ScanResultClassifier.classifyMediaStore("partial", false, false))
        assertEquals("PARTIAL", ScanResultClassifier.classifyMediaStore("PARTIAL", false, true))
    }

    @Test fun mediaStoreWaitingIsPartial() {
        assertEquals("PARTIAL", ScanResultClassifier.classifyMediaStore("WAITING_FOR_MEDIASTORE", false, false))
    }

    @Test fun mediaStoreCancellationIsCancelled() {
        assertEquals("CANCELLED", ScanResultClassifier.classifyMediaStore("cancelled", true, false))
        assertEquals("CANCELLED", ScanResultClassifier.classifyMediaStore("completed", true, false))
    }

    @Test fun mediaStoreBlockedIsNeverSuccessEvenWithGenericFlags() {
        assertEquals("FAILED", ScanResultClassifier.classifyMediaStore("BLOCKED", false, false))
        assertEquals("FAILED", ScanResultClassifier.classifyMediaStore("BLOCKED", false, true))
        assertEquals("FAILED", ScanResultClassifier.classifyMediaStore("BLOCKED", true, false))
    }

    @Test fun mediaStoreDeniedAndUnavailableAreFailures() {
        listOf("DENIED", "UNAVAILABLE", "REVOKED", "FAILED", "ERROR").forEach { status ->
            assertEquals("media status $status", "FAILED",
                ScanResultClassifier.classifyMediaStore(status, false, false))
        }
    }

    @Test fun mediaStoreUnknownStateCannotBecomeSuccessThroughPartialFlag() {
        assertEquals("FAILED", ScanResultClassifier.classifyMediaStore("FUTURE_STATUS", false, true))
        assertEquals("FAILED", ScanResultClassifier.classifyMediaStore(null, false, false))
    }
}
