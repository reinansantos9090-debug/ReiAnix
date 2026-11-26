package com.reianix.reianix_local.player

import org.json.JSONObject

/** Three fixed aspect modes. Keep the legacy "fill" key for proportional zoom. */
enum class PlayerAspectMode(val setting: String, val label: String) {
    FIT("fit", "Ajustar"),
    STRETCH("stretch", "Esticar"),
    ZOOM("fill", "Zoom");

    fun next(): PlayerAspectMode = when (this) {
        FIT -> STRETCH
        STRETCH -> ZOOM
        ZOOM -> FIT
    }

    companion object {
        fun fromSetting(value: String?): PlayerAspectMode = when (value) {
            "stretch" -> STRETCH
            "fill", "zoom" -> ZOOM
            else -> FIT
        }

        fun fromLabel(value: String?): PlayerAspectMode = when (value) {
            "Esticar" -> STRETCH
            "Zoom", "Preencher" -> ZOOM
            else -> FIT
        }
    }
}

/**
 * One opt-in record per anime in the existing SQLite preferences table.
 * Never store the Media3 track group's runtime index: that index changes
 * between episodes and would otherwise select the wrong track.
 */
data class PlayerAnimeTrackChoice(
    val mode: String = "auto",
    val language: String = "",
    val label: String = "",
    val mimeType: String = "",
) {
    init { require(mode in setOf("auto", "off", "selected")) }

    fun toJson() = JSONObject()
        .put("mode", mode)
        .put("language", language.take(80))
        .put("label", label.take(160))
        .put("mime", mimeType.take(120))

    companion object {
        fun fromJson(json: JSONObject?) = PlayerAnimeTrackChoice(
            mode = json?.optString("mode")?.takeIf { it in setOf("auto", "off", "selected") } ?: "auto",
            language = json?.optString("language").orEmpty().take(80),
            label = json?.optString("label").orEmpty().take(160),
            mimeType = json?.optString("mime").orEmpty().take(120),
        )
    }
}

data class PlayerAnimePreferences(
    val speed: Float = 1f,
    val aspect: String = "fit",
    val autoplay: Boolean = true,
    val zoomEnabled: Boolean = true,
    val audioOnly: Boolean = false,
    val audioLanguage: String = "",
    val subtitleLanguage: String = "",
    val subtitleMode: String = "auto",
    val subtitleScale: Float = 1f,
    val subtitlePadding: Int = 8,
    val subtitleEmbedded: Boolean = true,
    val subtitleColor: String = "system",
    val subtitleFont: String = "system",
    val audioTrack: PlayerAnimeTrackChoice = PlayerAnimeTrackChoice(),
    val subtitleTrack: PlayerAnimeTrackChoice = PlayerAnimeTrackChoice(),
) {
    fun encode(): String = JSONObject()
        .put("v", 1)
        .put("speed", speed.toDouble())
        .put("aspect", aspect)
        .put("autoplay", autoplay)
        .put("zoom", zoomEnabled)
        .put("audioOnly", audioOnly)
        .put("audioLanguage", audioLanguage.take(80))
        .put("subtitleLanguage", subtitleLanguage.take(80))
        .put("subtitleMode", subtitleMode)
        .put("subtitleScale", subtitleScale.toDouble())
        .put("subtitlePadding", subtitlePadding)
        .put("subtitleEmbedded", subtitleEmbedded)
        .put("subtitleColor", subtitleColor)
        .put("subtitleFont", subtitleFont)
        .put("audioTrack", audioTrack.toJson())
        .put("subtitleTrack", subtitleTrack.toJson())
        .toString()

    companion object {
        fun keyFor(animeId: String?): String? {
            val id = animeId?.trim().orEmpty()
            return id.takeIf { it.length in 1..18 && it.all(Char::isDigit) && id.toLongOrNull()?.let { n -> n > 0 } == true }
                ?.let { "player.anime.$it" }
        }

        fun decode(raw: String?): PlayerAnimePreferences? = runCatching {
            if (raw.isNullOrBlank() || raw.length > 10_000) return@runCatching null
            val obj = JSONObject(raw)
            if (obj.optInt("v") != 1) return@runCatching null
            PlayerAnimePreferences(
                speed = obj.optDouble("speed", 1.0).toFloat().takeIf { it.isFinite() }?.coerceIn(0.5f, 2f) ?: 1f,
                aspect = PlayerAspectMode.fromSetting(obj.optString("aspect")).setting,
                autoplay = obj.optBoolean("autoplay", true),
                zoomEnabled = obj.optBoolean("zoom", true),
                audioOnly = obj.optBoolean("audioOnly", false),
                audioLanguage = obj.optString("audioLanguage").take(80),
                subtitleLanguage = obj.optString("subtitleLanguage").take(80),
                subtitleMode = obj.optString("subtitleMode").takeIf { it in setOf("auto", "never", "always") } ?: "auto",
                subtitleScale = obj.optDouble("subtitleScale", 1.0).toFloat().takeIf { it.isFinite() }?.coerceIn(0.5f, 2f) ?: 1f,
                subtitlePadding = obj.optInt("subtitlePadding", 8).coerceIn(0, 50),
                subtitleEmbedded = obj.optBoolean("subtitleEmbedded", true),
                subtitleColor = obj.optString("subtitleColor").takeIf { it in setOf("system", "white", "yellow", "cyan") } ?: "system",
                subtitleFont = obj.optString("subtitleFont").takeIf { it in setOf("system", "sans", "serif", "mono") } ?: "system",
                audioTrack = PlayerAnimeTrackChoice.fromJson(obj.optJSONObject("audioTrack")),
                subtitleTrack = PlayerAnimeTrackChoice.fromJson(obj.optJSONObject("subtitleTrack")),
            )
        }.getOrNull()
    }
}
