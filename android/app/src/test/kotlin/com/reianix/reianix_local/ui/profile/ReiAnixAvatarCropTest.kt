package com.reianix.reianix_local.ui.profile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReiAnixAvatarCropTest {
    @Test fun defaultCropCentersSquareInLandscapeAndPortrait() {
        assertEquals(AvatarCropRect(500, 0, 1000), avatarCropRect(2000, 1000, .5f, .5f, 1f))
        assertEquals(AvatarCropRect(0, 500, 1000), avatarCropRect(1000, 2000, .5f, .5f, 1f))
    }

    @Test fun cropRemainsWithinImageAtEveryEdgeAndZoom() {
        for ((w, h) in listOf(2000 to 1000, 1000 to 2000, 512 to 512, 1 to 1)) {
            for (x in listOf(-.5f, 0f, .5f, 1f, 1.5f)) {
                for (y in listOf(-.5f, 0f, .5f, 1f, 1.5f)) {
                    for (f in listOf(0f, .15f, .5f, 1f, 2f)) {
                        val r = avatarCropRect(w, h, x, y, f)
                        assertTrue(r.size > 0 && r.left >= 0 && r.top >= 0)
                        assertTrue(r.left + r.size <= w && r.top + r.size <= h)
                    }
                }
            }
        }
    }
}
