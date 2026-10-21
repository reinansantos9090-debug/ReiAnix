from pathlib import Path
import re
import unittest


ROOT = Path(__file__).resolve().parents[1]
NAVIGATION = ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/ui/navigation/ReiAnixNavigation.kt"
COMPOSE_HOST = ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/ui/host/ReiAnixComposeLibraryHost.kt"
SETTINGS = ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/ui/settings/ReiAnixSettings.kt"
HOME = ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/ui/home/ReiAnixHome.kt"
DETAILS = ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/ui/details/ReiAnixDetails.kt"
DISCOVERY_DIALOGS = ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/ui/discovery/ReiAnixDiscoveryDialogs.kt"
LIBRARY_VIEW_MODEL = ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/viewmodel/ReiAnixLibraryViewModel.kt"
SQLITE_MUTATIONS = ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/data/sqlite/ReiAnixSqliteLibraryMutations.kt"
COVERAGE = ROOT / "docs/PROMPT_23_COMPOSE_INTERFACE_COVERAGE.md"


class Prompt23ComposeInterfaceMigrationContract(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.navigation = NAVIGATION.read_text(encoding="utf-8")
        cls.compose_host = COMPOSE_HOST.read_text(encoding="utf-8")
        cls.settings = SETTINGS.read_text(encoding="utf-8")
        cls.home = HOME.read_text(encoding="utf-8")
        cls.details = DETAILS.read_text(encoding="utf-8")
        cls.discovery_dialogs = DISCOVERY_DIALOGS.read_text(encoding="utf-8")
        cls.library_view_model = LIBRARY_VIEW_MODEL.read_text(encoding="utf-8")
        cls.sqlite_mutations = SQLITE_MUTATIONS.read_text(encoding="utf-8")

    def test_native_entrypoint_is_kotlin_and_legacy_flet_entrypoint_is_absent(self):
        entrypoint = (ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/main.kt").read_text(encoding="utf-8")
        activity = (ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/MainActivity.kt").read_text(encoding="utf-8")
        self.assertIn("internal object MainEntryPoint", entrypoint)
        self.assertIn("dependencies.bootstrap.start()", entrypoint)
        self.assertIn("MainEntryPoint.onActivityCreated(", activity)
        self.assertNotIn("import flet", entrypoint.casefold())
        self.assertNotIn("from views.", entrypoint)
        self.assertFalse((ROOT / "main.py").exists())

    def test_primary_screens_are_wired_to_native_compose_destinations(self):
        for route in ("HOME", "LIBRARY", "ORGANIZE", "DETAILS", "COLLECTOR", "SETTINGS"):
            self.assertIn("ReiAnixRoutes." + route, self.navigation)
        for native_screen in ("ReiAnixHomeRoute(", "ReiAnixLibraryRoute(", "ReiAnixOrganizeRoute(", "ReiAnixDetailsRoute(", "ReiAnixCollectorJourneyRoute(", "ReiAnixSettingsRoute("):
            self.assertIn(native_screen, self.navigation + self.compose_host)
        self.assertIn("ReiAnixNavigationHost", self.compose_host)
        self.assertIn("rememberNavController()", self.compose_host)
        self.assertIn("navController.popBackStack()", self.compose_host)
        self.assertNotIn("page.views", self.compose_host)
        self.assertNotIn("flutterEngine", self.compose_host)

    def test_android_compose_owns_root_and_nested_settings_without_hidden_legacy_views(self):
        entrypoint = (ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/main.kt").read_text(encoding="utf-8")
        activity = (ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/MainActivity.kt").read_text(encoding="utf-8")
        self.assertIn("ReiAnixNavigationHost", self.compose_host)
        self.assertIn("ReiAnixSettingsRoute(", self.compose_host)
        self.assertIn("var selectedCategory by", self.settings)
        self.assertIn("BackHandler(enabled = selectedCategory != null)", self.settings)
        self.assertIn("onBack = { selectedCategory = null }", self.settings)
        self.assertIn("composeLibraryHost", activity)
        self.assertNotIn("page.views", entrypoint + activity + self.compose_host)
        self.assertNotIn("SettingsView.build(", self.compose_host)
    def test_every_declared_compose_settings_category_is_managed_natively(self):
        model_start = self.settings.index("fun defaultCategories():")
        managed_start = self.settings.index("private val NativeManagedSettingsCategories")
        model_block = self.settings[model_start:managed_start]
        declared = set(re.findall(
            r'ReiAnixSettingsCategoryUiModel\("([^"]+)"',
            model_block,
        ))
        managed_end = self.settings.index("\n)", managed_start)
        managed_block = self.settings[managed_start:managed_end]
        managed = set(re.findall(r'^\s*"([^"]+)"\s*,?$', managed_block, re.MULTILINE))
        self.assertTrue(declared, "No native settings categories were found")
        self.assertEqual(declared, managed)
        self.assertIn("onOpenCategory = { label ->", self.settings)
        self.assertIn("selectedCategory = label", self.settings)

    def test_compose_destinations_are_wired_to_the_existing_native_host(self):
        for route in (
            "HOME", "LIBRARY", "MY_LIST", "ORGANIZE", "SEARCH", "SETTINGS",
            "STORAGE", "RECOVERY", "COLLECTOR", "DETAILS", "PLAYER",
        ):
            self.assertIn("ReiAnixRoutes." + route, self.navigation)
        for native_screen in (
            "ReiAnixOrganizeRoute(",
            "ReiAnixSettingsRoute(",
            "ReiAnixRecoveryRoute(",
            "ReiAnixStorageRoute(",
            "navigateToCollector()",
        ):
            self.assertIn(native_screen, self.compose_host)
        self.assertIn("ReiAnixHomeRoute(", self.navigation)
        self.assertIn("ReiAnixLibraryRoute(", self.navigation)
        self.assertIn("ReiAnixSearchRoute(", self.navigation)
        self.assertIn("ReiAnixDetailsRoute(", self.navigation)

    def test_home_discovery_tools_use_native_compose_queries(self):
        self.assertIn('text = "Linha do tempo"', self.home)
        self.assertIn('text = "Durações incomuns"', self.home)
        self.assertIn("ReiAnixTimelineDialog(", self.home)
        self.assertIn("ReiAnixDurationAnomalyDialog(", self.home)
        self.assertIn("viewModel.queryNativeTimelineGroups()", self.discovery_dialogs)
        self.assertIn("viewModel.queryNativeDurationAnomalies()", self.discovery_dialogs)
        self.assertIn("viewModel.queryNativeMarathonPlan(animeId, currentPath)", self.discovery_dialogs)
        self.assertIn("LaunchedEffect(", self.discovery_dialogs)
        self.assertIn("Tentar novamente", self.discovery_dialogs)
        self.assertIn("loading = loading", self.discovery_dialogs)
        self.assertIn("error = error", self.discovery_dialogs)

    def test_details_actions_mutate_the_canonical_sqlite_store(self):
        for label in ("Maratona", "Adicionar nota pessoal", "Adicionar tags", "Corrigir identificação"):
            self.assertIn(label, self.details)
        for callback in (
            "viewModel::toggleAnimePinnedNative",
            "viewModel::saveAnimePersonalNoteNative",
            "viewModel::setEpisodeManualIdentificationNative",
            "viewModel::saveAnimeUserTagsNative",
        ):
            self.assertIn(callback, self.details)
        for mutation in (
            "toggleAnimePinned",
            "setAnimePersonalNote",
            "setAnimeUserTags",
            "setEpisodeManualIdentification",
        ):
            self.assertIn(mutation, self.sqlite_mutations)
            self.assertIn(mutation, self.library_view_model)
        self.assertIn('put("manual_override", 1)', self.sqlite_mutations)
        self.assertIn('put("identification_source", "manual")', self.sqlite_mutations)
        self.assertIn("refreshNativeCatalog()", self.library_view_model)
        self.assertIn("personalNote = anime.personalNote", (ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/ui/model/ReiAnixDetailsUiModels.kt").read_text(encoding="utf-8"))

    def test_gacha_is_explicitly_blocked_until_native_filter_parity_exists(self):
        report = COVERAGE.read_text(encoding="utf-8")
        self.assertIn("Gacha do acervo", report)
        for filter_name in (
            "query", "state", "genre", "sort", "tag", "media_type",
            "season", "episode_type", "availability", "metadata", "artwork",
        ):
            self.assertIn(filter_name, report)
        self.assertIn("Bloqueado por dependência concreta", report)

    def test_coverage_matrix_records_the_actual_cutover_and_remaining_fallback(self):
        report = COVERAGE.read_text(encoding="utf-8")
        for token in (
            "Prompt 23/29",
            "views/home_view.py",
            "views/details_view.py",
            "views/organize_view.py",
            "views/settings_view.py",
            "views/collector_view.py",
            "views/recovery_view.py",
            "ReiAnixSettingsRoute",
            "fallback não Android",
            "não declara o projeto 100% Kotlin",
            "matriz",
        ):
            self.assertIn(token, report)


if __name__ == "__main__":
    unittest.main()
