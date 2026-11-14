package com.reiflix.reiflix_local.data.library

import com.reiflix.reiflix_local.data.sqlite.ReiAnixDurationObservationRecord
import com.reiflix.reiflix_local.data.sqlite.ReiAnixEpisodeRecord
import com.reiflix.reiflix_local.data.sqlite.ReiAnixSqliteLibraryRepository
import com.reiflix.reiflix_local.data.sqlite.ReiAnixTimelineSourceRecord
import java.util.Locale
import kotlin.math.abs
import kotlin.math.round

/**
 * Pure Kotlin implementation of the local discovery algorithms previously in
 * core/library_discovery.py. It never creates persistence or writes playback state.
 */
object ReiAnixLibraryDiscovery {
    private val specialEpisodeTypes = setOf("special", "ova", "oad", "ona", "extra", "movie")

    data class TimelineGroup(
        val year: Int?,
        val label: String,
        val items: List<ReiAnixTimelineSourceRecord>,
        val count: Int,
    )

    data class DurationAnomaly(
        val episode: ReiAnixDurationObservationRecord,
        val durationSeconds: Double,
        val relativeDelta: Double,
        val direction: String,
        val label: String,
    )

    data class DurationAnomalySeries(
        val animeId: Long,
        val animeTitle: String,
        val status: String,
        val episodesUsed: Int,
        val anomalies: List<DurationAnomaly>,
        val medianSeconds: Double,
        val iqrSeconds: Double?,
        val lowerFenceSeconds: Double? = null,
        val upperFenceSeconds: Double? = null,
    )

    data class DurationAnomalyReport(
        val items: List<DurationAnomalySeries>,
        val anomalyCount: Int,
        val seriesWithAnomalies: Int,
        val seriesWithInsufficientData: Int,
    )

    data class MarathonPlanItem(
        val episode: ReiAnixEpisodeRecord,
        val remainingSeconds: Double?,
        val isCurrent: Boolean,
    )

    data class MarathonPlan(
        val items: List<MarathonPlanItem>,
        val episodeCount: Int,
        val knownDurationSeconds: Double,
        val unknownDurationCount: Int,
        val partial: Boolean,
        val currentPath: String?,
    )

    fun formatDuration(seconds: Double?, includeSeconds: Boolean = false): String {
        val value = seconds ?: return "Duração desconhecida"
        if (!value.isFinite() || value < 0.0 || value > Long.MAX_VALUE.toDouble()) {
            return "Duração desconhecida"
        }
        val total = round(value).toLong()
        val hours = total / 3600L
        val remainder = total % 3600L
        val minutes = remainder / 60L
        val secs = remainder % 60L
        return when {
            hours > 0L -> hours.toString() + "h " + minutes.toString().padStart(2, '0') + "min" +
                if (includeSeconds) " " + secs.toString().padStart(2, '0') + "s" else ""
            minutes > 0L -> minutes.toString() + "min" +
                if (includeSeconds) " " + secs.toString().padStart(2, '0') + "s" else ""
            else -> secs.toString() + "s"
        }
    }

    fun timelineGroups(rows: List<ReiAnixTimelineSourceRecord>): List<TimelineGroup> {
        val groups = linkedMapOf<Int?, MutableList<ReiAnixTimelineSourceRecord>>()
        rows.forEach { row ->
            val year = row.year?.takeIf { it in 1800..3000 }
            groups.getOrPut(year) { mutableListOf() }.add(row)
        }
        val years = groups.keys.filterNotNull().sortedDescending().map { it as Int? }.toMutableList()
        if (groups.containsKey(null)) years += null
        return years.map { year ->
            val items = groups[year].orEmpty().sortedWith(
                compareBy<ReiAnixTimelineSourceRecord> { caseFold(it.title) }.thenBy { it.id },
            )
            TimelineGroup(
                year = year,
                label = year?.toString() ?: "Ano desconhecido",
                items = items,
                count = items.size,
            )
        }
    }

