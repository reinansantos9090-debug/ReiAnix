package com.reianix.reianix_local.data.organize

import com.reianix.reianix_local.ui.model.ReiAnixGenreUiModel

/**
 * Semantic presentation identity, independent of provider/database IDs.
 * Stored associations and metadata remain untouched.
 */
internal object ReiAnixGenreNames {
    const val PREFIX = "genre:"
    private val labels = linkedMapOf(
        "action" to "Ação",
        "adventure" to "Aventura",
        "comedy" to "Comédia",
        "drama" to "Drama",
        "fantasy" to "Fantasia",
        "horror" to "Terror",
        "mecha" to "Mecha",
        "mystery" to "Mistério",
        "psychological" to "Psicológico",
        "romance" to "Romance",
        "sci fi" to "Ficção científica",
        "slice of life" to "Cotidiano",
        "sports" to "Esportes",
        "supernatural" to "Sobrenatural",
        "thriller" to "Suspense",
        "music" to "Música",
        "ecchi" to "Ecchi",
        "hentai" to "Hentai",
        "historical" to "Histórico",
        "military" to "Militar",
        "school" to "Escolar",
        "martial arts" to "Artes marciais",
        "demons" to "Demônios",
        "magic" to "Magia",
        "isekai" to "Isekai",
        "shounen" to "Shounen",
        "shoujo" to "Shoujo",
        "seinen" to "Seinen",
        "josei" to "Josei",
        "kids" to "Infantil",
        "space" to "Espaço",
        "parody" to "Paródia",
        "game" to "Jogos",
        "vampire" to "Vampiros",
        "police" to "Policial",
        "samurai" to "Samurai",
    )
    private val aliases = buildMap {
        labels.forEach { (key, label) ->
            put(normalizeReiAnixGenre(key), key)
            put(normalizeReiAnixGenre(label), key)
        }
        put("science fiction", "sci fi")
        put("scifi", "sci fi")
        put("slice of life", "slice of life")
        put("vida cotidiana", "slice of life")
        put("esporte", "sports")
        put("sport", "sports")
        put("terror", "horror")
        put("shonen", "shounen")
        put("shojo", "shoujo")
        put("acao", "action")
    }

    fun key(name: String): String {
        val normalized = normalizeReiAnixGenre(name)
        return PREFIX + (aliases[normalized] ?: normalized)
    }

    fun label(name: String): String = labels[key(name).removePrefix(PREFIX)] ?: name.trim()

    fun matches(genre: ReiAnixGenreUiModel, selected: String): Boolean =
        if (selected.startsWith(PREFIX)) key(genre.name) == selected else genre.stableKey == selected

    fun options(genres: Iterable<ReiAnixGenreUiModel>): List<ReiAnixGenreUiModel> =
        genres.asSequence()
            .filter { normalizeReiAnixGenre(it.name).isNotBlank() }
            .map { ReiAnixGenreUiModel(key(it.name), label(it.name)) }
            .distinctBy { it.stableKey }
            .sortedWith(compareBy({ normalizeReiAnixGenre(it.name) }, { it.stableKey }))
            .toList()

    /**
     * An association table can be partially backfilled while the original
     * metadata still holds the complete list. Do not discard either source.
     * Keep real IDs; legacy-only names have no fabricated database ID.
     */
    fun preserveMetadataGenres(
        registered: List<ReiAnixGenreUiModel>,
        metadata: List<String>,
    ): List<ReiAnixGenreUiModel> {
        val result = registered.filter { it.name.isNotBlank() }.toMutableList()
        val keys = result.mapTo(hashSetOf()) { key(it.name) }
        metadata.forEach { raw ->
            val name = raw.trim()
            if (name.isNotBlank() && keys.add(key(name))) {
                result.add(ReiAnixGenreUiModel(null, name))
            }
        }
        return result
    }

    // Only terms that actually match a translated label need to bypass the
    // original SQL candidate narrowing; a random one-character substring
    // should not disable its fast path for all ordinary searches.
    fun mayMatchTranslatedTerm(term: String): Boolean =
        labels.values.any { normalizeReiAnixGenre(it).split(' ').any { word -> word.startsWith(term) } }
}
