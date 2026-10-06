import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

class InstrumentationTests(unittest.TestCase):
    def read(self, path):
        return (ROOT / path).read_text(encoding="utf-8")

    def test_performance_uses_monotonic_timestamps_and_task_lifecycle(self):
        source=self.read("core/performance.py")
        self.assertIn("monotonic_ns=time.monotonic_ns()", source)
        for token in ("TASK_CREATED","TASK_FINISHED","TASK_CANCELLED","TASK_FAILED","tasks.active.total"):
            self.assertIn(token, source)

    def test_player_correlation_events_exist(self):
        main=self.read("main.py")
        player=self.read("android/app/src/main/kotlin/com/reiflix/reiflix_local/NativePlayerActivity.kt")
        for token in (
            "NEXT_BUTTON_PRESSED","PREVIOUS_BUTTON_PRESSED","PLAYER_COMMAND_DUPLICATE",
            "PLAYER_COMMAND_OUT_OF_ORDER","PLAYER_COMMAND_STALE","TARGET_EPISODE_RESOLVED",
            "NATIVE_PLAY_REQUEST_CREATED","PYTHON_MAILBOX_RECEIVED",
            "SQLITE_NEIGHBOR_QUERY_STARTED","SQLITE_NEIGHBOR_QUERY_FINISHED",
            "PLAYER_TRANSITION_COMMITTED",
        ):
            self.assertIn(token, main)
        for token in ("sequence","transitionGeneration","playerSessionId","PLAYER_COMMAND_OUT_OF_ORDER","PLAYER_LIFECYCLE"):
            self.assertIn(token, player)

    def test_compose_player_route_is_real_and_uses_existing_player_boundary(self):
        navigation = self.read("android/app/src/main/kotlin/com/reiflix/reiflix_local/ui/navigation/ReiAnixNavigation.kt")
        details = self.read("android/app/src/main/kotlin/com/reiflix/reiflix_local/ui/details/ReiAnixDetails.kt")
        player = self.read("android/app/src/main/kotlin/com/reiflix/reiflix_local/ui/player/ReiAnixPlayer.kt")
        repository = self.read("android/app/src/main/kotlin/com/reiflix/reiflix_local/data/library/ReiAnixLibraryRepository.kt")
        view_model = self.read("android/app/src/main/kotlin/com/reiflix/reiflix_local/viewmodel/ReiAnixLibraryViewModel.kt")

        self.assertIn("ReiAnixPlayerRoute(", navigation)
        self.assertIn("onWatch = { episodeId ->", details)
        self.assertIn("navController.navigateToPlayer(", details)
        self.assertNotIn("onWatch = viewModel::openEpisode", details)
        self.assertIn("viewModel.openEpisode(canonicalEpisodeId)", player)
        self.assertIn("LifecycleEventObserver", player)
        self.assertIn("BackHandler {", player)
        self.assertIn("if (canLeave)", player)
        self.assertNotIn("ExoPlayer", player)
        self.assertNotIn("androidx.media3", player)
        self.assertNotIn("import com.reiflix.reiflix_local.NativePlayerActivity", player)
        self.assertIn("fun openEpisode(episodeId: Long): String", repository)
        self.assertIn("fun openEpisode(episodeId: Long): String", view_model)
        self.assertIn("Action.OPEN_MEDIA", repository)

    def test_compose_player_passes_stable_route_identity_to_existing_native_request(self):
        navigation = self.read("android/app/src/main/kotlin/com/reiflix/reiflix_local/ui/navigation/ReiAnixNavigation.kt")
        request = self.read("android/app/src/main/kotlin/com/reiflix/reiflix_local/player/NativePlayerRequest.kt")
        self.assertIn('const val PLAYER = "player/{episodeId}?animeId={animeId}&origin={origin}"', navigation)
        self.assertIn('putExtra("uri", normalizedUri.toString())', request)
        self.assertIn('putExtra("episodeId", episodeId)', request)
        self.assertIn('putExtra("animeId", animeId)', request)
        self.assertIn('putExtra("positionMs", positionMs)', request)
        self.assertIn('putExtra("canNext", canNext)', request)
        self.assertIn('putExtra("canPrevious", canPrevious)', request)

    def test_mailbox_home_scan_and_thumbnail_markers_exist(self):
        mailbox=self.read("android/app/src/main/kotlin/com/reiflix/reiflix_local/bridge/NativeMailbox.kt")
        home=self.read("views/home_view.py")
        scan=self.read("core/scan_coordinator.py")
        thumb=self.read("android/app/src/main/kotlin/com/reiflix/reiflix_local/storage/VideoThumbnailExtractor.kt")
        for token in ("mailboxWriteStartedElapsedNs","writeDurationMs","EVENT_WRITTEN"):
            self.assertIn(token, mailbox)
        for token in ("HOME_BROWSE_START","HOME_BROWSE_END","HOME_HYDRATION_START","HOME_HYDRATION_END","HOME_SECTION_RENDER_START","HOME_SECTION_RENDER_END"):
            self.assertIn(token, home)
        for token in ("SCAN_STARTED","SCAN_SOURCE_STARTED","SCAN_SOURCE_DISPATCHED","SCAN_FINISHED"):
            self.assertIn(token, scan)
        for token in ("THUMBNAIL_REQUESTED","THUMBNAIL_CACHE_HIT","THUMBNAIL_CACHE_MISS","THUMBNAIL_GENERATION_STARTED","THUMBNAIL_GENERATION_FINISHED","THUMBNAIL_FAILED"):
            self.assertIn(token, thumb)

    def test_jank_has_aggregated_threshold_buckets(self):
        source=self.read("android/app/src/main/kotlin/com/reiflix/reiflix_local/PerformanceDiagnostics.kt")
        for token in ("framesOver16Ms","framesOver32Ms","framesOver50Ms","framesOver100Ms"):
            self.assertIn(token, source)

if __name__ == "__main__":
    unittest.main()
