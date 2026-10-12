package com.reiflix.reiflix_local.data.settings

/**
 * Typed compatibility contract for the preference keys currently defined by
 * core/settings.py. Persisted values remain strings in LibraryStore.preferences
 * for backward compatibility; this schema supplies type validation at the
 * Kotlin boundary without changing the database format.
 */
enum class ReiAnixSettingKind { BOOLEAN, INTEGER, DECIMAL, ENUM, LANGUAGE }

data class ReiAnixSettingDefinition(
    val key: String,
    val kind: ReiAnixSettingKind,
    val defaultValue: String,
    val choices: Set<String> = emptySet(),
) {
    fun normalize(raw: String): String {
        val value = raw.trim()
        return when (kind) {
            ReiAnixSettingKind.BOOLEAN -> when (value.lowercase()) {
                "true", "1", "yes", "on" -> "true"
                "false", "0", "no", "off" -> "false"
                else -> throw IllegalArgumentException("Invalid boolean for $key")
            }
            ReiAnixSettingKind.INTEGER -> {
                val parsed = value.toIntOrNull()
                    ?: throw IllegalArgumentException("Invalid integer for $key")
                require(parsed >= 0) { "Negative integer for $key" }
                if (choices.isNotEmpty()) require(value in choices) { "Unsupported value for $key" }
                parsed.toString()
            }
            ReiAnixSettingKind.DECIMAL -> {
                val parsed = value.toDoubleOrNull()
                    ?: throw IllegalArgumentException("Invalid decimal for $key")
                require(parsed.isFinite() && parsed > 0.0) { "Invalid decimal for $key" }
                val canonical = parsed.toString().removeSuffix(".0").let {
                    if (it.contains('.') || it.contains('E') || it.contains('e')) it else "$it.0"
                }
                if (choices.isNotEmpty()) require(value in choices || canonical in choices) {
                    "Unsupported value for $key"
                }
                canonical
            }
            ReiAnixSettingKind.ENUM -> {
                require(value in choices) { "Unsupported option for $key" }
                value
            }
            ReiAnixSettingKind.LANGUAGE -> {
                require(value.length <= 32 && (value.isEmpty() ||
                    Regex("[A-Za-z]{2,3}(?:-[A-Za-z0-9]{2,8})*").matches(value))) {
                    "Invalid language tag for $key"
                }
                value
            }
        }
    }
}

object ReiAnixSettingsContract {
    private fun bool(key: String, default: Boolean) =
        ReiAnixSettingDefinition(key, ReiAnixSettingKind.BOOLEAN, default.toString())
    private fun int(key: String, default: Int, vararg choices: Int) =
        ReiAnixSettingDefinition(key, ReiAnixSettingKind.INTEGER, default.toString(), choices.map(Int::toString).toSet())
    private fun decimal(key: String, default: String, vararg choices: String) =
        ReiAnixSettingDefinition(key, ReiAnixSettingKind.DECIMAL, default, choices.toSet())
    private fun enum(key: String, default: String, vararg choices: String) =
        ReiAnixSettingDefinition(key, ReiAnixSettingKind.ENUM, default, choices.toSet())

    val definitions: Map<String, ReiAnixSettingDefinition> = listOf(
        bool("app.confirm_destructive", true),
        enum("appearance.theme", "dark", "system", "light", "dark"),
        enum("appearance.card_size", "medium", "small", "medium", "large"),
        bool("appearance.show_thumbnails", true),
        enum("library.sort_default", "added_desc", "added_desc", "title_asc", "title_desc", "recently_watched"),
        enum("library.grid_density", "medium", "small", "medium", "large"),
        int("library.page_size", 36, 24, 36, 48, 72),
        bool("library.continue_watching", true),
        int("library.continue_watching_limit", 10, 5, 10, 15, 20),
        bool("player.autoplay_next", true),
        bool("player.resume", true),
        decimal("player.default_speed", "1.0", "0.5", "0.75", "1.0", "1.25", "1.5", "1.75", "2.0"),
        enum("player.aspect_ratio", "fit", "fit", "fill"),
        bool("player.zoom_enabled", false),
        enum("player.immersive", "always", "always", "landscape", "never"),
        enum("player.rotation", "auto", "auto", "portrait", "landscape"),
        bool("player.pip", true),
        bool("player.lock_mode", false),
        int("player.auto_hide_seconds", 5, 5, 10, 15, 30, 0),
        int("player.double_tap_seek_seconds", 10, 5, 10, 15, 30),
        decimal("player.long_press_speed", "2.0", "1.5", "1.75", "2.0"),
        enum("player.max_video_resolution", "auto", "auto", "480p", "720p", "1080p", "1440p", "2160p"),
        int("player.max_video_frame_rate", 0, 0, 24, 30, 60),
        int("player.max_audio_channels", 0, 0, 2, 6, 8),
        bool("gestures.volume", false),
        bool("gestures.brightness", false),
        bool("gestures.double_tap", false),
        bool("gestures.long_press", false),
        ReiAnixSettingDefinition("audio.preferred_language", ReiAnixSettingKind.LANGUAGE, ""),
        ReiAnixSettingDefinition("audio.preferred_subtitle_language", ReiAnixSettingKind.LANGUAGE, ""),
        enum("audio.subtitles", "auto", "auto", "always", "never"),
        decimal("audio.subtitle_scale", "1.0", "0.75", "1.0", "1.25", "1.5"),
        int("audio.subtitle_bottom_padding", 8, 4, 8, 12, 16),
        bool("audio.subtitle_embedded_style", true),
        bool("metadata.anilist_enabled", true),
        bool("metadata.auto_match", true),
        bool("artwork.enabled", true),
        int("artwork.cache_limit_mb", 128, 64, 128, 256, 512),
    ).associateBy { it.key }

    /** Unknown legacy keys are retained verbatim rather than silently discarded. */
    fun normalize(key: String, value: String): String {
        val normalizedKey = key.trim()
        require(normalizedKey.isNotEmpty()) { "Preference key must not be blank." }
        return definitions[normalizedKey]?.normalize(value) ?: value
    }

    fun defaultFor(key: String): String? = definitions[key]?.defaultValue
}
