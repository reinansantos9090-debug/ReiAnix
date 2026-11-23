package com.reianix.reianix_local.player

/**
 * The bottom bar is user-configurable without losing access to the menu.
 * Exact default order matches the original four buttons.
 */
enum class PlayerShortcut(val key: String, val displayName: String) {
    LOCK("lock", "Bloquear"),
    EPISODES("episodes", "Episódios"),
    NEXT("next", "Próximo episódio"),
    OPTIONS("options", "Opções"),
    CAPTURE("capture", "Capturar imagem"),
    SPEED("speed", "Velocidade"),
    SUBTITLES("subtitles", "Legendas"),
    TIMER("timer", "Temporizador");
}

object PlayerShortcutPolicy {
    val default = listOf(
        PlayerShortcut.LOCK,
        PlayerShortcut.EPISODES,
        PlayerShortcut.NEXT,
        PlayerShortcut.OPTIONS,
    )
    const val MAX_COUNT = 5

    fun normalize(raw: String): String {
        val entries = raw.split(',').map { it.trim() }
        require(entries.size in 1..MAX_COUNT && entries.none { it.isBlank() }) {
            "Only one to five player shortcuts are supported"
        }
        val options = entries.map { entry ->
            PlayerShortcut.entries.firstOrNull { it.key == entry }
                ?: throw IllegalArgumentException("Unsupported player shortcut")
        }
        require(options.distinct().size == options.size && PlayerShortcut.OPTIONS in options) {
            "Shortcut configuration must be unique and contain Options"
        }
        return options.joinToString(",") { it.key }
    }

    fun parse(value: String?): List<PlayerShortcut> = runCatching {
        normalize(value ?: default.joinToString(",") { it.key })
            .split(',').map { key -> PlayerShortcut.entries.first { it.key == key } }
    }.getOrDefault(default)

    fun toggle(current: List<PlayerShortcut>, shortcut: PlayerShortcut, selected: Boolean): List<PlayerShortcut> =
        when {
            shortcut == PlayerShortcut.OPTIONS -> current
            selected && shortcut in current -> current
            selected && current.size < MAX_COUNT -> current + shortcut
            !selected -> current.filterNot { it == shortcut }
            else -> current
        }

    fun move(current: List<PlayerShortcut>, shortcut: PlayerShortcut, direction: Int): List<PlayerShortcut> {
        val index = current.indexOf(shortcut)
        val target = index + direction
        if (index < 0 || target !in current.indices) return current
        return current.toMutableList().apply {
            this[index] = this[target]
            this[target] = shortcut
        }
    }
}
