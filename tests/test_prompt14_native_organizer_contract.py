"""Static contracts for the native Kotlin Organize/domain migration."""

import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
KOTLIN = ROOT / "android" / "app" / "src" / "main" / "kotlin" / "com" / "reiflix" / "reiflix_local"
CLASSIFIER = KOTLIN / "data" / "organize" / "ReiAnixGenreClassifier.kt"
REGISTRY = KOTLIN / "data" / "organize" / "ReiAnixGenreRegistry.kt"
DEPENDENCIES = KOTLIN / "ReiAnixAppDependencies.kt"
ANILIST = KOTLIN / "data" / "anilist" / "ReiAnixAniListIntegrationService.kt"
VIEWMODEL = KOTLIN / "viewmodel" / "ReiAnixLibraryViewModel.kt"
ORGANIZE_UI = KOTLIN / "ui" / "organize" / "ReiAnixOrganize.kt"
MAIN = ROOT / "main.py"
SQLITE_REPOSITORY = KOTLIN / "data" / "sqlite" / "ReiAnixSqliteLibraryRepository.kt"


class NativeGenreMigrationContractTests(unittest.TestCase):
    def read(self, path):
        return path.read_text(encoding="utf-8")

    def test_classifier_ports_all_legacy_rule_families_without_io(self):
        classifier = self.read(CLASSIFIER)
        for label, term in (
            ("Ação", "naruto"),
            ("Aventura", "one piece"),
            ("Fantasia", "isekai"),
            ("Romance", "horimiya"),
            ("Comédia", "kaguya"),
            ("Terror", "tokyo ghoul"),
            ("Esporte", "haikyuu"),
        ):
            with self.subTest(label=label):
                self.assertIn(f'"{label}"', classifier)
                self.assertIn(f'"{term}"', classifier)
        self.assertNotIn("HttpURLConnection", classifier)
        self.assertNotIn("AniListClient", classifier)

    def test_registry_uses_only_existing_v29_tables_and_no_schema_ddl(self):
        registry = self.read(REGISTRY)
        for table in ("genres", "genre_aliases", "anime_genres", "anime"):
            self.assertIn(table, registry)
        self.assertIn("database.transaction", registry)
        self.assertIn("database.read", registry)
        self.assertIn("replaceSource", registry)
        self.assertIn("COUNT(DISTINCT ag.anime_id)", registry)
        self.assertNotIn("CREATE TABLE", registry.upper())
        self.assertNotIn("DROP TABLE", registry.upper())
        self.assertNotIn("openOrCreateDatabase", registry)

    def test_explicit_classification_preserves_authoritative_and_existing_genres(self):
        registry = self.read(REGISTRY)
        self.assertIn("classifyMissingLocalGenres", registry)
        self.assertIn("a.anilist_id", registry)
        self.assertIn("metadata_manual_fields", registry)
        self.assertIn("AUTHORITATIVE_SOURCES", registry)
        self.assertIn("AND NOT EXISTS (SELECT 1 FROM anime_genres", registry)
        self.assertIn("changedAnimeIds", registry)
        self.assertIn("CLASSIFICATION_BATCH_SIZE", registry)
        self.assertIn("lastScannedId", registry)
        self.assertIn("while (candidates.size < limit)", registry)
        self.assertIn("manualFieldsOwnGenres(candidate.manualFields)", registry)

    def test_one_shared_database_registry_is_wired_to_anilist_and_viewmodel(self):
        dependencies = self.read(DEPENDENCIES)
        anilist = self.read(ANILIST)
        viewmodel = self.read(VIEWMODEL)
        self.assertIn("ReiAnixGenreRegistry(sqliteDatabase)", dependencies)
        self.assertIn("genreRegistry = genreRegistry", dependencies)
        self.assertIn("genreRegistry: ReiAnixGenreRegistry? = null", anilist)
        self.assertIn("effectiveGenreRegistry.syncAnime", anilist)
        self.assertIn("ReiAnixGenreRegistry(nativeLibraryRepository.database)", viewmodel)
        self.assertIn("nativeGenreRegistry.classifyMissingLocalGenres()", viewmodel)

    def test_anilist_registry_sync_respects_manually_owned_genres(self):
        anilist = self.read(ANILIST)
        self.assertIn(
            "remote.genres.isNotEmpty() && !manualFieldOwnsGenres(updated.metadataManualFieldsJson)",
            anilist,
        )
        self.assertIn("private fun manualFieldOwnsGenres", anilist)

    def test_compose_organize_exposes_native_classification_and_status(self):
        ui = self.read(ORGANIZE_UI)
        viewmodel = self.read(VIEWMODEL)
        self.assertIn("Classificar gêneros locais ausentes", ui)
        self.assertIn("onClassifyLocalGenres = viewModel::classifyMissingLocalGenres", ui)
        self.assertIn("organizerGenreOperationState", ui)
        self.assertIn("isClassifyingLocalGenres", ui)
        self.assertIn("ReiAnixGenreRegistry", viewmodel)

    def test_catalog_projection_deduplicates_genres_shared_by_multiple_sources(self):
        repository = self.read(SQLITE_REPOSITORY)
        self.assertIn("SELECT DISTINCT ag.anime_id, g.id, g.canonical_name", repository)
        self.assertIn("COUNT(DISTINCT ag.anime_id)", self.read(REGISTRY))

    def test_python_event_only_refreshes_projection_and_does_not_repeat_genre_writes(self):
        main = self.read(MAIN)
        start = main.index("if event_type == 'native_genres_committed':")
        end = main.index("if event_type == 'artwork_published':", start)
        handler = main[start:end]
        self.assertIn("compose_library_bridge.request_publish", handler)
        self.assertIn("native_genres_updated:", handler)
        self.assertNotIn("genre_registry", handler)
        self.assertNotIn("sync_anime", handler)
        self.assertNotIn("UPDATE anime", handler)

    def test_prompt_migration_doc_records_cutover_limitations(self):
        document = self.read(ROOT / "docs" / "KOTLIN_ORGANIZER_MIGRATION.md")
        self.assertIn("schema v29", document)
        self.assertIn("não é uma remoção geral de Python/Flet", document)
        self.assertIn("core/collector_journey.py", document)
        self.assertIn("workflow_dispatch", document)


if __name__ == "__main__":
    unittest.main()
