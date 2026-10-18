package com.reiflix.reiflix_local.data.collector

import com.reiflix.reiflix_local.data.sqlite.ReiAnixCollectorEpisodeRow
import com.reiflix.reiflix_local.data.sqlite.ReiAnixSqliteLibraryRepository
import com.reiflix.reiflix_local.data.sqlite.ReiAnixSqliteSettingsRepository
import java.util.Calendar
import java.util.Locale
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

data class ReiAnixCollectorTitle(
    val id: String,
    val title: String,
    val condition: String,
    val unlocked: Boolean,
)

data class ReiAnixCollectorAchievement(
    val id: String,
    val title: String,
    val description: String,
    val category: String,
    val target: Long,
    val progress: Long,
    val value: Long,
    val unlocked: Boolean,
    val icon: String,
)

data class ReiAnixCollectorJourney(
    val xp: Int,
    val level: Int,
    val xpIntoLevel: Int,
    val xpToNext: Int,
    val levelProgress: Float,
    val episodesCompleted: Int,
    val animesCompleted: Int,
    val libraryTitles: Int,
    val activeDays: Int,
    val genresExplored: Int,
    val decadesExplored: Int,
    val formatsExplored: Int,
    val completedDurationSeconds: Long,
    val maxCompletedInOneAnime: Int,
    val activeTitle: String?,
    val activeTitleLabel: String?,
    val titles: List<ReiAnixCollectorTitle>,
    val achievements: List<ReiAnixCollectorAchievement>,
    val achievementsUnlocked: Int,
    val achievementsTotal: Int,
    val rebuildable: Boolean = true,
    val schemaVersion: Int = 1,
)

/**
 * Native domain owner for the Collector Journey. Every metric/unlock is derived from
 * canonical SQLite consumption and metadata. Only collector.active_title is persisted.
 */
class ReiAnixCollectorJourneyService(
    private val library: ReiAnixSqliteLibraryRepository,
    private val settings: ReiAnixSqliteSettingsRepository,
) {
    private val operationMutex = Mutex()

    suspend fun loadJourney(): ReiAnixCollectorJourney = operationMutex.withLock {
        rebuildAndReconcilePreference()
    }

    suspend fun setActiveTitle(titleId: String): ReiAnixCollectorJourney = operationMutex.withLock {
        val current = rebuildAndReconcilePreference()
        val selected = current.titles.firstOrNull { it.id == titleId && it.unlocked }
            ?: throw IllegalArgumentException("Título do colecionador indisponível.")
        settings.setPreference(ACTIVE_TITLE_PREFERENCE, selected.id)
        current.copy(activeTitle = selected.id, activeTitleLabel = selected.title)
    }

    suspend fun clearActiveTitle(): ReiAnixCollectorJourney = operationMutex.withLock {
        settings.removePreference(ACTIVE_TITLE_PREFERENCE)
        rebuildAndReconcilePreference()
    }

    private suspend fun rebuildAndReconcilePreference(): ReiAnixCollectorJourney {
        // If either canonical read fails, propagate it rather than inventing an empty journey.
        val rows = library.collectorSnapshot()
        val savedTitle = settings.getPreference(ACTIVE_TITLE_PREFERENCE)?.value
        val journey = ReiAnixCollectorJourneyCalculator.build(rows, savedTitle)
        if (savedTitle != journey.activeTitle) {
            val selected = journey.activeTitle
            if (selected == null) {
                settings.removePreference(ACTIVE_TITLE_PREFERENCE)
            } else {
                settings.setPreference(ACTIVE_TITLE_PREFERENCE, selected)
            }
        }
        return journey
    }

    companion object {
        const val ACTIVE_TITLE_PREFERENCE = "collector.active_title"
    }
}

/** Pure projection: no Compose, SQLite mutations, network calls or global state. */
internal object ReiAnixCollectorJourneyCalculator {
    private const val LEVEL_EPISODES = 10
    private const val GENRE_DISCOVERY_TARGET = 5
    private const val DECADE_DISCOVERY_TARGET = 3
    private const val MARATHON_TITLE_TARGET = 10
    private const val ACTIVE_DAY_TARGET = 7
    private const val ACTIVE_DAY_VETERAN_TARGET = 30
    private const val MAX_SUPPORTED_EPOCH_SECONDS = 253402300800.0

