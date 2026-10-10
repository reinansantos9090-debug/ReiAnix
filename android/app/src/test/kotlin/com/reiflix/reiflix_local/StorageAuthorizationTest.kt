package com.reiflix.reiflix_local

import com.reiflix.reiflix_local.storage.BroadStorageAccessLevel
import com.reiflix.reiflix_local.storage.MediaAccessLevel
import com.reiflix.reiflix_local.storage.SafAccessLevel
import com.reiflix.reiflix_local.storage.StorageAuthorization
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StorageAuthorizationTest {
    @Test
    fun android_api_branching_maps_media_permissions_deterministically() {
        assertEquals(
            MediaAccessLevel.FULL,
            StorageAuthorization.mediaAccess(32, readExternalStorage = true),
        )
        assertEquals(
            MediaAccessLevel.DENIED,
            StorageAuthorization.mediaAccess(32, readExternalStorage = false),
        )
        assertEquals(
            MediaAccessLevel.FULL,
            StorageAuthorization.mediaAccess(33, readMediaVideo = true),
        )
        assertEquals(
            MediaAccessLevel.DENIED,
            StorageAuthorization.mediaAccess(33),
        )
        assertEquals(
            MediaAccessLevel.FULL,
            StorageAuthorization.mediaAccess(34, readMediaVideo = true),
        )
        assertEquals(
            MediaAccessLevel.PARTIAL,
            StorageAuthorization.mediaAccess(34, readSelectedVisualMedia = true),
        )
        assertEquals(
            MediaAccessLevel.DENIED,
            StorageAuthorization.mediaAccess(34),
        )
    }

    @Test
    fun partial_media_access_is_not_full_but_is_scan_capable() {
        assertFalse(StorageAuthorization.mediaAccess(34, readSelectedVisualMedia = true) == MediaAccessLevel.FULL)
        assertTrue(StorageAuthorization.canScanMediaStore(MediaAccessLevel.PARTIAL))
        assertFalse(StorageAuthorization.canScanMediaStore(MediaAccessLevel.DENIED))
        assertTrue(StorageAuthorization.canReconcileMediaStore(MediaAccessLevel.FULL))
        assertFalse(StorageAuthorization.canReconcileMediaStore(MediaAccessLevel.PARTIAL))
        assertFalse(StorageAuthorization.canReconcileMediaStore(MediaAccessLevel.DENIED))
    }

    @Test
    fun saf_state_depends_on_the_persisted_tree_grant() {
        val uri = "content://com.android.externalstorage.documents/tree/primary%3AMovies"

        assertEquals(
            SafAccessLevel.UNKNOWN,
            StorageAuthorization.safAccess(null, emptyList()),
        )
        assertEquals(
            SafAccessLevel.AVAILABLE,
            StorageAuthorization.safAccess(uri, listOf(uri)),
        )
        assertEquals(
            SafAccessLevel.REVOKED,
            StorageAuthorization.safAccess(uri, emptyList()),
        )
        assertTrue(StorageAuthorization.canScanSaf(SafAccessLevel.AVAILABLE))
        assertFalse(StorageAuthorization.canScanSaf(SafAccessLevel.REVOKED))
    }

    @Test
    fun saf_identity_matches_canonical_python_identity_for_equivalent_encoded_tree_uris() {
        val encoded = "content://com.android.externalstorage.documents/tree/primary%3AMovies"
        val lowercaseEncoding = "content://COM.ANDROID.EXTERNALSTORAGE.DOCUMENTS/tree/primary%3aMovies"

        assertEquals(
            "saf:com.android.externalstorage.documents:primary:Movies",
            StorageAuthorization.safIdentity(encoded),
        )
        assertEquals(
            StorageAuthorization.safIdentity(encoded),
            StorageAuthorization.safIdentity(lowercaseEncoding),
        )
        assertEquals(
            SafAccessLevel.AVAILABLE,
            StorageAuthorization.safAccess(encoded, listOf(lowercaseEncoding)),
        )
    }

    @Test
    fun broad_storage_state_is_independent_and_gates_only_broad_scan() {
        assertEquals(
            BroadStorageAccessLevel.AVAILABLE,
            StorageAuthorization.broadAccess(true),
        )
        assertEquals(
            BroadStorageAccessLevel.UNAVAILABLE,
            StorageAuthorization.broadAccess(false),
        )
        assertTrue(StorageAuthorization.canScanBroad(true))
        assertFalse(StorageAuthorization.canScanBroad(false))
    }
}
