package com.reianix.reianix_local.player

/**
 * The bottom bar is user-configurable without losing access to the menu.
 * The default has five buttons, with Options fixed on the right.
 */
enum class PlayerShortcut(val key: String, val displayName: String) {
    LOCK("lock", "Bloquear"),
    EPISODES("episodes", "Episódios"),
    NEXT("next", "Próximo episódio"),
    OPTIONS("options", "Opções"),
    SPEED("speed", "Velocidade"),
    CAST("cast", "Transmitir"),
    SUBTITLES("subtitles", "Legendas"),
    TIMER("timer", "Temporizador");
}

object PlayerShortcutPolicy {
    val default = listOf(
        PlayerShortcut.CAST,
        PlayerShortcut.EPISODES,
        PlayerShortcut.LOCK,
        PlayerShortcut.NEXT,
        PlayerShortcut.OPTIONS,
    )

    val defaultValue = default.joinToString(",") { it.key }

    const val MAX_COUNT = 5

    // The five-slot bottom row uses 1/64 of screen width as its left inset
    // and 3/64 as its right inset. Its middle column is 1/64 to the left
    // of the geometric screen center.
    const val FIVE_SHORTCUT_INSET_UNIT_FRACTION = 1f / 64f

    /** Center of the final Options slot relative to the padded bottom row. */
    fun optionsCenterFraction(shortcuts: List<PlayerShortcut>): Float {
        val count = pinnedOptionsLast(shortcuts).size.coerceAtLeast(1)
        val inset = if (shortcuts.size == MAX_COUNT) FIVE_SHORTCUT_INSET_UNIT_FRACTION else 0f
        return inset + (1f - 4f * inset) * (1f - 0.5f / count)
    }

    private val historicalFiveDefault = listOf(PlayerShortcut.LOCK, PlayerShortcut.EPISODES,
        PlayerShortcut.SPEED, PlayerShortcut.NEXT, PlayerShortcut.OPTIONS)

    /**
     * The native preparation spinner is centered in the full PlayerView.
     * Center Play/Pause on that same anchor in portrait; keep the already
     * approved five-shortcut offset in landscape. Seek controls share the
     * parent offset so their spacing remains unchanged.
     */
    fun centerActionGroupShiftFraction(shortcuts: List<PlayerShortcut>, landscape: Boolean): Float =
        if (landscape && shortcuts.size == MAX_COUNT) -FIVE_SHORTCUT_INSET_UNIT_FRACTION else 0f

    fun normalize(raw: String): String {
        val keys = raw.split(',').map { it.trim() }
        require(keys.size in 1..MAX_COUNT && keys.none { it.isBlank() } &&
            keys.distinct().size == keys.size
        ) { "Only one to five unique player shortcuts are supported" }

        // "capture" is a retired shortcut. Drop it from pre-upgrade SQLite
        // preferences and imported backups without discarding other choices.
        // It must never be part of the current enum or appear in the player.
        val kept = keys.filterNot { it == "capture" }
        val options = kept.map { key ->
            PlayerShortcut.entries.firstOrNull { it.key == key }
                ?: throw IllegalArgumentException("Unsupported player shortcut")
        }
        require(PlayerShortcut.OPTIONS in options) {
            "Shortcut configuration must contain Options"
        }
        // Keep valid four-button selections as four: the user may have
        // deliberately disabled an item after upgrading to the five-button default.
        val pinned = pinnedOptionsLast(options)
        // Upgrade the exact previous five-button default, never overwrite a
        // different customized order (Speed remains an available optional key).
        return (if (pinned == historicalFiveDefault) default else pinned)
            .joinToString(",") { it.key }
    }

    /** The menu is always the fixed rightmost button, including legacy saved orders. */
    private fun pinnedOptionsLast(items: List<PlayerShortcut>): List<PlayerShortcut> =
        items.filterNot { it == PlayerShortcut.OPTIONS } + PlayerShortcut.OPTIONS

    fun parse(value: String?): List<PlayerShortcut> = runCatching {
        normalize(value ?: defaultValue)
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
