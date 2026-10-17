package com.reiflix.reiflix_local.data.library

import com.reiflix.reiflix_local.data.sqlite.ReiAnixDurationObservationRecord
import com.reiflix.reiflix_local.data.sqlite.ReiAnixEpisodeRecord
import com.reiflix.reiflix_local.data.sqlite.ReiAnixTimelineSourceRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** JVM-only parity contract; the test remains assigned to manual execution. */
class ReiAnixLibraryDiscoveryTest {
    @Test
    fun timelineGroupsSortKnownYearsAndLeaveUnknownLast() {
        val result = ReiAnixLibraryDiscovery.timelineGroups(
            listOf(
                timeline(1, "A", 2024),
                timeline(2, "B", null),
                timeline(3, "C", 2026),
                timeline(4, "D", 2024),
                timeline(5, "Out of range", 1700),
            ),
        )
        assertEquals(listOf(2026, 2024, null), result.map { it.year })
        assertEquals(listOf("A", "D"), result[1].items.map { it.title })
        assertEquals("Ano desconhecido", result.last().label)
        assertEquals(2, result.last().count)
    }

    @Test
    fun durationAnomaliesKeepConservativeThresholdsAndInsufficientState() {
        val normal = listOf(1200.0, 1260.0, 1320.0, 1260.0, 1320.0)
            .mapIndexed { index, duration -> observation(index + 1L, duration) }
        assertEquals(0, ReiAnixLibraryDiscovery.durationAnomalies(normal).anomalyCount)

        val unusual = listOf(1200.0, 1260.0, 1320.0, 2700.0, 1320.0)
            .mapIndexed { index, duration -> observation(index + 1L, duration) }
        val report = ReiAnixLibraryDiscovery.durationAnomalies(unusual)
        assertEquals(1, report.anomalyCount)
        assertEquals("long", report.items.first().anomalies.first().direction)
        assertTrue(report.items.first().anomalies.first().label.contains("Duração"))

        val insufficient = ReiAnixLibraryDiscovery.durationAnomalies(unusual.take(1))
        assertEquals(1, insufficient.seriesWithInsufficientData)
        assertEquals(0, insufficient.anomalyCount)
        assertEquals("insufficient", insufficient.items.single().status)
    }

    @Test
    fun marathonUsesRequestedEpisodeProgressAndExcludesSpecialOrMissingItems() {
        val result = ReiAnixLibraryDiscovery.marathonPlan(
            listOf(
                episode(1, "e1", 1.0, 1200.0, 1200.0, true),
                episode(2, "e2", 2.0, 1380.0, 720.0, false),
                episode(3, "e3", 3.0, 1320.0, 0.0, false),
                episode(4, "special", 4.0, 2400.0, 0.0, false, episodeType = "OVA"),
                episode(5, "missing", 5.0, 2400.0, 0.0, false, missing = true),
            ),
            currentPath = "e2",
        )
        assertEquals(2, result.episodeCount)
        assertEquals(660.0, result.items[0].remainingSeconds!!, 0.0)
        assertEquals(1320.0, result.items[1].remainingSeconds!!, 0.0)
        assertEquals(1980.0, result.knownDurationSeconds, 0.0)
        assertTrue(result.items.first().isCurrent)
        assertFalse(result.partial)
    }

    @Test
    fun marathonSkipsCompletedEpisodesAndLeavesUnknownDurationsExplicit() {
        val result = ReiAnixLibraryDiscovery.marathonPlan(
            listOf(
                episode(1, "completed", 1.0, 100.0, 90.0, false),
                episode(2, "unknown", 2.0, null, 5.0, false),
                episode(3, "known", 3.0, 200.0, 0.0, false),
            ),
        )
        assertEquals(listOf("unknown", "known"), result.items.map { it.episode.path })
        assertNull(result.items.first().remainingSeconds)
        assertEquals(1, result.unknownDurationCount)
        assertTrue(result.partial)
    }

    @Test
    fun durationFormattingPreservesPortugueseLabelsAndUnknownValues() {
        assertEquals("Duração desconhecida", ReiAnixLibraryDiscovery.formatDuration(Double.NaN))
        assertEquals("Duração desconhecida", ReiAnixLibraryDiscovery.formatDuration(-1.0))
        assertEquals("1h 01min", ReiAnixLibraryDiscovery.formatDuration(3660.0))
        assertEquals("1h 01min 01s", ReiAnixLibraryDiscovery.formatDuration(3661.0, true))
    }

    private fun timeline(id: Long, title: String, year: Int?) =
        ReiAnixTimelineSourceRecord(
            id = id, title = title, year = year, season = null, status = null,
            mediaKind = null, coverCache = null, coverUrl = null, addedAt = null,
        )

    private fun observation(id: Long, duration: Double) =
        ReiAnixDurationObservationRecord(
            id = id, animeId = 10L, season = 1, number = id.toDouble(),
            absoluteNumber = null, fileName = "episode" + id + ".mkv", episodeTitle = null,
            durationSeconds = duration, animeTitle = "Normal",
        )

    private fun episode(
        id: Long,
        path: String,
        number: Double,
        duration: Double?,
        progress: Double?,
        watched: Boolean,
        missing: Boolean = false,
        episodeType: String = "regular",
    ) = ReiAnixEpisodeRecord(
        id = id, animeId = 10L, path = path, fileName = path + ".mkv",
        season = 1, number = number, duration = duration, progress = progress,
        watched = watched, mimeType = null, fileSize = null, modifiedAt = null,
        sourceFolder = null, absoluteNumber = null, relativePath = null,
        volumeId = null, volumeUuid = null, episodeType = episodeType,
        episodeTitle = null, identificationSource = null, identificationConfidence = null,
        manualOverride = null, missing = missing, lastPlayedAt = null, mediaIdentity = null,
        availabilityState = "available",
    )
}
