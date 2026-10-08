package com.reiflix.reiflix_local

import androidx.media3.common.TrackSelectionParameters
import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerAdvancedSettingsTest {
    @Test
    fun media3TrackConstraintsAcceptConfiguredLimits() {
        val parameters = TrackSelectionParameters.Builder()
            .setMaxVideoSize(1920, 1080)
            .setMaxVideoFrameRate(30)
            .setMaxAudioChannelCount(2)
            .build()

        assertEquals(1920, parameters.maxVideoWidth)
        assertEquals(1080, parameters.maxVideoHeight)
        assertEquals(30, parameters.maxVideoFrameRate)
        assertEquals(2, parameters.maxAudioChannelCount)
    }
}
