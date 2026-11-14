package com.reiflix.reiflix_local.data.organize

import java.text.Normalizer
import java.util.Locale

/**
 * Local, deterministic genre inference ported from core/genre_classifier.py.
 *
 * This code does not call AniList, read file contents, or mutate catalog records.
 * Inference is intentionally only a suggestion for records without authoritative
 * genre metadata; callers decide when it is safe to persist the result.
 */
internal object ReiAnixGenreClassifier {
    private val rules = linkedMapOf(
        "Ação" to listOf("attack", "naruto", "dragon", "hero", "slayer", "battle", "fate", "bleach", "jujutsu"),
        "Aventura" to listOf("one piece", "adventure", "quest", "journey", "hunter"),
        "Fantasia" to listOf("magic", "fantasy", "dungeon", "isekai", "demon", "maou", "reincarn"),
        "Romance" to listOf("love", "koi", "romance", "girl", "kanojo", "horimiya"),
        "Comédia" to listOf("comedy", "comedia", "nichijou", "kaguya", "saiki"),
        "Terror" to listOf("horror", "ghost", "corpse", "tokyo ghoul", "dark"),
        "Esporte" to listOf("volley", "haikyuu", "football", "soccer", "basket", "sport"),
    )

    fun classify(title: String): List<String> {
        // Preserve the behavior of the legacy Python classifier, including its
        // intentionally conservative ASCII-token rules and stable rule order.
        val normalized = title.lowercase(Locale.ROOT).replace(NON_ASCII_CLASSIFIER_TOKEN, " ")
        return rules.filterValues { terms -> terms.any(normalized::contains) }.keys.toList()
    }

    private val NON_ASCII_CLASSIFIER_TOKEN = Regex("[^a-z0-9 ]")
}

/** Unicode-aware identity normalization shared with the persistent genre registry. */
internal fun normalizeReiAnixGenre(value: String?): String {
    val decomposed = Normalizer.normalize(value.orEmpty(), Normalizer.Form.NFKD)
    val withoutMarks = decomposed.filterNot { character ->
        Character.getType(character) == Character.NON_SPACING_MARK.toInt() ||
            Character.getType(character) == Character.COMBINING_SPACING_MARK.toInt() ||
            Character.getType(character) == Character.ENCLOSING_MARK.toInt()
    }
    return withoutMarks
        .lowercase(Locale.ROOT)
        .trim()
        .replace(Regex("[-_]+"), " ")
        .replace(Regex("[^\\p{L}\\p{N}_\\s/]+"), " ")
        .split(Regex("\\s+"))
        .filter(String::isNotBlank)
        .joinToString(" ")
}