    fun build(
        rows: List<ReiAnixCollectorEpisodeRow>,
        savedActiveTitle: String?,
    ): ReiAnixCollectorJourney {
        val availableRows = rows.filter { it.missing != true }
        val completedRows = availableRows.filter(::isCompleted)
        val byAnime = availableRows.groupBy { it.animeId }
        val completedTitles = linkedMapOf<Long, ReiAnixCollectorEpisodeRow>()
        byAnime.forEach { (animeId, items) ->
            val completed = items.filter(::isCompleted)
            if (items.isNotEmpty() && completed.isNotEmpty() && completed.size == items.size) {
                completedTitles[animeId] = items.first()
            }
        }

        val completedEpisodeCount = completedRows.size
        val libraryTitleCount = rows.map { it.animeId }.toSet().size
        val animeCompletedCount = completedTitles.size
        val completedGenres = linkedSetOf<String>()
        val completedDecades = linkedSetOf<Int>()
        val completedFormats = linkedSetOf<String>()
        val activeDays = linkedSetOf<String>()
        var completedDuration = 0.0
        var maxCompletedInOneAnime = 0

        byAnime.forEach { (_, items) ->
            val completedHere = items.filter(::isCompleted)
            maxCompletedInOneAnime = maxOf(maxCompletedInOneAnime, completedHere.size)
            if (completedHere.isEmpty()) return@forEach

            val source = completedHere.first()
            decodeGenres(source.genresJson).forEach { genre -> completedGenres.add(genre) }
            source.year
                ?.takeIf { it in 1800..3000 }
                ?.let { completedDecades.add((it / 10) * 10) }
            val rawFormat = source.format?.takeIf { it.isNotEmpty() } ?: source.mediaKind.orEmpty()
            val format = rawFormat.trim()
            if (format.isNotEmpty() && format.lowercase(Locale.ROOT) !in setOf("unknown", "none", "null")) {
                completedFormats.add(format.lowercase(Locale.ROOT))
            }
        }

        completedRows.forEach { row ->
            completedDuration += finiteNumber(row.duration).coerceAtLeast(0.0)
        }
        // In-progress consumption also counts, but opening Home/Settings does not create activity.
        availableRows.forEach { row ->
            if (!hasMeaningfulActivity(row)) return@forEach
            val timestamp = finiteNumber(row.lastPlayedAt)
            if (timestamp <= 0.0 || timestamp >= MAX_SUPPORTED_EPOCH_SECONDS) return@forEach
            runCatching {
                // Calendar uses the device's local zone and supports the declared minSdk (24).
                val date = Calendar.getInstance().apply {
                    timeInMillis = (timestamp * 1000.0).toLong()
                }
                val year = date.get(Calendar.YEAR)
                if (year in 1..9999) {
                    val month = date.get(Calendar.MONTH) + 1
                    val day = date.get(Calendar.DAY_OF_MONTH)
                    activeDays.add(String.format(Locale.ROOT, "%04d-%02d-%02d", year, month, day))
                }
            }
        }

        val xp = completedEpisodeCount
        val level = 1 + xp / LEVEL_EPISODES
        val xpIntoLevel = xp % LEVEL_EPISODES
        val xpToNext = if (xpIntoLevel == 0) LEVEL_EPISODES else LEVEL_EPISODES - xpIntoLevel
        val completedDurationSeconds = completedDuration
            .coerceAtLeast(0.0)
            .coerceAtMost(Long.MAX_VALUE.toDouble())
            .toLong()
        val achievements = mutableListOf<ReiAnixCollectorAchievement>()

        fun addAchievement(
            id: String,
            title: String,
            description: String,
            category: String,
            target: Long,
            value: Long,
            icon: String,
        ) {
            val safeTarget = maxOf(1L, target)
            val safeValue = maxOf(0L, value)
            achievements += ReiAnixCollectorAchievement(
                id = id,
                title = title,
                description = description,
                category = category,
                target = safeTarget,
                progress = minOf(safeValue, safeTarget),
                value = safeValue,
                unlocked = safeValue >= safeTarget,
                icon = icon,
            )
        }

        addAchievement("first_episode", "Primeiro Passo", "Conclua seu primeiro episódio local.", "Consumo", 1, completedEpisodeCount.toLong(), "CHECK_CIRCLE")
        addAchievement("episodes_10", "10 Episódios", "Conclua 10 episódios locais.", "Consumo", 10, completedEpisodeCount.toLong(), "FORMAT_LIST_NUMBERED")
        addAchievement("episodes_50", "50 Episódios", "Conclua 50 episódios locais.", "Consumo", 50, completedEpisodeCount.toLong(), "FORMAT_LIST_NUMBERED")
        addAchievement("episodes_100", "100 Episódios", "Conclua 100 episódios locais.", "Consumo", 100, completedEpisodeCount.toLong(), "FORMAT_LIST_NUMBERED")
        addAchievement("episodes_500", "500 Episódios", "Conclua 500 episódios locais.", "Consumo", 500, completedEpisodeCount.toLong(), "FORMAT_LIST_NUMBERED")
        addAchievement("anime_1", "Primeiro Anime", "Conclua o primeiro título disponível na biblioteca.", "Consumo", 1, animeCompletedCount.toLong(), "MOVIE_OUTLINED")
        addAchievement("anime_10", "10 Animes", "Conclua 10 títulos da biblioteca.", "Consumo", 10, animeCompletedCount.toLong(), "MOVIE_OUTLINED")
        addAchievement("library_10", "Colecionador", "Mantenha 10 títulos na biblioteca local.", "Coleção", 10, libraryTitleCount.toLong(), "STAR")
        addAchievement("library_50", "Grande Acervo", "Mantenha 50 títulos na biblioteca local.", "Coleção", 50, libraryTitleCount.toLong(), "STAR")
        addAchievement("genres_5", "Explorador de Gêneros", "Conclua títulos de 5 gêneros diferentes.", "Exploração", GENRE_DISCOVERY_TARGET.toLong(), completedGenres.size.toLong(), "LOCAL_OFFER_OUTLINED")
        addAchievement("decades_3", "Explorador Temporal", "Conclua títulos de 3 décadas diferentes.", "Exploração", DECADE_DISCOVERY_TARGET.toLong(), completedDecades.size.toLong(), "CALENDAR_TODAY")
        addAchievement("formats_2", "Formatos Diversos", "Conclua títulos de 2 formatos diferentes.", "Exploração", 2, completedFormats.size.toLong(), "MOVIE_OUTLINED")
        addAchievement("active_7", "Ritmo de Colecionador", "Tenha atividade de consumo significativa em 7 dias diferentes.", "Atividade", ACTIVE_DAY_TARGET.toLong(), activeDays.size.toLong(), "HISTORY")
        addAchievement("active_30", "Jornada Constante", "Tenha atividade de consumo significativa em 30 dias diferentes.", "Atividade", ACTIVE_DAY_VETERAN_TARGET.toLong(), activeDays.size.toLong(), "HISTORY")
        addAchievement("marathon_10", "Maratonista Local", "Conclua 10 episódios de um mesmo título.", "Consumo", MARATHON_TITLE_TARGET.toLong(), maxCompletedInOneAnime.toLong(), "SCHEDULE_OUTLINED")
        addAchievement("content_10h", "10 Horas de Conteúdo", "Conclua pelo menos 10 horas de conteúdo local.", "Consumo", 10L * 3600L, completedDurationSeconds, "SCHEDULE_OUTLINED")
        addAchievement("content_50h", "50 Horas de Conteúdo", "Conclua pelo menos 50 horas de conteúdo local.", "Consumo", 50L * 3600L, completedDurationSeconds, "SCHEDULE_OUTLINED")

        val decadeNames = linkedMapOf(
            1980 to "Explorador dos anos 80",
            1990 to "Explorador dos anos 90",
            2000 to "Explorador dos anos 2000",
            2010 to "Explorador dos anos 2010",
            2020 to "Explorador dos anos 2020",
        )
        decadeNames.forEach { (decade, label) ->
            val titleCount = completedTitles.values.count { item ->
                val year = item.year?.takeIf { it in 1800..3000 }
                year != null && (year / 10) * 10 == decade
            }
            addAchievement(
                id = "decade_" + decade,
                title = label,
                description = "Conclua pelo menos um título lançado nos anos " + decade.toString().takeLast(2) + "0.",
                category = "Exploração",
                target = 1,
                value = titleCount.toLong(),
                icon = "CALENDAR_TODAY",
            )
        }

        val genreTitleCounts = mutableMapOf<String, Int>()
        completedTitles.values.forEach { item ->
            decodeGenres(item.genresJson).forEach { genre ->
                genreTitleCounts[genre] = (genreTitleCounts[genre] ?: 0) + 1
            }
        }
        genreTitleCounts.entries
            .sortedWith(compareBy<Map.Entry<String, Int>> { it.key.lowercase(Locale.ROOT) }.thenBy { it.key })
            .filter { it.value >= 3 }
            .forEach { (genre, count) ->
                addAchievement(
                    id = "genre_" + slug(genre),
                    title = "Explorador de " + genre,
                    description = "Conclua 3 títulos classificados com o gênero " + genre + ".",
                    category = "Exploração",
                    target = 3,
                    value = count.toLong(),
                    icon = "LOCAL_OFFER_OUTLINED",
                )
            }
        achievements.sortWith(compareBy<ReiAnixCollectorAchievement> { it.category }.thenBy { it.id })

        val titles = mutableListOf(
            ReiAnixCollectorTitle("collector_beginner", "Colecionador Iniciante", "1 episódio concluído.", completedEpisodeCount >= 1),
            ReiAnixCollectorTitle("collector", "Colecionador", "10 episódios concluídos.", completedEpisodeCount >= 10),
            ReiAnixCollectorTitle("temporal_explorer", "Explorador Temporal", "3 décadas exploradas.", completedDecades.size >= 3),
            ReiAnixCollectorTitle("genre_explorer", "Explorador de Gêneros", "5 gêneros explorados.", completedGenres.size >= 5),
            ReiAnixCollectorTitle("marathon_local", "Maratonista", "10 episódios concluídos em um mesmo título.", maxCompletedInOneAnime >= MARATHON_TITLE_TARGET),
            ReiAnixCollectorTitle("veteran", "Veterano", "100 episódios concluídos.", completedEpisodeCount >= 100),
            ReiAnixCollectorTitle("master", "Mestre", "300 episódios concluídos.", completedEpisodeCount >= 300),
        )
        genreTitleCounts.entries
            .sortedWith(compareBy<Map.Entry<String, Int>> { it.key.lowercase(Locale.ROOT) }.thenBy { it.key })
            .filter { it.value >= 3 }
            .forEach { (genre, _) ->
                titles += ReiAnixCollectorTitle(
                    id = "genre_" + slug(genre),
                    title = "Explorador de " + genre,
                    condition = "3 títulos concluídos com o gênero " + genre + ".",
                    unlocked = true,
                )
            }

        val selectedTitle = titles.firstOrNull { it.id == savedActiveTitle && it.unlocked }
            ?: titles.firstOrNull { it.unlocked }
        return ReiAnixCollectorJourney(
            xp = xp,
            level = level,
            xpIntoLevel = xpIntoLevel,
            xpToNext = xpToNext,
            levelProgress = xpIntoLevel.toFloat() / LEVEL_EPISODES.toFloat(),
            episodesCompleted = completedEpisodeCount,
            animesCompleted = animeCompletedCount,
            libraryTitles = libraryTitleCount,
            activeDays = activeDays.size,
            genresExplored = completedGenres.size,
            decadesExplored = completedDecades.size,
            formatsExplored = completedFormats.size,
            completedDurationSeconds = completedDurationSeconds,
            maxCompletedInOneAnime = maxCompletedInOneAnime,
            activeTitle = selectedTitle?.id,
            activeTitleLabel = selectedTitle?.title,
            titles = titles,
            achievements = achievements,
            achievementsUnlocked = achievements.count { it.unlocked },
            achievementsTotal = achievements.size,
        )
    }

