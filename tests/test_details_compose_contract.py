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
        self.assertIn("val detailsSeasonCardMinWidth = 240.dp", tokens)
        self.assertIn("PaddingValues(ReiAnixTokens.Spacing.none)", source)
        self.assertIn("ReiAnixTokens.Dimensions.detailsSeasonCardMinWidth", source)

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


    def test_details_distinguishes_announced_count_from_local_files(self):
        bridge = (ROOT / "core/compose_library_bridge.py").read_text(encoding="utf-8")
        mapper = (ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/ui/mapper/LibraryUiMappers.kt").read_text(encoding="utf-8")
        model = EPISODE_MODEL.read_text(encoding="utf-8")
        details_model = DETAILS_MODEL.read_text(encoding="utf-8")
        details = DETAILS.read_text(encoding="utf-8")
        self.assertIn('"episodes_count": meta.get("episodes_count")', bridge)
        self.assertIn('announcedEpisodeCount = metadata.intOrNull("episodes_count")', mapper)
        self.assertIn("val announcedEpisodeCount: Int? = null", model)
        self.assertIn("announcedEpisodeCount = anime.announcedEpisodeCount", details_model)
        self.assertIn('else "Biblioteca: $count episódios locais"', details)
        self.assertIn('else "AniList: $count episódios esperados"', details)
        self.assertIn('"Biblioteca: 1 episódio local"', details)
        self.assertIn('"AniList: 1 episódio esperado"', details)

    def test_details_preserves_and_presents_persisted_alternate_titles(self):
        details = DETAILS.read_text(encoding="utf-8")
        model = DETAILS_MODEL.read_text(encoding="utf-8")
        self.assertIn("val aliases: List<String> = emptyList()", model)
        self.assertIn("aliases = anime.aliases", model)
        self.assertIn('add("Títulos alternativos" to it.joinToString(" • "))', details)
        self.assertIn("distinctBy { it.lowercase(Locale.ROOT) }", details)
        self.assertIn("filter { it.lowercase(Locale.ROOT) !in knownTitles }", details)


    def test_remote_details_uses_only_exact_persisted_anilist_identity(self):
        repository = (ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/data/sqlite/ReiAnixSqliteLibraryRepository.kt").read_text(encoding="utf-8")
        integration = (ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/data/anilist/ReiAnixAniListIntegrationService.kt").read_text(encoding="utf-8")
        self.assertIn("suspend fun findAnimeIdsByAniListId(anilistId: Long)", repository)
        self.assertIn('"SELECT id FROM anime WHERE anilist_id = ? ORDER BY id ASC LIMIT 2"', repository)
        lookup = repository[repository.index("suspend fun findAnimeIdsByAniListId"):repository.index("suspend fun listAnime", repository.index("suspend fun findAnimeIdsByAniListId"))]
        query = lookup[lookup.index('"SELECT id FROM anime'):lookup.index('",', lookup.index('"SELECT id FROM anime'))]
        self.assertNotIn("title", query.casefold())
        self.assertIn("findLocalAnimeIdsByAniListId(anilistId: Int)", integration)

    def test_remote_details_offers_local_navigation_only_for_one_exact_match(self):
        remote = (ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/ui/details/ReiAnixAniListDetails.kt").read_text(encoding="utf-8")
        view_model = (ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/viewmodel/ReiAnixAniListViewModel.kt").read_text(encoding="utf-8")
        navigation = (ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/ui/navigation/ReiAnixNavigation.kt").read_text(encoding="utf-8")
        self.assertIn("associatedLocalAnimeIds.singleOrNull()", remote)
        self.assertIn("Abrir detalhes da biblioteca", remote)
        self.assertIn("associatedLocalAnimeIds.size > 1", remote)
        self.assertIn("associatedLocalAnimeIds = localAnimeIds", view_model)
        self.assertIn("navigateToDetails(localAnimeId.toString(), origin)", remote)
        self.assertIn("origin = origin", navigation)

    def test_remote_details_does_not_create_or_persist_an_unmatched_local_record(self):
        remote = (ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/ui/details/ReiAnixAniListDetails.kt").read_text(encoding="utf-8")
        self.assertIn("Esta busca não cria nem altera registros locais", remote)
        self.assertNotIn("insertOrThrow", remote)
        self.assertNotIn("addToPersonalList", remote)


if __name__ == "__main__":
    unittest.main()
