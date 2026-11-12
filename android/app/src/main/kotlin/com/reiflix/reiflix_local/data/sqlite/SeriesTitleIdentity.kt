package com.reiflix.reiflix_local.data.sqlite

import com.reiflix.reiflix_local.data.media.MediaTitleNormalizer
import java.text.Normalizer

/**
 * Exact, conservative franchise/season evidence for local organisation.
 *
 * We do not equate arbitrary similar anime names. An ordinal suffix is accepted
 * only if there is ANOTHER actual local album with the exact base title. This
 * prevents stripping meaningful numbers from unrelated series.
 */
internal object SeriesTitleIdentity {
    data class TitleForm(val key: String, val season: Int, val tagged: Boolean)

    // Covers "Youjo Senki II", "Youjo Senki 2", "Season 2", "2nd Season",
    // "Temporada 2", "S02". Unicode Roman numerals are NFKC-normalised first.
    private val seasonSuffix = Regex(
        """(?i)^(.+?)[\s._-]+(?:season[\s._-]*(\d{1,2})|temporada[\s._-]*(\d{1,2})|temp[\s._-]*(\d{1,2})|(\d{1,2})(?:st|nd|rd|th)[\s._-]*season|s(\d{1,2})|([ivx]{2,5}|[2-9]|10))$"""
    )
    private val roman = mapOf(
        "ii" to 2, "iii" to 3, "iv" to 4, "v" to 5,
        "vi" to 6, "vii" to 7, "viii" to 8, "ix" to 9, "x" to 10,
    )
    private val prohibited = setOf(
        "episode", "episodio", "anime", "animes online", "unknown",
        "arquivo nao identificado", "sem titulo", "untitled"
    )

    fun form(raw: String?): TitleForm? {
        val value=raw?.trim().orEmpty()
        if(value.isEmpty()) return null
        val unfolded=Normalizer.normalize(value, Normalizer.Form.NFKC)
        val match=seasonSuffix.matchEntire(unfolded)
        if(match!=null) {
            val n=match.groupValues.drop(2).firstOrNull(String::isNotBlank)?.lowercase()
            val season=n?.toIntOrNull() ?: roman[n]
            val base=normal(match.groupValues[1])
            if(season!=null && season in 1..20 && useful(base)) {
                return TitleForm(base,season,true)
            }
        }
        val key=normal(unfolded)
        return if(useful(key)) TitleForm(key,1,false) else null
    }

    fun exactTitle(raw:String?):String? = form(raw)?.let { form ->
        if(!form.tagged)form.key else null
    }

    private fun useful(value:String):Boolean =
        value.length>=4 && value.any(Char::isLetter) && value !in prohibited &&
            !value.matches(Regex("""(?i)(?:ep|episode|episodio)\s*\d+"""))

    private fun normal(value:String):String = MediaTitleNormalizer.normalizeTitle(value)
        .replace(Regex("""\s+""")," ").trim()
}
