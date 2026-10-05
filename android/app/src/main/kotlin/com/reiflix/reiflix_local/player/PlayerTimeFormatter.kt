package com.reiflix.reiflix_local.player

import java.util.Locale

/**
 * Single time formatter shared by the native player UI and its Android host.
 *
 * Input and output are always milliseconds -> hh:mm:ss / mm:ss. Millisecond
 * precision is intentionally omitted because the player chrome only displays
 * whole seconds.
 */
object PlayerTimeFormatter {
    fun format(valueMs: Long): String {
        val totalSeconds = (valueMs.coerceAtLeast(0L)) / 1000L
        val seconds = totalSeconds % 60L
        val minutes = (totalSeconds / 60L) % 60L
        val hours = totalSeconds / 3600L

        return if (hours > 0L) {
            String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.US, "%02d:%02d", minutes, seconds)
        }
    }
}
