package com.reiflix.reiflix_local.storage

import android.net.Uri
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SafFileOperationsTest {
    private val operations by lazy {
        SafFileOperations(RuntimeEnvironment.getApplication())
    }

    @Test
    fun rejectsFilesystemUrisInsteadOfTreatingThemAsPaths() = runBlocking {
        val result = operations.list(Uri.parse("file:///storage/emulated/0/Anime"))
        assertTrue(result is SafFileOperations.ResultOrItems.Failed)
        assertEquals(
            SafFileOperations.FailureKind.UNSUPPORTED,
            (result as SafFileOperations.ResultOrItems.Failed).result.kind,
        )
    }

    @Test
    fun rejectsTraversalAndSeparatorNamesBeforeProviderMutation() = runBlocking {
        val parent = Uri.parse("content://example.invalid/tree/root/document/root")
        for (name in listOf("../escape", "nested/name", "nested\\name", ".", "..", "")) {
            val result = operations.createDirectory(parent, name)
            assertTrue("Expected invalid-name failure for $name", result is SafFileOperations.Result.Failure)
            assertEquals(
                SafFileOperations.FailureKind.INVALID_NAME,
                (result as SafFileOperations.Result.Failure).kind,
            )
        }
    }

    @Test
    fun missingPersistedTreeGrantIsReportedWithoutMutation() = runBlocking {
        val uri = Uri.parse("content://example.invalid/document/missing")
        val result = operations.rename(uri, "renamed.mkv")
        assertTrue(result is SafFileOperations.Result.Failure)
        assertEquals(
            SafFileOperations.FailureKind.PERMISSION_DENIED,
            (result as SafFileOperations.Result.Failure).kind,
        )
    }

    @Test
    fun readOnlyGrantAllowsListingPolicyButNeverMutation() {
        assertTrue(operations.grantAllows(true, false, requireWrite = false, treeMatches = true, withinTree = true))
        assertFalse(operations.grantAllows(true, false, requireWrite = true, treeMatches = true, withinTree = true))
    }

    @Test
    fun writeGrantRequiresReadWriteAndExactTreeScope() {
        assertTrue(operations.grantAllows(true, true, requireWrite = true, treeMatches = true, withinTree = true))
        assertFalse(operations.grantAllows(false, true, requireWrite = true, treeMatches = true, withinTree = true))
        assertFalse(operations.grantAllows(true, true, requireWrite = true, treeMatches = false, withinTree = true))
        assertFalse(operations.grantAllows(true, true, requireWrite = true, treeMatches = true, withinTree = false))
    }

    @Test
    fun cancelledCoroutineIsPropagated() = runBlocking {
        val cancelledJob = Job().apply { cancel() }
        var cancelled = false
        try {
            withContext(cancelledJob) {
                operations.list(Uri.parse("content://example.invalid/tree/root/document/root"))
            }
        } catch (_: CancellationException) {
            cancelled = true
        }
        assertTrue("SAF call must not swallow coroutine cancellation", cancelled)
    }

    @Test
    fun inaccessibleUriDoesNotProduceSuccessfulMetadata() = runBlocking {
        val result = operations.info(Uri.parse("content://example.invalid/document/not-authorized"))
        assertTrue(result is SafFileOperations.Result.Failure)
        assertFalse(result is SafFileOperations.Result.Success)
    }

}
