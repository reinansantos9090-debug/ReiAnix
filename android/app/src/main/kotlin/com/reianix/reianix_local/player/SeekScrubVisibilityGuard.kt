package com.reianix.reianix_local.player

/**
 * Main-thread interaction state shared by the seekbar, auto-hide timer and
 * playback callbacks. A playback event must not restart auto-hide mid-gesture.
 */
internal class SeekScrubVisibilityGuard {
    var active: Boolean = false
        private set

    fun update(isScrubbing: Boolean): Boolean {
        if (active == isScrubbing) return false
        active = isScrubbing
        return true
    }

    fun blocksHide(inPictureInPicture: Boolean, locked: Boolean, errorVisible: Boolean): Boolean =
        active && !inPictureInPicture && !locked && !errorVisible
}
