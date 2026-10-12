package com.reiflix.reiflix_local

import com.reiflix.reiflix_local.scanner.NativeScanController
import com.reiflix.reiflix_local.scanner.ScanLifecycleState
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class NativeScanControllerTest {
    private fun id(): String = "scan-test-${UUID.randomUUID()}"

    @Test
    fun beginPublishesPreparingSnapshotAndPreventsDuplicateSource() {
        val first = id()
        val second = id()
        assertTrue(NativeScanController.begin(first, "saf:source-a"))
        assertEquals(ScanLifecycleState.PREPARING, NativeScanController.snapshot(first)?.state)
        assertFalse(NativeScanController.begin(second, "saf:source-a"))
        assertTrue(NativeScanController.begin(second, "saf:source-b"))
        NativeScanController.finish(first)
        NativeScanController.finish(second)
    }

    @Test
    fun successfulAndEmptyScansAreExplicitlyComplete() {
        val completeId = id()
        assertTrue(NativeScanController.begin(completeId, "saf:complete"))
        NativeScanController.complete(
            completeId,
            "COMPLETED",
            JSONObject().put("stats", JSONObject().put("videos", 4).put("directories", 2)),
        )
        val complete = NativeScanController.snapshot(completeId)
        assertEquals(ScanLifecycleState.SUCCEEDED, complete?.state)
        assertTrue(complete?.isComplete == true)
        assertEquals(4L, complete?.result?.discoveredFiles)
        assertTrue(complete?.result?.complete == true)
        NativeScanController.finish(completeId)

        val emptyId = id()
        assertTrue(NativeScanController.begin(emptyId, "saf:empty"))
        NativeScanController.complete(emptyId, "EMPTY_COMPLETE", JSONObject())
        assertEquals(ScanLifecycleState.SUCCEEDED, NativeScanController.snapshot(emptyId)?.state)
        assertTrue(NativeScanController.snapshot(emptyId)?.isComplete == true)
        NativeScanController.finish(emptyId)
    }

    @Test
    fun authorizationFailureNeverLooksLikeAnEmptySuccessfulLibrary() {
        val scanId = id()
        assertTrue(NativeScanController.begin(scanId, "saf:revoked"))
        NativeScanController.complete(scanId, "REVOKED", JSONObject())
        val snapshot = NativeScanController.snapshot(scanId)
        assertEquals(ScanLifecycleState.FAILED, snapshot?.state)
        assertFalse(snapshot?.isComplete == true)
        assertNull(snapshot?.completionPercent)
        NativeScanController.finish(scanId)
    }

    @Test
    fun partialResultPreservesCountsAndErrorsButIsNotComplete() {
        val scanId = id()
        assertTrue(NativeScanController.begin(scanId, "saf:partial"))
        val payload = JSONObject().put(
            "stats",
            JSONObject()
                .put("videos", 7)
                .put("directories", 3)
                .put("failedQueries", 1)
                .put("errors", JSONArray().put("subdirectory unavailable")),
        ).put("partial", true)
        NativeScanController.complete(scanId, "PARTIAL", payload)
        val result = NativeScanController.snapshot(scanId)?.result
        assertEquals(ScanLifecycleState.PARTIAL, result?.finalState)
        assertEquals(7L, result?.discoveredFiles)
        assertEquals(1, result?.errors?.size)
        assertFalse(result?.complete == true)
        assertFalse(NativeScanController.snapshot(scanId)?.isComplete == true)
        NativeScanController.finish(scanId)
    }

    @Test
    fun cancellationBeforeLaunchDoesNotStartWork() {
        val scanId = id()
        assertTrue(NativeScanController.begin(scanId, "saf:cancel-before"))
        assertTrue(NativeScanController.requestCancel(scanId, "test"))
        assertFalse(NativeScanController.launch(scanId) { error("Cancelled scan must not launch") })
        assertEquals(ScanLifecycleState.CANCELLED, NativeScanController.snapshot(scanId)?.state)
        assertTrue(NativeScanController.snapshot(scanId)?.result?.cancelled == true)
    }

    @Test
    fun cancellationDuringScanWinsOverSuccessReportedNearCompletion() {
        val scanId = id()
        assertTrue(NativeScanController.begin(scanId, "saf:cancel-during"))
        assertTrue(NativeScanController.requestCancel(scanId, "user"))
        NativeScanController.complete(scanId, "COMPLETED", JSONObject())
        val snapshot = NativeScanController.snapshot(scanId)
        assertEquals(ScanLifecycleState.CANCELLED, snapshot?.state)
        assertFalse(snapshot?.isComplete == true)
        assertTrue(snapshot?.result?.cancelled == true)
        NativeScanController.finish(scanId)
    }

    @Test
    fun cancelAllMarksEveryActiveOperationWithoutErasingTerminalSnapshot() {
        val scanId = id()
        assertTrue(NativeScanController.begin(scanId, "broad-storage"))
        assertTrue(NativeScanController.cancelAll().contains(scanId))
        assertTrue(NativeScanController.isCancelled(scanId))
        assertEquals(ScanLifecycleState.CANCELLATION_REQUESTED, NativeScanController.snapshot(scanId)?.state)
        NativeScanController.complete(scanId, "CANCELLED", JSONObject().put("cancelled", true))
        NativeScanController.finish(scanId)
        assertFalse(NativeScanController.isCancelled(scanId))
        assertEquals(ScanLifecycleState.CANCELLED, NativeScanController.snapshot(scanId)?.state)
    }

    @Test
    fun progressIsObservableAndUnknownTotalsRemainIndeterminate() {
        val scanId = id()
        assertTrue(NativeScanController.begin(scanId, "mediastore"))
        NativeScanController.updateProgress(
            scanId,
            JSONObject().put("phase", "scanning").put("videos", 12).put("directories", 4),
        )
        val snapshot = NativeScanController.snapshot(scanId)
        assertEquals(ScanLifecycleState.RUNNING, snapshot?.state)
        assertEquals(12L, snapshot?.filesFound)
        assertEquals(4L, snapshot?.directoriesCompleted)
        assertTrue(snapshot?.isIndeterminate == true)
        assertNull(snapshot?.completionPercent)
        assertNotNull(NativeScanController.progress.value[scanId])
        NativeScanController.finish(scanId)
    }

    @Test
    fun invalidLateEventsCannotReplaceTerminalState() {
        val scanId = id()
        assertTrue(NativeScanController.begin(scanId, "saf:terminal"))
        NativeScanController.complete(scanId, "COMPLETED", JSONObject())
        NativeScanController.complete(scanId, "FAILED", JSONObject().put("errorType", "late"))
        NativeScanController.updateProgress(scanId, JSONObject().put("videos", 999))
        val snapshot = NativeScanController.snapshot(scanId)
        assertEquals(ScanLifecycleState.SUCCEEDED, snapshot?.state)
        assertEquals(0L, snapshot?.filesFound)
        NativeScanController.finish(scanId)
    }

    @Test
    fun unknownTerminalStatusFailsClosed() {
        val scanId = id()
        assertTrue(NativeScanController.begin(scanId, "saf:unknown-status"))
        NativeScanController.complete(scanId, "UNRECOGNIZED_STATUS", JSONObject())
        assertEquals(ScanLifecycleState.FAILED, NativeScanController.snapshot(scanId)?.state)
        assertFalse(NativeScanController.snapshot(scanId)?.isComplete == true)
        NativeScanController.finish(scanId)
    }

    @Test
    fun cancellationPreservesCountsAlreadyObserved() {
        val scanId = id()
        assertTrue(NativeScanController.begin(scanId, "mediastore:partial"))
        NativeScanController.updateProgress(
            scanId,
            JSONObject().put("videos", 5).put("directories", 2),
        )
        assertTrue(NativeScanController.requestCancel(scanId, "test"))
        NativeScanController.complete(scanId, "CANCELLED", JSONObject().put("cancelled", true))
        val result = NativeScanController.snapshot(scanId)?.result
        assertEquals(5L, result?.discoveredFiles)
        assertEquals(2L, result?.directoriesCompleted)
        assertFalse(result?.complete == true)
        NativeScanController.finish(scanId)
    }

    @Test
    fun failedOperationReleasesSourceForRetry() {
        val first = id()
        val retry = id()
        assertTrue(NativeScanController.begin(first, "saf:retryable"))
        NativeScanController.complete(first, "FAILED", JSONObject().put("errorType", "provider"))
        NativeScanController.finish(first)
        assertTrue(NativeScanController.begin(retry, "saf:retryable"))
        NativeScanController.finish(retry)
        assertEquals(ScanLifecycleState.FAILED, NativeScanController.snapshot(first)?.state)
        assertEquals(ScanLifecycleState.FAILED, NativeScanController.snapshot(retry)?.state)
    }
}