    private fun isCompleted(row: ReiAnixCollectorEpisodeRow): Boolean {
        if (row.watched == true) return true
        val duration = finiteNumber(row.duration)
        val progress = finiteNumber(row.progress).coerceAtLeast(0.0)
        return duration > 0.0 && minOf(progress / duration, 1.0) >= 0.90
    }

    private fun hasMeaningfulActivity(row: ReiAnixCollectorEpisodeRow): Boolean {
        val progress = finiteNumber(row.progress).coerceAtLeast(0.0)
        val duration = finiteNumber(row.duration).coerceAtLeast(0.0)
        val ratio = if (duration > 0.0) minOf(progress / duration, 1.0) else 0.0
        return row.watched == true || ratio >= 0.05
    }

    private fun finiteNumber(value: Double?): Double =
        value?.takeIf { it.isFinite() } ?: 0.0

    private fun decodeGenres(value: String?): List<String> {
        if (value.isNullOrBlank()) return emptyList()
        val rawItems: List<String> = try {
            val source = value.trim()
            when {
                source.startsWith("[") -> {
                    val array = JSONArray(source)
                    (0 until array.length()).map { index ->
                        val item = array.opt(index)
                        if (item == JSONObject.NULL) "" else item.toString()
                    }
                }
                source.startsWith("{") -> {
                    JSONObject(source).keys().asSequence().toList()
                }
                source.startsWith("\"") -> {
                    val decoded = JSONArray("[" + source + "]").getString(0)
                    decoded.map { it.toString() }
                }
                else -> listOf(value)
            }
        } catch (_: Exception) {
            listOf(value)
        }
        val unique = linkedMapOf<String, String>()
        rawItems.forEach { raw ->
            val normalized = raw.trim().split(Regex("\\s+")).filter(String::isNotEmpty).joinToString(" ")
            if (normalized.isNotEmpty() && unique.keys.none { it.equals(normalized, ignoreCase = true) }) {
                unique[normalized] = normalized
            }
        }
        return unique.values.toList()
    }

    private fun slug(value: String): String =
        value.lowercase(Locale.ROOT)
            .map { character -> if (character.isLetterOrDigit()) character else '_' }
            .joinToString("")
            .trim('_')
}
