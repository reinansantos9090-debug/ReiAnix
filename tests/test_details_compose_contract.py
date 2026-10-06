from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
DETAILS = ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/ui/details/ReiAnixDetails.kt"
DETAILS_MODEL = ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/ui/model/ReiAnixDetailsUiModels.kt"
EPISODE_MODEL = ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/ui/model/LibraryUiModels.kt"
INSTRUMENTED = ROOT / "android/app/src/androidTest/kotlin/com/reiflix/reiflix_local/ui/details/ReiAnixDetailsInstrumentedTest.kt"


class DetailsComposeContractTests(unittest.TestCase):
    def test_details_uses_lazy_lists_and_stable_episode_identity(self):
        source = DETAILS.read_text(encoding="utf-8")
        self.assertIn("LazyColumn(", source)
        self.assertIn("LazyRow(", source)
        self.assertIn("key = { episode -> episode.stableKey }", source)
        self.assertIn("viewModel::setEpisodeWatched", source)
        self.assertNotIn("verticalScroll(", source)
        self.assertNotIn(".controls.clear()", source)
        self.assertNotIn(".controls.extend", source)

    def test_details_projection_keeps_real_seasons_and_specials(self):
        model = DETAILS_MODEL.read_text(encoding="utf-8")
        self.assertIn("val seasons: List<ReiAnixSeasonUiModel>", model)
        self.assertIn("val specials: List<ReiAnixEpisodeUiModel>", model)
        self.assertIn("seasons = anime.seasons", model)
        self.assertIn("specials = anime.specials", model)

    def test_episode_model_has_stable_key_and_fixed_progress_state(self):
        model = EPISODE_MODEL.read_text(encoding="utf-8")
        self.assertIn('get() = "episode:" + id', model)
        self.assertIn("val progressFraction: Float", model)
        self.assertIn("val progressPercent: Int?", model)

    def test_required_stage12_compose_regressions_have_tests(self):
        tests = INSTRUMENTED.read_text(encoding="utf-8")
        self.assertIn("episodeListKeepsEpisodeIdentityWhenProgressChanges", tests)
        self.assertIn("seasonSelectorShowsOnlyTheSelectedSeasonInCanonicalOrder", tests)
        self.assertIn("largeEpisodeSeasonUsesLazyListAndRetainsLastEpisode", tests)
        self.assertIn('performScrollToNode(hasText("E300 • Episode 300"))', tests)


if __name__ == "__main__":
    unittest.main()
