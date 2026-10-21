from pathlib import Path
import unittest



ROOT = Path(__file__).resolve().parents[1]
MAIN_ACTIVITY = ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/MainActivity.kt"
PLAYER_ACTIVITY = ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/NativePlayerActivity.kt"
MANIFEST = ROOT / "android/app/src/main/AndroidManifest.xml"
MAIN = ROOT / "main.py"


class BackLifecycleTests(unittest.TestCase):
    def read(self, path):
        return path.read_text(encoding="utf-8")

    def test_main_activity_restores_native_compose_shell_for_unconsumed_back(self):
        source = self.read(MAIN_ACTIVITY)
        self.assertIn("import androidx.activity.ComponentActivity", source)
        self.assertIn("import androidx.activity.OnBackPressedCallback", source)
        self.assertEqual(source.count("onBackPressedDispatcher.addCallback("), 1)
        self.assertIn("BACK_PHYSICAL_RECEIVED", source)
        self.assertIn("BACK_COMPOSE_SHELL_RESTORED", source)
        self.assertNotIn("BACK_FLUTTER_POP_SENT", source)
        self.assertNotIn("engine.navigationChannel.popRoute()", source)
        self.assertNotIn("flutterEngine", source)
        self.assertNotIn('put("type", "android_back")', source)
        handler = source[source.index("private fun installSystemBackHandler"):source.index("private fun persistedSafTreeUris")]
        self.assertNotIn("finish()", handler)
        self.assertLess(handler.index("systemBackDispatchPosted = true"), handler.index("composeLibraryHost.handleBack()"))
        self.assertIn("if (systemBackDispatchPosted)", handler)
        self.assertIn("composeLibraryHost.show(ReiAnixRoutes.HOME, resetBackStack = true)", handler)
        self.assertIn("if (::composeLibraryHost.isInitialized)", handler)
        self.assertIn("try {", handler)
        self.assertIn("finally {", handler)
        self.assertIn("window.decorView.post { systemBackDispatchPosted = false }", handler)
        self.assertLess(handler.index("composeLibraryHost.show(ReiAnixRoutes.HOME, resetBackStack = true)"), handler.index("finally {"))
        self.assertIn("if (composeLibraryHost.handleBack())", handler)


    def test_native_back_callback_dispatches_one_logical_back_operation(self):
        source = self.read(MAIN_ACTIVITY)
        handler = source[source.index("private fun installSystemBackHandler"):source.index("private fun persistedSafTreeUris")]
        self.assertEqual(1, handler.count("composeLibraryHost.handleBack()"))
        self.assertIn("systemBackDispatchPosted = true", handler)
        self.assertIn("BACK_PHYSICAL_RECEIVED", source)
        self.assertIn("BACK_COMPOSE_SHELL_RESTORED", source)
        self.assertNotIn("page.on_view_pop", source)

    def test_compose_is_the_native_navigation_surface_for_system_back(self):
        navigation = self.read(ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/ui/navigation/ReiAnixNavigation.kt")
        host = self.read(ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/ui/host/ReiAnixComposeLibraryHost.kt")
        activity = self.read(MAIN_ACTIVITY)
        self.assertIn("rememberNavController()", navigation)
        self.assertIn("NavHost(", navigation)
        self.assertIn("ReiAnixRoutes.HOME", navigation)
        self.assertIn("ReiAnixRoutes.SETTINGS", navigation)
        self.assertIn("ReiAnixNavigationHost", host)
        self.assertIn("composeLibraryHost.handleBack()", activity)
        self.assertNotIn("page.views.clear()", activity)
        self.assertNotIn("page.on_view_pop", activity)

    def test_compose_nav_controller_remains_single_logical_source_of_truth(self):
        host = self.read(ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/ui/host/ReiAnixComposeLibraryHost.kt")
        navigation = self.read(ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/ui/navigation/ReiAnixNavigation.kt")
        settings = self.read(ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/ui/settings/ReiAnixSettings.kt")
        self.assertIn("rememberNavController()", host)
        self.assertIn("ReiAnixNavigationHost", host)
        self.assertIn("NavHost(", navigation)
        self.assertIn("fun NavHostController.navigateToTopLevel(", navigation)
        self.assertIn("controller.popBackStack()", host)
        self.assertIn("BackHandler(enabled = selectedCategory != null)", settings)

    def test_manifest_uses_single_task_and_predictive_back_enabled(self):
        manifest = self.read(MANIFEST)
        self.assertIn('android:launchMode="singleTask"', manifest)
        self.assertIn('android:documentLaunchMode="never"', manifest)
        self.assertIn('android:enableOnBackInvokedCallback="true"', manifest)

    def test_host_and_player_have_distinct_system_bar_policies(self):
        main = self.read(MAIN_ACTIVITY)
        player = self.read(PLAYER_ACTIVITY)
        self.assertIn("systemUiController.applyApplicationImmersivePolicy(useContextAppearance = false)", main)
        exit_start = player.index("private fun restoreSystemUiBeforeExit")
        exit_end = player.index("private fun applyImmersiveAfterLayout", exit_start)
        self.assertIn("systemUiController.applyNormal(useContextAppearance = false)", player[exit_start:exit_end])
        self.assertIn("systemUiController.applyImmersive()", player)

    def test_compose_root_back_uses_native_exit_policy_and_restores_hidden_host(self):
        host = self.read(ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/ui/host/ReiAnixComposeLibraryHost.kt")
        activity = self.read(MAIN_ACTIVITY)
        root = host[host.index("fun handleBack(): Boolean"):host.index("fun dispose()", host.index("fun handleBack(): Boolean"))]
        self.assertIn("Pressione voltar novamente para sair", host)
        self.assertIn("Toast.makeText", host)
        self.assertIn("HOME_DOUBLE_BACK_WINDOW_MS", root)
        self.assertIn("activity.finish()", root)
        self.assertNotIn("Python NavigationController", root)
        self.assertIn("composeLibraryHost.show(ReiAnixRoutes.HOME, resetBackStack = true)", activity)
        self.assertIn("BACK_COMPOSE_SHELL_RESTORED", activity)
        self.assertNotIn("engine.navigationChannel.popRoute()", activity)
        self.assertNotIn("flutterEngine", activity)

    def test_player_finish_is_idempotent_while_exiting(self):
        source = self.read(PLAYER_ACTIVITY)
        start = source.index("private fun finishPlayer")
        end = source.index("private fun updateEpisodeNavigationButtons", start)
        finish = source[start:end]
        self.assertIn("sessionState == SessionState.EXITING || sessionState == SessionState.DESTROYED", finish)
        self.assertIn("sessionState = SessionState.EXITING", finish)
        self.assertEqual(finish.count("finish()"), 1)

    def test_player_keeps_native_back_dispatch_and_finishes_itself(self):
        source = self.read(PLAYER_ACTIVITY)
        self.assertIn("onBackPressedDispatcher.addCallback(", source)
        self.assertIn('finishPlayer("back_button")', source)
        self.assertNotIn('finishPlayer("android_back")', source)
        self.assertIn("player.release()", source)
        self.assertIn("setResult(", source)
        self.assertNotIn("navigationChannel.popRoute()", source)
        self.assertNotIn("navigate_back(", source)

        handler_start = source.index("private fun installBackHandler")
        handler_end = source.index("private fun applyRootInsets", handler_start)
        handler = source[handler_start:handler_end]
        self.assertLess(handler.index("if (moreVisible)"), handler.index('finishPlayer("back_button")'))
        self.assertIn('findViewByTag<View>("reiflix_more_panel")?.visibility = View.GONE', handler)

    def test_legacy_back_overrides_are_absent_from_both_activities(self):
        for path in (MAIN_ACTIVITY, PLAYER_ACTIVITY):
            source = self.read(path)
            self.assertNotIn("override fun onBackPressed()", source)
            self.assertNotIn("KEYCODE_BACK", source)


    def test_settings_categories_are_nested_under_compose_navigation(self):
        host = self.read(ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/ui/host/ReiAnixComposeLibraryHost.kt")
        navigation = self.read(ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/ui/navigation/ReiAnixNavigation.kt")
        settings = self.read(ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/ui/settings/ReiAnixSettings.kt")
        activity = self.read(MAIN_ACTIVITY)
        self.assertIn("ReiAnixSettingsRoute(", host)
        self.assertIn("ReiAnixRoutes.SETTINGS", navigation)
        self.assertIn("rememberSaveable", settings)
        self.assertIn("BackHandler(enabled = selectedCategory != null)", settings)
        self.assertIn("selectedCategory = null", settings)
        self.assertNotIn("composeSettingsHost", activity)

    def test_back_has_no_generic_activity_exit_shortcut(self):
        activity = self.read(MAIN_ACTIVITY)
        player = self.read(PLAYER_ACTIVITY)
        entrypoint = self.read(ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/main.kt")
        self.assertNotIn("finishAffinity(", activity + player + entrypoint)
        self.assertEqual(1, activity.count("onBackPressedDispatcher.addCallback("))
        self.assertIn("composeLibraryHost.handleBack()", activity)
        self.assertIn("finishPlayer(\"back_button\")", player)
        self.assertNotIn("page.on_view_pop", entrypoint)

    def test_native_navigation_state_is_owned_by_compose_and_saved_state(self):
        navigation = self.read(ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/ui/navigation/ReiAnixNavigation.kt")
        settings = self.read(ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/ui/settings/ReiAnixSettings.kt")
        host = self.read(ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/ui/host/ReiAnixComposeLibraryHost.kt")
        self.assertIn("rememberNavController()", navigation)
        self.assertIn("NavHost(", navigation)
        self.assertIn("rememberSaveable", settings)
        self.assertIn("ReiAnixRoutes.DETAILS", navigation)
        self.assertIn("ReiAnixRoutes.SETTINGS", navigation)
        self.assertIn("navController.popBackStack()", navigation + host)
        self.assertNotIn("navigation_state.json", navigation + settings + host)
    def test_android_host_uses_one_lifecycle_back_callback_and_reapplies_ui_without_resetting_python_stack(self):
        activity = self.read(MAIN_ACTIVITY)
        manifest = self.read(MANIFEST)
        self.assertEqual(activity.count("onBackPressedDispatcher.addCallback"), 1)
        self.assertIn("override fun onResume()", activity)
        self.assertIn("override fun onDestroy()", activity)
        self.assertNotIn("finishAffinity(", activity)
        self.assertIn('android:launchMode="singleTask"', manifest)
        self.assertIn('android:documentLaunchMode="never"', manifest)
        self.assertIn('android:enableOnBackInvokedCallback="true"', manifest)

    def test_player_launch_is_single_flight(self):
        source = self.read(MAIN_ACTIVITY)
        self.assertIn("previousActiveRequestId", source)
        self.assertIn("reusingPlayerActivity", source)
        self.assertIn("PLAY_HANDOFF_DUPLICATE", source)
        self.assertIn("activePlayerRequestId = requestId.takeIf { it.isNotBlank() }", source)
        self.assertIn("FLAG_ACTIVITY_REORDER_TO_FRONT", source)


    def test_native_startup_does_not_restore_a_legacy_python_navigation_route(self):
        entrypoint = self.read(ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/main.kt")
        activity = self.read(MAIN_ACTIVITY)
        self.assertIn("dependencies.bootstrap.start()", entrypoint)
        self.assertIn("dispatchStartupDiscoveryAfterBootstrap()", activity)
        self.assertNotIn("navigation_state.json", entrypoint + activity)
        self.assertNotIn("NavigationController", entrypoint + activity)
        self.assertIn("ReiAnixRoutes.HOME", activity)
        self.assertIn("ReiAnixRoutes.RECOVERY", activity)

    def test_nested_settings_categories_use_native_saved_selection(self):
        settings = self.read(ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/ui/settings/ReiAnixSettings.kt")
        navigation = self.read(ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/ui/navigation/ReiAnixNavigation.kt")
        self.assertIn("var selectedCategory by androidx.compose.runtime.saveable.rememberSaveable", settings)
        self.assertIn("onBack = { selectedCategory = null }", settings)
        self.assertIn("selectedCategory = label", settings)
        self.assertIn("ReiAnixRoutes.SETTINGS", navigation)
        self.assertNotIn("settings_path_override", settings)
    def test_main_resume_discovery_requires_persisted_saf_tree(self):
        source = self.read(MAIN_ACTIVITY)
        start = source.index("override fun onResume()")
        end = source.index("override fun onPause()", start)
        resume = source[start:end]
        self.assertIn("val shouldDiscover = !startupDiscoveryTriggered", resume)
        self.assertIn("if (shouldDiscover && hasPersistedSafTree)", resume)
        self.assertIn('publishScanRequest(', resume)
        self.assertIn('"STARTUP"', resume)
        self.assertIn('"STARTUP_SCAN_SKIPPED"', resume)
        self.assertIn('result = "no_persisted_saf_tree"', resume)
        self.assertNotIn('result = "explicit_scan_required"', resume)
        self.assertNotIn("scanMediaStore(null)", resume)
        self.assertNotIn("scanAllStorage(null)", resume)
        self.assertNotIn("scanTree(tree, null)", resume)
        self.assertIn("dispatchStartupDiscoveryAfterBootstrap()", source)


if __name__ == "__main__":
    unittest.main()
