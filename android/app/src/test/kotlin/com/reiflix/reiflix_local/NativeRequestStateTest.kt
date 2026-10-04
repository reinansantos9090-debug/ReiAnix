package com.reiflix.reiflix_local

import com.reiflix.reiflix_local.bridge.NativeRequestState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeRequestStateTest {
    @Test
    fun duplicate_request_id_is_ignored() {
        val state = NativeRequestState()

        assertTrue(state.acceptRequest("abc", "select_tree", 100L))
        assertEquals(NativeRequestState.OperationState.RECEIVED, state.operationState("abc"))
        assertFalse(state.acceptRequest("abc", "select_tree", 100L))
        assertTrue(state.acceptRequest("def", "scan_tree", 200L))
        assertEquals("def", state.lastHandledRequestId)
        assertFalse(state.acceptRequest("abc", "select_tree", 100L))
    }

    @Test
    fun blank_request_ids_are_not_treated_as_duplicates() {
        val state = NativeRequestState()

        assertFalse(state.acceptRequest(null))
        assertFalse(state.acceptRequest(""))
        assertFalse(state.acceptRequest("   "))
    }

    @Test
    fun lifecycle_action_is_queued_once_and_consumed_once() {
        val state = NativeRequestState()

        assertTrue(state.acceptRequest("queued", "request_media_access", 100L))
        assertTrue(state.queueLifecycleAction("request_media_access", "queued"))
        assertEquals(NativeRequestState.OperationState.QUEUED, state.operationState("queued"))
        assertFalse(state.queueLifecycleAction("open_broad_storage_settings", "other"))
        assertEquals("request_media_access", state.consumeLifecycleAction())
        assertNull(state.consumeLifecycleAction())
    }

    @Test
    fun malformed_native_actions_are_rejected() {
        assertFalse(NativeRequestState.isSupportedAction(null))
        assertFalse(NativeRequestState.isSupportedAction(""))
        assertFalse(NativeRequestState.isSupportedAction("  unknown_action  "))
        assertTrue(NativeRequestState.isSupportedAction("scan_tree"))
        assertTrue(NativeRequestState.isSupportedAction("open_broad_storage_settings"))
        assertTrue(NativeRequestState.isSupportedAction("open_storage_settings"))
    }

    @Test
    fun saved_request_and_pending_action_can_be_restored() {
        val state = NativeRequestState()

        state.restore("abc", "open_broad_storage_settings")

        assertEquals("abc", state.lastHandledRequestId)
        assertEquals("open_broad_storage_settings", state.pendingLifecycleAction)
        assertFalse(state.acceptRequest("abc"))
        assertEquals("open_broad_storage_settings", state.consumeLifecycleAction())
    }

    @Test
    fun operation_state_transitions_are_independent_by_request_id() {
        val state = NativeRequestState()

        assertTrue(state.acceptRequest("A", "select_tree", 100L))
        assertTrue(state.acceptRequest("B", "scan_tree", 200L))
        state.markOperationState("A", "select_tree", NativeRequestState.OperationState.RUNNING)
        state.markOperationState("B", "scan_tree", NativeRequestState.OperationState.COMPLETED)

        assertEquals(NativeRequestState.OperationState.RUNNING, state.operationState("A"))
        assertEquals(NativeRequestState.OperationState.COMPLETED, state.operationState("B"))
    }

    @Test
    fun terminal_states_are_persisted_in_the_in_memory_snapshot() {
        val state = NativeRequestState()
        assertTrue(state.acceptRequest("timeout", "select_tree", 123L))
        state.markOperationState("timeout", "select_tree", NativeRequestState.OperationState.TIMEOUT)
        val snapshot = state.requestSnapshot("timeout")!!

        assertEquals("select_tree", snapshot.action)
        assertEquals(NativeRequestState.OperationState.TIMEOUT, snapshot.state)
        assertEquals(123L, snapshot.createdAt)
        assertTrue(snapshot.updatedAt >= snapshot.createdAt)
    }
}
