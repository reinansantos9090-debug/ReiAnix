package com.reiflix.reiflix_local

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.reiflix.reiflix_local.data.sqlite.ReiAnixSqliteSettingsRepository
import com.reiflix.reiflix_local.player.NativePlayerRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.UUID

/**
 * Exported entry point for Android's Open with / Share video intents.
 *
 * An external app never gets access to the private NativePlayerActivity or the
 * canonical library. The received URI is passed directly to the existing Media3
 * player with a temporary Android read grant; no scan/import is started.
 */
class ExternalVideoActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val externalUri = when (intent.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> {
                @Suppress("DEPRECATION")
                val stream = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
                stream ?: intent.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri
            }
            else -> null
        }

        // Never pass arbitrary external URLs or unsupported URI schemes into
        // the local-media player. The provider must grant access to content://.
        if (externalUri?.scheme?.lowercase(Locale.ROOT) !in setOf("content", "file")) {
            Toast.makeText(this, "Vídeo local inválido ou indisponível.", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        val videoUri = externalUri!!

        lifecycleScope.launch {
            val (preferences, displayName) = withContext(Dispatchers.IO) {
                val saved = runCatching {
                    ReiAnixSqliteSettingsRepository(applicationContext).allPreferences()
                }.getOrDefault(emptyMap())
                val name = runCatching {
                    if (videoUri.scheme == "content") {
                        contentResolver.query(
                            videoUri,
                            arrayOf(OpenableColumns.DISPLAY_NAME),
                            null,
                            null,
                            null,
                        )?.use { cursor ->
                            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                            if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
                        }
                    } else null
                }.getOrNull()
                Pair(saved, name)
            }

            val title = (displayName?.takeIf { it.isNotBlank() }
                ?: videoUri.lastPathSegment?.substringAfterLast('/')?.substringAfterLast(':')
                ?: "Vídeo externo").take(240)

            // Reuse the exact parser and preferences mapping used by playback
            // from the ReiAnix library; external media has no catalogue IDs.
            val request = NativePlayerRequest.fromQueryParameters { queryKey ->
                playerPreferenceKeys[queryKey]?.let(preferences::get)
            }.copy(
                requestId = UUID.randomUUID().toString(),
                commandCreatedAtMs = System.currentTimeMillis(),
                episodeUri = videoUri.toString(),
                playerSessionId = UUID.randomUUID().toString(),
                title = title,
                positionMs = 0L,
                positionWasExplicit = true,
                canNext = false,
                canPrevious = false,
                autoplay = true,
            )

            val playerIntent = request.toIntent(this@ExternalVideoActivity, videoUri)
                .putExtra("nativeCompose", true)
                .putExtra("handoffDispatchedAtMs", System.currentTimeMillis())

            // Pass the incoming URI permission to the private player Activity.
            // ClipData is required for reliable grant propagation across hops.
            if (videoUri.scheme == "content") {
                playerIntent.clipData = ClipData.newUri(
                    contentResolver,
                    "ReiAnix external video",
                    videoUri,
                )
                playerIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            try {
                startActivity(playerIntent)
            } catch (error: Exception) {
                Log.w(TAG, "Unable to open shared video in player", error)
                Toast.makeText(
                    this@ExternalVideoActivity,
                    "Não foi possível abrir este vídeo no ReiAnix.",
                    Toast.LENGTH_SHORT,
                ).show()
            } finally {
                finish()
            }
        }
    }

    private companion object {
        const val TAG = "ReiAnixExternalVideo"

        // Same keys forwarded by ReiAnixLibraryViewModel to NativePlayerRequest.
        val playerPreferenceKeys = mapOf(
            "setting_player_default_speed" to "player.default_speed",
            "setting_player_aspect_ratio" to "player.aspect_ratio",
            "setting_player_zoom_enabled" to "player.zoom_enabled",
            "setting_player_immersive" to "player.immersive",
            "setting_player_rotation" to "player.rotation",
            "setting_player_pip" to "player.pip",
            "setting_player_lock_mode" to "player.lock_mode",
            "setting_player_auto_hide_seconds" to "player.auto_hide_seconds",
            "setting_player_double_tap_seek_seconds" to "player.double_tap_seek_seconds",
            "setting_player_long_press_speed" to "player.long_press_speed",
            "setting_player_max_video_resolution" to "player.max_video_resolution",
            "setting_player_max_video_frame_rate" to "player.max_video_frame_rate",
            "setting_player_max_audio_channels" to "player.max_audio_channels",
            "setting_gestures_volume" to "gestures.volume",
            "setting_gestures_brightness" to "gestures.brightness",
            "setting_gestures_horizontal_seek" to "gestures.horizontal_seek",
            "setting_gestures_double_tap" to "gestures.double_tap",
            "setting_gestures_long_press" to "gestures.long_press",
            "setting_audio_preferred_language" to "audio.preferred_language",
            "setting_audio_preferred_subtitle_language" to "audio.preferred_subtitle_language",
            "setting_audio_subtitles" to "audio.subtitles",
            "setting_audio_subtitle_scale" to "audio.subtitle_scale",
            "setting_audio_subtitle_bottom_padding" to "audio.subtitle_bottom_padding",
            "setting_audio_subtitle_embedded_style" to "audio.subtitle_embedded_style",
        )
    }
}