    fun durationAnomalies(rows: List<ReiAnixDurationObservationRecord>): DurationAnomalyReport {
        val grouped = linkedMapOf<Long, MutableList<Pair<ReiAnixDurationObservationRecord, Double>>>()
        val names = linkedMapOf<Long, String>()
        rows.forEach { row ->
            names[row.animeId] = row.animeTitle.ifBlank { "Anime local" }
            val duration = row.durationSeconds?.takeIf { it.isFinite() && it > 0.0 } ?: return@forEach
            grouped.getOrPut(row.animeId) { mutableListOf() }.add(row to duration)
        }

        val series = grouped.map { (animeId, episodes) ->
            val title = names[animeId] ?: "Anime local"
            val durations = episodes.map { it.second }
            val middle = median(durations)
            if (episodes.size < 4) {
                DurationAnomalySeries(
                    animeId = animeId,
                    animeTitle = title,
                    status = "insufficient",
                    episodesUsed = episodes.size,
                    anomalies = emptyList(),
                    medianSeconds = middle,
                    iqrSeconds = null,
                )
            } else {
                val quartiles = quartiles(durations)
                val iqr = (quartiles.second - quartiles.first).coerceAtLeast(0.0)
                val lower = quartiles.first - (1.5 * iqr)
                val upper = quartiles.second + (1.5 * iqr)
                val anomalies = episodes.mapNotNull { (episode, value) ->
                    val relative = if (middle > 0.0) abs(value - middle) / middle else 0.0
                    val outsideFence = value < lower || value > upper
                    val zeroIqrOutlier = iqr == 0.0 && relative >= 0.50
                    val relativeOutlier = relative >= 0.50
                    if ((outsideFence || zeroIqrOutlier || relativeOutlier) && relative >= 0.30) {
                        DurationAnomaly(
                            episode = episode,
                            durationSeconds = value,
                            relativeDelta = relative,
                            direction = if (value > middle) "long" else "short",
                            label = if (value > middle) {
                                "Duração muito acima da média"
                            } else {
                                "Duração muito abaixo da média"
                            },
                        )
                    } else {
                        null
                    }
                }.sortedWith(
                    compareBy<DurationAnomaly> { finiteOrDefault(it.episode.season?.toDouble()) }
                        .thenBy { finiteOrDefault(it.episode.number) }
                        .thenBy { finiteOrDefault(it.episode.absoluteNumber) }
                        .thenBy { caseFold(it.episode.fileName) }
                        .thenBy { it.episode.id },
                )
                DurationAnomalySeries(
                    animeId = animeId,
                    animeTitle = title,
                    status = if (anomalies.isEmpty()) "ok" else "unusual",
                    episodesUsed = episodes.size,
                    anomalies = anomalies,
                    medianSeconds = middle,
                    iqrSeconds = iqr,
                    lowerFenceSeconds = lower,
                    upperFenceSeconds = upper,
                )
            }
        }.sortedWith(
            compareBy<DurationAnomalySeries> { if (it.status == "unusual") 0 else 1 }
                .thenByDescending { it.anomalies.size }
                .thenBy { caseFold(it.animeTitle) },
        )
        return DurationAnomalyReport(
            items = series,
            anomalyCount = series.sumOf { it.anomalies.size },
            seriesWithAnomalies = series.count { it.status == "unusual" },
            seriesWithInsufficientData = series.count { it.status == "insufficient" },
        )
    }

