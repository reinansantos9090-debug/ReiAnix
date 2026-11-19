package com.reianix.reianix_local.ui.profile

import android.app.Application
import android.os.SystemClock
import com.reianix.reianix_local.data.profile.ReiAnixProfile
import com.reianix.reianix_local.data.profile.ReiAnixProfileStore
import com.reianix.reianix_local.data.sqlite.ReiAnixCollectorEpisodeRow
import com.reianix.reianix_local.player.PlayerLocalMetadataStore
import com.reianix.reianix_local.player.ProfileClipHandoff
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class ReiAnixProfileTest {
    @Test
    fun statsUseOnlyPersistedProgressAndDeduplicateEpisodeIds() {
        fun row(id: Long, progress: Double?, duration: Double?, watched: Boolean?, missing: Boolean = false) =
            ReiAnixCollectorEpisodeRow(id, 1L, progress, duration, watched, missing,
                null, null, "Anime", null, null, null, null)
        val first = row(1, 1800.0, 1200.0, false)
        val metrics = profileStatistics(emptyList(), listOf(first, first,
            row(2, 0.0, 600.0, true), row(3, 120.0, 1000.0, false),
            row(4, Double.NaN, null, false, true)))
        assertEquals(3, metrics.episodeCount)
        assertEquals(2, metrics.completedEpisodes)
        assertEquals(1920.0, metrics.watchedSeconds, 0.01)
        assertEquals(1, metrics.startedAnimeCount)
    }

    @Test
    fun profilePersistsSanitizedPersonalizationAndRejectsMissingPhoto() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val store = ReiAnixProfileStore.from(context)
        val original = store.state.value
        try {
            store.save(ReiAnixProfile(name = "  Otaku  ", bio = "x".repeat(200),
                avatar = "moon", color = "purple", frame = "gold", featuredAnimeId = 17L))
            val saved = store.state.value
            assertEquals("Otaku", saved.name)
            assertEquals(160, saved.bio.length)
            assertEquals("moon", saved.avatar)
            assertEquals("purple", saved.color)
            assertEquals("gold", saved.frame)
            assertEquals(17L, saved.featuredAnimeId)
            val prefs = context.getSharedPreferences("reianix_local_profile_v1", 0)
            assertEquals("Otaku", prefs.getString("name", null))
            assertEquals(17L, prefs.getLong("featured", 0))
            try {
                store.save(saved.copy(photo = "missing.jpg"))
                fail("Missing images must not overwrite the profile.")
            } catch (_: IllegalArgumentException) { }
            assertEquals(saved, store.state.value)
        } finally {
            store.save(original.copy(photo = null, banner = null))
        }
    }

    @Test
    fun clipHandoffMatchesRequestAndMediaAndIsConsumedOnlyOnce() {
        val scene = PlayerLocalMetadataStore.FavoriteScene("content://local/media/1",
            PlayerLocalMetadataStore.Note("scene-1", 1000, "Cena", 1, true, 5000))
        ProfileClipHandoff.put("request-a", scene)
        assertNull(ProfileClipHandoff.take("request-b", scene.mediaId))
        assertNull(ProfileClipHandoff.take("request-a", "content://local/media/2"))
        assertEquals(scene, ProfileClipHandoff.take("request-a", scene.mediaId))
        assertNull(ProfileClipHandoff.take("request-a", scene.mediaId))
        ProfileClipHandoff.put("request-b", scene)
        ProfileClipHandoff.clear("request-b")
        assertNull(ProfileClipHandoff.take("request-b", scene.mediaId))
    }
}
