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


    def test_details_scroll_state_is_keyed_by_anime_and_selector_uses_compact_tokens(self):
        source = DETAILS.read_text(encoding="utf-8")
        tokens = (ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/ui/theme/ReiAnixTokens.kt").read_text(encoding="utf-8")
        self.assertIn(".background(MaterialTheme.colorScheme.background)", source)
        self.assertIn("val listState = rememberSaveable(\n        anime.id,", source)
        self.assertIn("MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)", source)
        self.assertIn("val detailsSeasonCardWidth = 236.dp", tokens)
        self.assertIn("val detailsSeasonPreviewWidth = 80.dp", tokens)
        self.assertIn("val detailsSeasonPreviewHeight = 54.dp", tokens)

    def test_details_prioritizes_compact_actions_and_current_progress(self):
        source = DETAILS.read_text(encoding="utf-8")
        self.assertIn('text = "Continuar • ${formatEpisodeNumber(current.number)}"', source)
        self.assertIn("progress = current.progressFraction", source)
        self.assertIn("modifier = Modifier.weight(1f)", source)
        self.assertIn('text = if (anime.favorite) "Favoritado" else "Favoritar"', source)
        self.assertIn('text = if (watched) "Desmarcar visto" else "Marcar visto"', source)

    def test_details_synopsis_uses_only_the_canonical_description(self):
        source = DETAILS.read_text(encoding="utf-8")
        about = source[source.index("private fun DetailsAboutSection("):]
        self.assertIn("anime.description?.trim()?.takeIf { it.isNotBlank() }", about)
        self.assertIn("DetailsExpandableSynopsis(description)", about)
        self.assertNotIn("descriptionOriginal", source)
        self.assertNotIn("description_original", source)

if __name__ == "__main__":
    unittest.main()