    fun marathonPlan(
        rows: List<ReiAnixEpisodeRecord>,
        currentPath: String? = null,
    ): MarathonPlan {
        val ordered = rows.asSequence()
            .filter { it.missing != true }
            .filter { it.episodeType.orEmpty().lowercase(Locale.ROOT) !in specialEpisodeTypes }
            .sortedWith(
                compareBy<ReiAnixEpisodeRecord> { finiteOrDefault(it.season.toDouble()) }
                    .thenBy { finiteOrDefault(it.number) }
                    .thenBy { finiteOrDefault(it.absoluteNumber) }
                    .thenBy { caseFold(it.fileName) }
                    .thenBy { it.id },
            )
            .map { episode ->
                val duration = finiteDuration(episode.duration)
                var progress = episode.progress?.takeIf { it.isFinite() }?.coerceAtLeast(0.0) ?: 0.0
                if (duration != null) progress = progress.coerceAtMost(duration)
                PreparedEpisode(episode, duration, progress)
            }
            .toList()

        if (ordered.isEmpty()) return emptyMarathon(currentPath)

        var startIndex: Int? = null
        if (!currentPath.isNullOrBlank()) {
            startIndex = ordered.indexOfFirst { it.episode.path == currentPath }.takeIf { it >= 0 }
        }
        if (startIndex == null) {
            startIndex = ordered.indexOfFirst { item ->
                val duration = item.durationSeconds
                item.episode.watched != true &&
                    !(duration != null && duration > 0.0 && item.progressSeconds / duration >= 0.90)
            }.takeIf { it >= 0 }
        }
        if (startIndex == null) return emptyMarathon(currentPath)

        var knownDuration = 0.0
        var unknownDuration = 0
        val plan = ordered.drop(startIndex).mapNotNull { item ->
            if (item.episode.watched == true) return@mapNotNull null
            val remaining = item.durationSeconds?.let { (it - item.progressSeconds).coerceAtLeast(0.0) }
            if (remaining == null) unknownDuration++ else knownDuration += remaining
            MarathonPlanItem(
                episode = item.episode,
                remainingSeconds = remaining,
                isCurrent = !currentPath.isNullOrBlank() && item.episode.path == currentPath,
            )
        }
        return MarathonPlan(
            items = plan,
            episodeCount = plan.size,
            knownDurationSeconds = knownDuration,
            unknownDurationCount = unknownDuration,
            partial = unknownDuration > 0,
            currentPath = currentPath,
        )
    }

    private data class PreparedEpisode(
        val episode: ReiAnixEpisodeRecord,
        val durationSeconds: Double?,
        val progressSeconds: Double,
    )

    private fun emptyMarathon(path: String?) = MarathonPlan(
        items = emptyList(),
        episodeCount = 0,
        knownDurationSeconds = 0.0,
        unknownDurationCount = 0,
        partial = false,
        currentPath = path,
    )

    private fun finiteDuration(value: Double?): Double? =
        value?.takeIf { it.isFinite() && it > 0.0 }

    private fun finiteOrDefault(value: Double?): Double =
        value?.takeIf(Double::isFinite) ?: 1_000_000_000.0

    private fun caseFold(value: String): String =
        value.lowercase(Locale.ROOT).replace("ß", "ss").replace("ς", "σ")

    private fun median(values: List<Double>): Double {
        val sorted = values.sorted()
        if (sorted.isEmpty()) return 0.0
        val midpoint = sorted.size / 2
        return if (sorted.size % 2 == 0) {
            sorted[midpoint - 1] / 2.0 + sorted[midpoint] / 2.0
        } else {
            sorted[midpoint]
        }
    }

    /** Median-of-halves, excluding the central observation when the sample size is odd. */
    private fun quartiles(values: List<Double>): Pair<Double, Double> {
        val sorted = values.sorted()
        if (sorted.size == 1) return sorted.first() to sorted.first()
        val midpoint = sorted.size / 2
        val lower = sorted.subList(0, midpoint)
        val upperStart = if (sorted.size % 2 == 1) midpoint + 1 else midpoint
        val upper = sorted.subList(upperStart, sorted.size)
        return median(lower) to median(upper)
    }
}

/** Native facade over the canonical database. This service owns no separate state. */
class ReiAnixLibraryDiscoveryService(
    private val library: ReiAnixSqliteLibraryRepository,
) {
    suspend fun timelineGroups(): List<ReiAnixLibraryDiscovery.TimelineGroup> =
        ReiAnixLibraryDiscovery.timelineGroups(library.timelineItems())

    suspend fun durationAnomalies(animeId: Long? = null): ReiAnixLibraryDiscovery.DurationAnomalyReport {
        require(animeId == null || animeId > 0L) { "animeId must be a positive persisted ID." }
        return ReiAnixLibraryDiscovery.durationAnomalies(library.durationObservations(animeId))
    }

    suspend fun marathonPlan(
        animeId: Long,
        currentPath: String? = null,
    ): ReiAnixLibraryDiscovery.MarathonPlan {
        require(animeId > 0L) { "animeId must be a positive persisted ID." }
        return ReiAnixLibraryDiscovery.marathonPlan(library.marathonEpisodes(animeId), currentPath)
    }

    fun formatDuration(seconds: Double?, includeSeconds: Boolean = false): String =
        ReiAnixLibraryDiscovery.formatDuration(seconds, includeSeconds)
}
