import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
PLAYER = ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/NativePlayerActivity.kt"
CONTROLS = ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/player/PlayerMediaControls.kt"
GRADLE = ROOT / "android/app/build.gradle.kts"
MANIFEST = ROOT / "android/app/src/main/AndroidManifest.xml"


class NativeMediaControlsTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.player = PLAYER.read_text(encoding="utf-8")
        cls.controls = CONTROLS.read_text(encoding="utf-8")
        cls.gradle = GRADLE.read_text(encoding="utf-8")
        cls.manifest = MANIFEST.read_text(encoding="utf-8")

    def test_single_activity_player_owns_session_and_notification(self):
        self.assertEqual(1, self.player.count("ExoPlayer.Builder(this).build()"))
        self.assertIn("mediaControls = PlayerMediaControls(", self.player)
        self.assertIn("MediaSession.Builder(appContext, player)", self.controls)
        self.assertIn("setMediaSessionToken(mediaSession.sessionCompatToken)", self.controls)
        self.assertIn("setPlayer(player)", self.controls)

    def test_notification_actions_and_metadata_follow_real_player(self):
        for token in (
            "setUsePlayPauseActions(true)",
            "setUseFastForwardAction(true)",
            "setUseRewindAction(true)",
            "setUseNextAction(false)",
            "setUsePreviousAction(false)",
            "player.mediaMetadata.title",
            "player.mediaMetadata.artist",
        ):
            self.assertIn(token, self.controls)
        self.assertIn(".setMediaMetadata(", self.player)
        self.assertIn(".setTitle(titleValue.ifBlank { \"ReiAnix\" })", self.player)
        self.assertIn(".setArtist(currentEpisodeLabel())", self.player)

    def test_session_and_notification_are_released_with_activity(self):
        self.assertIn("mediaControls?.release()", self.player)
        self.assertIn("notificationManager.setPlayer(null)", self.controls)
        self.assertIn("mediaSession.release()", self.controls)
        self.assertLess(self.player.index("mediaControls?.release()"), self.player.index("player.release()"))

    def test_background_playback_is_not_enabled_by_a_service(self):
        self.assertNotIn("MediaSessionService", self.manifest)
        self.assertNotIn("android:foregroundServiceType=\"mediaPlayback\"", self.manifest)
        self.assertIn("if (::player.isInitialized && !isPictureInPictureActive() && sessionState == SessionState.ACTIVE)", self.player)
        self.assertIn("player.pause()", self.player)

    def test_media3_session_dependency_matches_existing_media3_version(self):
        self.assertIn('implementation("androidx.media3:media3-session:1.11.1")', self.gradle)
        self.assertIn('implementation("androidx.media3:media3-exoplayer:1.11.1")', self.gradle)


if __name__ == "__main__":
    unittest.main()
