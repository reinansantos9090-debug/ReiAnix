package com.reiflix.reiflix_local

import com.reiflix.reiflix_local.storage.NativeIndex
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class NativeIndexTest {
    @Test
    fun stableIdentity_convergesMediaStoreAndBroadOnSamePhysicalFile() {
        val media = JSONObject().put("uri", "content://media/external_primary/1").put("volumeId", "external_primary").put("relativePath", "Shows/a.mkv").put("name", "a.mkv")
        val broad = JSONObject().put("uri", "file:///storage/emulated/0/Shows/a.mkv").put("volumeId", "external_primary").put("relativePath", "Shows/a.mkv").put("name", "a.mkv")
        assertEquals(NativeIndex.stableIdentity(media, NativeIndex.SOURCE_MEDIASTORE), NativeIndex.stableIdentity(broad, NativeIndex.SOURCE_BROAD))
    }

    @Test
    fun safExternalDocumentIdentityConvergesToPhysicalRelativePath() {
        val saf = JSONObject().put("treeUri", "content://com.android.externalstorage.documents/tree/primary%3AMovies")
            .put("documentId", "primary:Movies/Sub/a.mkv").put("volumeId", "external_primary")
            .put("relativePath", "Sub/a.mkv").put("name", "a.mkv")
        val media = JSONObject().put("uri", "content://media/1").put("volumeId", "external_primary")
            .put("relativePath", "Movies/Sub/a.mkv").put("name", "a.mkv")
        assertEquals(NativeIndex.stableIdentity(saf, NativeIndex.SOURCE_SAF), NativeIndex.stableIdentity(media, NativeIndex.SOURCE_MEDIASTORE))
    }

    @Test
    fun safPrimaryIdentityConvergesWithMediaStoreExternalPrimary() {
        val saf = JSONObject()
            .put("treeUri", "content://com.android.externalstorage.documents/tree/primary%3AMovies")
            .put("documentId", "primary:Movies/Show/a.mkv")
            .put("volumeId", "primary")
            .put("relativePath", "Show/a.mkv")
        val broad = JSONObject()
            .put("uri", "file:///storage/emulated/0/Movies/Show/a.mkv")
            .put("volumeId", "external_primary")
            .put("relativePath", "Movies/Show/a.mkv")
        assertEquals(
            NativeIndex.stableIdentity(saf, NativeIndex.SOURCE_SAF),
            NativeIndex.stableIdentity(broad, NativeIndex.SOURCE_BROAD),
        )
    }

    @Test
    fun distinctVolumesWithSameRelativePathStayDistinct() {
        val primary = JSONObject().put("volumeId", "external_primary").put("relativePath", "Movies/a.mkv")
        val sd = JSONObject().put("volumeId", "ABCD-1234").put("relativePath", "Movies/a.mkv")
        assertNotEquals(
            NativeIndex.stableIdentity(primary, NativeIndex.SOURCE_BROAD),
            NativeIndex.stableIdentity(sd, NativeIndex.SOURCE_BROAD),
        )
    }

    @Test
    fun stableIdentity_doesNotUseDisplayNameAlone() {
        val a = JSONObject().put("uri", "content://media/1").put("volumeId", "external_primary").put("relativePath", "A/a.mkv").put("name", "a.mkv")
        val b = JSONObject().put("uri", "content://media/2").put("volumeId", "external_primary").put("relativePath", "B/a.mkv").put("name", "a.mkv")
        assertNotEquals(NativeIndex.stableIdentity(a, NativeIndex.SOURCE_MEDIASTORE), NativeIndex.stableIdentity(b, NativeIndex.SOURCE_MEDIASTORE))
    }
}
