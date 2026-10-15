from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[1]
DATABASE = ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/data/sqlite/ReiAnixSqliteDatabase.kt"
REPOSITORY = ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/data/sqlite/ReiAnixSqliteLibraryRepository.kt"
MUTATIONS = ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/data/sqlite/ReiAnixSqliteLibraryMutations.kt"
CONSUMPTION = ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/data/sqlite/ReiAnixConsumptionService.kt"
VIEW_MODEL = ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/viewmodel/ReiAnixLibraryViewModel.kt"
RUNTIME_TEST = ROOT / "android/app/src/test/kotlin/com/reiflix/reiflix_local/data/sqlite/ReiAnixSqliteRepositoryRuntimeTest.kt"


class KotlinSqliteDataLayerContractTests(unittest.TestCase):
    def test_native_mutations_extend_the_canonical_repository_without_a_second_store(self):
        database = DATABASE.read_text(encoding="utf-8")
        repository = REPOSITORY.read_text(encoding="utf-8")
        mutations = MUTATIONS.read_text(encoding="utf-8")

        self.assertIn('File(File(appContext.filesDir, "data"), DATABASE_FILE_NAME)', database)
        self.assertIn('DATABASE_FILE_NAME = "library.sqlite3"', database)
        self.assertIn("class ReiAnixSqliteLibraryRepository(", repository)
        self.assertIn("suspend fun ReiAnixSqliteLibraryRepository.setAnimeFavorite", mutations)
        self.assertIn("suspend fun ReiAnixSqliteLibraryRepository.setAnimePersonalNote", mutations)
        self.assertIn("suspend fun ReiAnixSqliteLibraryRepository.setAnimeUserTags", mutations)
        self.assertIn("suspend fun ReiAnixSqliteLibraryRepository.setManualMetadataByLookupTitle", mutations)
        self.assertIn("suspend fun ReiAnixSqliteLibraryRepository.setEpisodeWatchedState", mutations)
        self.assertIn("setAniListMatchByLookupTitle", mutations)
        self.assertIn("clearAniListMatchByLookupTitle", mutations)
        self.assertIn("setPendingAniListMatch", mutations)
        self.assertIn("resolvePendingAniListMatch", mutations)
        self.assertIn("clearAniListMetadataCache", mutations)
        self.assertNotIn("RoomDatabase", mutations)
        self.assertNotIn("CREATE TABLE", mutations.upper())
        self.assertNotIn("CREATE_IF_NECESSARY", database)

    def test_domain_mutations_are_transactional_and_external_values_are_bound(self):
        mutations = MUTATIONS.read_text(encoding="utf-8")

        for function in (
            "setAnimeFavorite",
            "toggleAnimePinned",
            "setAnimePersonalNote",
            "setAnimeUserTags",
            "setManualMetadataByLookupTitle",
            "clearManualMetadataByLookupTitle",
            "setEpisodeWatchedState",
            "setAniListMatchByLookupTitle",
            "setPendingAniListMatch",
            "clearAniListMatchByLookupTitle",
        ):
            start = mutations.find(f"fun ReiAnixSqliteLibraryRepository.{function}(")
            self.assertGreaterEqual(start, 0, f"Missing native mutation: {function}")
            next_function = mutations.find("\nsuspend fun ReiAnixSqliteLibraryRepository.", start + 1)
            body = mutations[start:next_function if next_function >= 0 else len(mutations)]
            self.assertIn("database.transaction", body, f"{function} must use a transaction")

        self.assertIn('WHERE lookup_title = ?', mutations)
        self.assertIn('WHERE id = ?', mutations)
        self.assertIn('WHERE path = ?', mutations)
        self.assertIn('WHERE media_identity = ?', mutations)
        self.assertNotIn('WHERE lookup_title = "${', mutations)

    def test_watched_state_mutation_is_native_and_python_bridge_only_republishes(self):
        consumption = CONSUMPTION.read_text(encoding="utf-8")
        view_model = VIEW_MODEL.read_text(encoding="utf-8")
        start = view_model.rfind("fun setEpisodeWatched(")
        self.assertGreaterEqual(start, 0)
        end = view_model.find("\n    fun openEpisode(", start)
        self.assertGreater(end, start)
        body = view_model[start:end]

        self.assertIn("suspend fun setWatched(", consumption)
        self.assertIn("consumptionService.setWatched(episodeId, watched)", body)
        self.assertIn("repository.publishCatalog()", body)
        self.assertNotIn("repository.setEpisodeWatched(", body)
        self.assertIn("CancellationException", body)
        self.assertIn("_consumptionMutationError", body)

    def test_jvm_contract_test_covers_native_user_fields_and_path_equivalence(self):
        tests = RUNTIME_TEST.read_text(encoding="utf-8")
        for marker in (
            "nativeLibraryMutationsPreserveCanonicalStateAndPathIdentity",
            "findEpisodeByPathOrFileUri",
            "setManualMetadataByLookupTitle",
            "setAnimePersonalNote",
            "setAnimeUserTags",
            "setEpisodeWatchedState",
        ):
            self.assertIn(marker, tests)
        workflow = (ROOT / ".github/workflows/kotlin_jvm_tests.yml").read_text(encoding="utf-8")
        self.assertIn("workflow_dispatch:", workflow)


if __name__ == "__main__":
    unittest.main()
