import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
KOTLIN = ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local"
DETAILS = KOTLIN / "ui/details/ReiAnixDetails.kt"
LIBRARY_VM = KOTLIN / "viewmodel/ReiAnixLibraryViewModel.kt"
ANILIST_VM = KOTLIN / "viewmodel/ReiAnixAniListViewModel.kt"
SERVICE = KOTLIN / "data/anilist/ReiAnixAniListIntegrationService.kt"
LIBRARY_REPOSITORY = KOTLIN / "data/sqlite/ReiAnixSqliteLibraryRepository.kt"
ASSOCIATION = KOTLIN / "data/sqlite/ReiAnixSqliteAniListAssociation.kt"


class NativeAniListIntegrationContractTests(unittest.TestCase):
    def test_details_uses_shared_buttons_for_association_actions(self):
        source = DETAILS.read_text(encoding="utf-8")
        self.assertNotIn("TextButton(", source)
        self.assertIn('text = "Pesquisar"', source)
        self.assertIn('text = "Fechar"', source)
        self.assertIn("ReiAnixCompactButton(", source)
        self.assertIn("ReiAnixSecondaryButton(", source)

    def test_local_search_viewmodel_isolated_from_remote_anilist(self):
        source = LIBRARY_VM.read_text(encoding="utf-8")
        self.assertNotIn("anilist", source.lower())
        integration = ANILIST_VM.read_text(encoding="utf-8")
        self.assertIn("ReiAnixAniListIntegrationService", integration)
        self.assertIn("CancellationException", integration)
        self.assertIn("associationRevision", integration)

    def test_generic_metadata_patch_does_not_own_association_bookkeeping(self):
        source = LIBRARY_REPOSITORY.read_text(encoding="utf-8")
        start = source.index("suspend fun updateAnimeMetadata(")
        end = source.index("suspend fun getEpisode(", start)
        method = source[start:end]
        self.assertIn("database.transaction", method)
        self.assertNotIn('values.put("metadata_source"', method)
        self.assertNotIn('values.put("anilist_id"', method)
        self.assertIn('patch.coverCache?.takeIf { it.isNotBlank() }', method)
        self.assertIn('patch.genresJson?.takeIf { it.isNotBlank() && it.trim() != "[]" }', method)

    def test_anilist_association_is_atomic_and_preserves_manual_ownership(self):
        source = ASSOCIATION.read_text(encoding="utf-8")
        self.assertIn("database.transaction", source)
        self.assertIn('if (!manual && current.anilistMatchManual == true)', source)
        self.assertIn("current.anilistId != anilistId", source)
        self.assertIn('optionalString("metadata_manual_fields")', source)
        self.assertIn('values.put("metadata_source", "anilist")', source)
        self.assertIn('"cover_cache" !in protectedFields', source)
        for local_column in ('"local_path"', '"favorite"', '"personal_note"', '"user_tags"'):
            self.assertNotIn(f'values.put({local_column}', source)

    def test_anilist_preferences_and_artwork_gate_are_reused(self):
        source = SERVICE.read_text(encoding="utf-8")
        self.assertIn('"metadata.anilist_enabled"', source)
        self.assertIn('"metadata.auto_match"', source)
        self.assertIn('"artwork.enabled"', source)
        self.assertIn("coverUrl = if (artworkEnabled)", source)
        self.assertIn("bannerUrl = remote.bannerUrl.takeIf { artworkEnabled }", source)
        self.assertIn("private val autoMatchAttemptTimestamps = ConcurrentHashMap<Long, Long>()", source)
        matcher = (KOTLIN / "data/anilist/ReiAnixAniListMatcher.kt").read_text(encoding="utf-8")
        self.assertIn("ReiAnixAniListMatcher.rank(", source)
        self.assertIn("best.score < ReiAnixAniListMatcher.MINIMUM_SCORE", source)
        self.assertIn("best.score < AUTO_MATCH_MIN_SCORE || margin < AUTO_MATCH_MIN_MARGIN", source)
        self.assertIn("AUTO_CONFIRM_SCORE = 0.86", matcher)
        self.assertIn("AUTO_CONFIRM_MARGIN = 0.05", matcher)
        self.assertIn("const val AUTO_MATCH_MIN_SCORE = ReiAnixAniListMatcher.AUTO_CONFIRM_SCORE", source)
        self.assertIn("MINIMUM_SCORE = 0.48", matcher)


    def test_graphql_variables_are_literal_and_transport_payload_is_verified(self):
        client = (KOTLIN / "data/anilist/ReiAnixAniListClient.kt").read_text(encoding="utf-8")
        tests = (ROOT / "android/app/src/test/kotlin/com/reiflix/reiflix_local/data/anilist/ReiAnixAniListClientTest.kt").read_text(encoding="utf-8")
        for variable in ("search", "page", "perPage", "id"):
            self.assertIn(chr(36) + "{\'$\'}" + variable, client)
        self.assertIn(chr(36) + "MEDIA_FIELDS", client)
        self.assertIn("searchSendsLiteralGraphQlVariablesAndExpectedJsonValues", tests)
        self.assertIn("idLookupSendsLiteralIdVariableAndExpectedJsonValue", tests)
        self.assertIn('getJSONObject("variables")', tests)

    def test_native_synopsis_localization_preserves_source_and_legacy_cache_contract(self):
        localizer = (KOTLIN / "data/anilist/ReiAnixAniListDescriptionLocalizer.kt").read_text(encoding="utf-8")
        models = (KOTLIN / "data/sqlite/ReiAnixSqliteModels.kt").read_text(encoding="utf-8")
        dependencies = (KOTLIN / "ReiAnixAppDependencies.kt").read_text(encoding="utf-8")
        source_persistence = ASSOCIATION.read_text(encoding="utf-8")
        service = SERVICE.read_text(encoding="utf-8")

        self.assertIn("localizer.localizeToPtBr(remoteOriginal)", service)
        self.assertIn("localizer.getCachedDescriptionPtBr(remoteOriginal)", service)
        self.assertIn("descriptionOriginal = remoteOriginalDescription", service)
        self.assertIn("val descriptionOriginal: String? = null", models)
        self.assertIn('values.put("description_original", it)', source_persistence)
        self.assertIn('"data/covers/anilist_description_ptbr.json"', service)
        self.assertIn('"data/covers/anilist_description_ptbr.json"', dependencies)
        self.assertIn("TRANSLATION_ENDPOINT = \"https://api.mymemory.translated.net/get\"", localizer)
        self.assertIn("MAX_TRANSLATION_BYTES = 480", localizer)
        self.assertIn("MAX_CACHE_ENTRIES = 4096", localizer)
        self.assertIn("CancellationException", localizer)

    def test_native_anilist_metadata_handoff_only_requests_existing_artwork_engine(self):
        service = SERVICE.read_text(encoding="utf-8")
        dependencies = (KOTLIN / "ReiAnixAppDependencies.kt").read_text(encoding="utf-8")
        main = (ROOT / "main.py").read_text(encoding="utf-8")
        start = main.index("if event_type == 'anilist_metadata_committed':")
        end = main.index("if event_type == 'compose_settings_set':", start)
        handler = main[start:end]

        self.assertIn('"type", "anilist_metadata_committed"', service)
        self.assertIn('"type", "anilist_metadata_committed"', dependencies)
        self.assertIn("NativeMailbox.write(", dependencies)
        self.assertIn("if request_artwork and has_artwork_urls:", handler)
        self.assertIn("library.artwork.sync_anime_metadata(", handler)
        self.assertIn("request_download=True", handler)
        self.assertIn('library.artwork.request(', handler)
        self.assertIn("compose_library_bridge.request_publish(", handler)
        self.assertIn("anilist_metadata_updated:", handler)
        bridge = (ROOT / "core/compose_library_bridge.py").read_text(encoding="utf-8")
        self.assertIn('"anilist_metadata_updated"}', bridge)
        self.assertNotIn("library.anilist", handler)
        self.assertNotIn("AniListClient", handler)

    def test_native_library_owns_catalog_metadata_hydration_in_compose_mode(self):
        service = SERVICE.read_text(encoding="utf-8")
        association = ASSOCIATION.read_text(encoding="utf-8")
        view_model = (KOTLIN / "viewmodel/ReiAnixLibraryViewModel.kt").read_text(encoding="utf-8")
        host = (KOTLIN / "ui/host/ReiAnixComposeLibraryHost.kt").read_text(encoding="utf-8")
        main = (ROOT / "main.py").read_text(encoding="utf-8")
        python_service = (ROOT / "core/library_service.py").read_text(encoding="utf-8")

        self.assertIn("reconcileUnmatchedLibraryMetadata()", service)
        self.assertIn('AUTO_MATCH_TERMINAL_STATUSES', service)
        self.assertIn('recordAniListAutoMatchOutcome', service)
        self.assertIn('recordAniListAutoMatchOutcome(', association)
        self.assertIn('service.reconcileUnmatchedLibraryMetadata()', host)
        self.assertIn('LaunchedEffect(catalogSize, scanning)', host)
        self.assertNotIn('anilist', view_model.lower())
        self.assertNotIn('network_error",\n            "rate_limited', service)
        self.assertIn('allow_anilist_metadata=not compose_library_bridge.enabled', main)
        self.assertIn('needs_metadata = allow_anilist_metadata and not materialized', python_service)
        self.assertIn('request_download=True', python_service)

    def test_malformed_graphql_search_data_is_not_reported_as_empty_results(self):
        client = (KOTLIN / "data/anilist/ReiAnixAniListClient.kt").read_text(encoding="utf-8")
        self.assertIn("Missing media array in AniList search response", client)
        self.assertIn("Invalid anime item in AniList search response", client)
        self.assertIn("Missing Media field in AniList detail response", client)

    def test_search_responses_are_guarded_by_request_identity(self):
        source = ANILIST_VM.read_text(encoding="utf-8")
        self.assertIn("val requestId = ++searchRequestId", source)
        self.assertIn("if (requestId == searchRequestId)", source)
        self.assertNotIn("_aniListUiState.value.query == query", source)
        self.assertIn("isSearchLoading", source)
        self.assertIn("isManualAssociationLoading", source)
        self.assertIn("isAutoMatchLoading", source)

    def test_association_preserves_manual_metadata_bookkeeping(self):
        source = ASSOCIATION.read_text(encoding="utf-8")
        self.assertIn("buildSet<String>", source)
        self.assertIn("val remoteMetadataFieldsApplied = values.size() > 0", source)
        self.assertIn('current.metadataSource == "manual" || current.metadataStatus == "manual"', source)
        self.assertIn("if (remoteMetadataFieldsApplied && !metadataIsManual)", source)
        self.assertIn("autoMatchRequestedAnimeIds.add(animeId)", ANILIST_VM.read_text(encoding="utf-8"))

if __name__ == "__main__":
    unittest.main()
