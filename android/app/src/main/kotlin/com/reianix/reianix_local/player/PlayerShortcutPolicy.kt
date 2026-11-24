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
        return pinnedOptionsLast(options).joinToString(",") { it.key }
    }

    /** The menu is always the fixed rightmost button, including legacy saved orders. */
    private fun pinnedOptionsLast(items: List<PlayerShortcut>): List<PlayerShortcut> =
        items.filterNot { it == PlayerShortcut.OPTIONS } + PlayerShortcut.OPTIONS

    fun parse(value: String?): List<PlayerShortcut> = runCatching {
        normalize(value ?: default.joinToString(",") { it.key })
            .split(',').map { key -> PlayerShortcut.entries.first { it.key == key } }
    }.getOrDefault(default)

    /** Enabling a shortcut places it first; the Options slot never moves. */
    fun toggle(current: List<PlayerShortcut>, shortcut: PlayerShortcut, selected: Boolean): List<PlayerShortcut> =
        when {
            shortcut == PlayerShortcut.OPTIONS -> pinnedOptionsLast(current)
            selected && shortcut in current -> pinnedOptionsLast(current)
            selected && current.size < MAX_COUNT -> pinnedOptionsLast(listOf(shortcut) + current)
            !selected -> pinnedOptionsLast(current.filterNot { it == shortcut })
            else -> pinnedOptionsLast(current)
        }

    /** Preview target with hysteresis, avoiding back-and-forth flicker at row boundaries. */
    fun dragPreviewIndex(
        startIndex: Int,
        previousIndex: Int,
        offsetPx: Float,
        rowHeightPx: Float,
        lastMovableIndex: Int,
    ): Int {
        if (!offsetPx.isFinite() || !rowHeightPx.isFinite() ||
            rowHeightPx <= 0f || lastMovableIndex < 0
        ) return previousIndex
        val position = startIndex + offsetPx / rowHeightPx
        var result = previousIndex.coerceIn(0, lastMovableIndex)
        while (position > result + 0.65f && result < lastMovableIndex) result++
        while (position < result - 0.65f && result > 0) result--
        return result
    }

    /** Reorder only checked shortcuts; Options is immovable. */
    fun move(current: List<PlayerShortcut>, shortcut: PlayerShortcut, direction: Int): List<PlayerShortcut> {
        if (shortcut == PlayerShortcut.OPTIONS || direction !in -1..1 || direction == 0) {
            return pinnedOptionsLast(current)
        }
        val movable = current.filterNot { it == PlayerShortcut.OPTIONS }.toMutableList()
        val index = movable.indexOf(shortcut)
        val target = index + direction
        if (index < 0 || target !in movable.indices) return pinnedOptionsLast(current)
        val displaced = movable[target]
        movable[target] = shortcut
        movable[index] = displaced
        return pinnedOptionsLast(movable)
    }
}
