package com.reianix.reianix_local.data.artwork

import org.json.JSONArray
import java.util.Locale

/** Only explicit adult labels, never title guesses or ordinary mature themes. */
internal object ReiAnixAdultArtworkPolicy {
    private val explicitLabels = setOf("hentai", "adult", "adulto", "adult only", "adults only", "r18", "r18+", "rx", "18+ explícito")
    fun blocked(genres: Iterable<String>): Boolean = genres.any {
        it.trim().lowercase(Locale.ROOT) in explicitLabels
    }

    fun blockedJson(raw: String?): Boolean {
        if (raw.isNullOrBlank()) return false
        // Most rows use JSON; historical imports may contain a delimited string.
        val labels = runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).map { array.optString(it) }
        }.getOrElse { raw.split(',', ';', '|') }
        return blocked(labels)
    }
}
