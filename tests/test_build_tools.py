import shutil
import subprocess
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path
import json
import re
import xml.etree.ElementTree as ET


ROOT = Path(__file__).resolve().parents[1]
VERIFY = ROOT / "scripts" / "verify_android_host.py"
PREPARE_TEMPLATE = ROOT / "scripts" / "prepare_flet_template.py"
DESCRIPTORS = (
    b"Lcom/reiflix/reiflix_local/MainActivity;",
    b"Lcom/reiflix/reiflix_local/ui/host/ReiAnixComposeLibraryHost;",
    b"Lcom/reiflix/reiflix_local/viewmodel/ReiAnixSettingsViewModel;",
    b"Lcom/reiflix/reiflix_local/bridge/NativeMailbox;",
    b"Lcom/reiflix/reiflix_local/bridge/NativeRequestState;",
    b"Lcom/reiflix/reiflix_local/scanner/SafScanner;",
    b"Lcom/reiflix/reiflix_local/scanner/MediaStoreScanner;",
    b"Lcom/reiflix/reiflix_local/scanner/BroadStorageScanner;",
    b"Lcom/reiflix/reiflix_local/storage/NativeIndex;",
    b"Lcom/reiflix/reiflix_local/scanner/NativeScanController;",
    b"Lcom/reiflix/reiflix_local/NativePlayerActivity;",
    b"Lcom/reiflix/reiflix_local/player/NativePlayerRequest;",
    b"Lcom/reiflix/reiflix_local/storage/VideoThumbnailExtractor;",
    b"Lcom/reiflix/reiflix_local/bridge/GoogleIdentity;",
    b"Lcom/reiflix/reiflix_local/ui/theme/ReiAnixComposeThemeKt;",
    b"Lcom/reiflix/reiflix_local/ui/ReiAnixComposeRootKt;",
    b"Lcom/reiflix/reiflix_local/viewmodel/ReiAnixViewModel;",
)


class AndroidHostVerificationTests(unittest.TestCase):
    def _apk(self, descriptors):
        path = Path(self.tmp.name) / "app.apk"
        with zipfile.ZipFile(path, "w") as archive:
            archive.writestr("AndroidManifest.xml", b"manifest")
            archive.writestr("classes.dex", b"dex\n" + b"\n".join(descriptors))
        return path

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()

    def tearDown(self):
        self.tmp.cleanup()

    def test_accepts_apk_with_all_native_host_classes(self):
        result = subprocess.run([sys.executable, str(VERIFY), str(self._apk(DESCRIPTORS))],
                                cwd=ROOT, capture_output=True, text=True)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("Verified native ReiAnix host", result.stdout)


    def test_compose_packaging_verifier_requires_compiled_classes_and_dex(self):
        script = ROOT / "scripts" / "verify_compose_packaging.py"
        with tempfile.TemporaryDirectory() as d:
            root = Path(d)
            classes = root / "android-build"
            for relative in (
                "tmp/kotlin-classes/release/com/reiflix/reiflix_local/ui/theme/ReiAnixComposeThemeKt.class",
                "tmp/kotlin-classes/release/com/reiflix/reiflix_local/ui/ReiAnixComposeRootKt.class",
                "tmp/kotlin-classes/release/com/reiflix/reiflix_local/viewmodel/ReiAnixViewModel.class",
                "tmp/kotlin-classes/release/com/reiflix/reiflix_local/viewmodel/ReiAnixSettingsViewModel.class",
            ):
                path = classes / relative
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_bytes(b"class")
            apk = root / "app.apk"
            with zipfile.ZipFile(apk, "w") as archive:
                archive.writestr("AndroidManifest.xml", b"manifest")
                archive.writestr("classes.dex", b"\\n".join((
                    b"Lcom/reiflix/reiflix_local/ui/theme/ReiAnixComposeThemeKt;",
                    b"Lcom/reiflix/reiflix_local/ui/ReiAnixComposeRootKt;",
                    b"Lcom/reiflix/reiflix_local/viewmodel/ReiAnixViewModel;",
                                    b"Lcom/reiflix/reiflix_local/viewmodel/ReiAnixSettingsViewModel;",
                )))
            result = subprocess.run(
                [sys.executable, str(script), str(apk), "--classes-root", str(classes)],
                cwd=ROOT, capture_output=True, text=True,
            )
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertIn("COMPOSE_COMPILED_CLASSES=PASS", result.stdout)
            self.assertIn("COMPOSE_APK_DEX=PASS", result.stdout)

    def test_rejects_stock_apk_without_native_host_classes(self):
        result = subprocess.run([sys.executable, str(VERIFY), str(self._apk(DESCRIPTORS[:1]))],
                                cwd=ROOT, capture_output=True, text=True)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("Native ReiAnix host was not packaged", result.stderr)

    def test_video_thumbnail_extractor_is_local_bounded_and_deduplicated(self):
        source = (ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/storage/VideoThumbnailExtractor.kt").read_text(encoding="utf-8")
        for token in (
            "MediaMetadataRetriever",
            "setDataSource(context, uri)",
            "setDataSource(uri.path",
            "getScaledFrameAtTime",
            "retriever.release()",
            "bitmap.recycle()",
            "FileOutputStream",
            "renameTo(target)",
            "ConcurrentHashMap",
            "mediaIdentity",
            "MAX_CACHE_BYTES",
            "MAX_FRAME_DIMENSION",
            "320",
            "durationMs",
            "METADATA_KEY_VIDEO_ROTATION",
            "Matrix().apply",
            "postRotate",
        ):
            self.assertIn(token, source)
        self.assertNotIn("Dispatchers.Main", source)

    def test_workflow_prepares_a_real_template_and_keeps_host_gate(self):
        workflow = (ROOT / ".github" / "workflows" / "build_apk.yml").read_text(encoding="utf-8")
        self.assertIn("https://github.com/flet-dev/flet/releases/download/v0.86.5/flet-build-template.zip", workflow)
        self.assertIn("--template \"$GITHUB_WORKSPACE/build/flet-build-template\"", workflow)
        self.assertIn("--overlay \"$GITHUB_WORKSPACE/android\"", workflow)
        self.assertIn('--build-number "$GITHUB_RUN_NUMBER"', workflow)
        self.assertIn('--build-version "0.2.1"', workflow)
        self.assertIn("Configure Android release signing", workflow)
        self.assertIn("FLET_ANDROID_SIGNING_KEY_STORE", workflow)
        self.assertNotIn("flet build apk --template .", workflow)
        self.assertIn('python scripts/verify_android_host.py "$apk"', workflow)
        self.assertIn("Verify Compose compiled classes and APK DEX packaging", workflow)
        self.assertIn('python scripts/verify_compose_packaging.py "$apk" --classes-root "$classes_root"', workflow)
        self.assertIn('classes_root="build/flutter"', workflow)
        self.assertIn("build-tools;36.0.0", workflow)
        self.assertIn("platforms;android-36", workflow)
        self.assertIn("Verify final APK permissions and target SDK", workflow)
        self.assertIn("targetSdkVersion:'36'", workflow)
        self.assertIn("MANAGE_EXTERNAL_STORAGE", workflow)

    def test_workflow_android_build_is_fast_and_emulator_free(self):
        workflow = (ROOT / ".github/workflows/build_apk.yml").read_text(encoding="utf-8")
        # Builds for the same ref are intentionally deduplicated so a
        # rapid sequence of focused correction commits does not accumulate
        # obsolete APK jobs.
        self.assertIn("concurrency:", workflow)
        self.assertIn("group: ${{ github.workflow }}-${{ github.ref }}", workflow)
        self.assertIn("cancel-in-progress: true", workflow)
        self.assertIn("timeout-minutes: 30", workflow)
        self.assertIn("Run Python regression suite", workflow)
        self.assertIn("python -m pytest -q --ignore=tests/test_certification_runner.py | tee build/pytest.txt", workflow)
        self.assertNotIn("pytest-first.txt", workflow)
        self.assertNotIn("pytest-second.txt", workflow)
        self.assertNotIn("python -m unittest discover -s tests -v", workflow)
        self.assertNotIn("Run Android unit tests on rendered project", workflow)
        self.assertIn("Build APK", workflow)
        self.assertIn("Verify final APK permissions and target SDK", workflow)
        self.assertIn("Verify Android host packaging", workflow)
        self.assertIn("Verify effective APK AndroidManifest", workflow)
        self.assertIn("Print and publish APK SHA-256", workflow)
        self.assertIn("Final Git integrity check", workflow)
        self.assertIn('published="build/ReiAnix.apk"', workflow)
        self.assertIn("Create downloadable APK ZIP", workflow)
        self.assertIn("build/ReiAnix-apk.zip", workflow)
        self.assertIn("Publish APK and ZIP to rolling GitHub Release", workflow)
        self.assertIn("RELEASE_TAG: reianix-ci-latest", workflow)
        self.assertIn("if: github.event_name == 'workflow_dispatch'", workflow)
        self.assertIn("Upload APK to Actions run", workflow)
        self.assertIn("actions/upload-artifact@v4", workflow)
        self.assertIn("name: ReiAnix-apk-${{ github.run_number }}", workflow)
        self.assertIn("retention-days: 1", workflow)
        self.assertIn("path: |", workflow)
        self.assertIn("            build/ReiAnix.apk", workflow)
        self.assertIn("            build/ReiAnix.apk.sha256", workflow)
        self.assertIn("Upload APK ZIP to Actions run", workflow)
        self.assertIn("name: ReiAnix-apk-zip-${{ github.run_number }}", workflow)
        self.assertIn("path: build/ReiAnix-apk.zip", workflow)
        self.assertNotIn("continue-on-error: true", workflow)
        self.assertNotIn("reactivecircus/android-emulator-runner", workflow)
        self.assertNotIn("android_api30", workflow)
        self.assertNotIn("android_api36", workflow)
        self.assertNotIn("emulator-boot-timeout", workflow)
        self.assertNotIn("Generate deterministic local player fixture", workflow)
        self.assertNotIn("build/android30-diagnostics", workflow)
        self.assertNotIn("build/android36-diagnostics", workflow)
        self.assertNotIn("actions: write", workflow)
        self.assertNotIn("Cancel legacy Android workflow runs", workflow)
        self.assertNotIn("gh run cancel", workflow)
        self.assertNotIn("|| true", workflow)
        self.assertNotIn("Run release evidence certification (no emulator)", workflow)
    def test_android_certification_is_manual_and_keeps_one_real_emulator(self):
        workflow = (ROOT / ".github/workflows/android_instrumented.yml").read_text(encoding="utf-8")
        self.assertIn("ReiAnix Android Instrumented Runtime Matrix", workflow)
        self.assertIn("workflow_dispatch:", workflow)
        self.assertNotIn("push:", workflow)
        self.assertNotIn("pull_request:", workflow)
        self.assertNotIn("matrix:", workflow)
        self.assertNotIn("api: [30, 36]", workflow)
        self.assertNotIn("Android TV API 36", workflow)
        self.assertIn("name: Android API 36", workflow)
        self.assertIn("api-level: 36", workflow)
        self.assertIn("reactivecircus/android-emulator-runner@v2", workflow)
        self.assertIn("connectedDebugAndroidTest", workflow)
        self.assertIn("flet build apk", workflow)
        self.assertIn("SERIOUS_PYTHON_SITE_PACKAGES: ${{ github.workspace }}/build/site-packages", workflow)
        self.assertIn('test -d "$SERIOUS_PYTHON_SITE_PACKAGES"', workflow)
        self.assertNotIn("ReiAnix Android No-Emulator Contract Checks", workflow)

    def test_android_build_declares_runtime_python_dependencies(self):
        project = (ROOT / "pyproject.toml").read_text(encoding="utf-8")
        android = (ROOT / "android/app/build.gradle.kts").read_text(encoding="utf-8")
        workflow = (ROOT / ".github/workflows/build_apk.yml").read_text(encoding="utf-8")
        self.assertIn('"flet==0.86.5"', project)
        self.assertIn('"certifi>=2024.8.30"', project)
        self.assertIn("min_sdk_version = 24", project)
        self.assertIn('getByName("release")', android)
        self.assertIn('abiFilters += "arm64-v8a"', android)
        self.assertNotIn('abiFilters += "armeabi-v7a"', android)
        self.assertNotIn('abiFilters += "x86_64"', android)
        self.assertIn('--arch "arm64-v8a"', workflow)
        self.assertNotIn('--arch "arm64-v8a,x86_64,armeabi-v7a"', workflow)

    def test_android_instrumented_diagnostic_is_not_part_of_fast_build(self):
        workflow = (ROOT / ".github/workflows/build_apk.yml").read_text(encoding="utf-8")
        self.assertNotIn('"$GITHUB_WORKSPACE/scripts/run_android_instrumented_diagnostic.sh"', workflow)
        self.assertNotIn("./scripts/run_android_instrumented_diagnostic.sh", workflow)
        source = (ROOT / "scripts/run_android_instrumented_diagnostic.sh").read_text(encoding="utf-8")
    def test_android_instrumented_diagnostic_remains_manual_device_only(self):
        source = (ROOT / "scripts/run_android_instrumented_diagnostic.sh").read_text(encoding="utf-8")
        self.assertIn("REIFLIX_ANDROID_API_LEVEL:-", source)
        self.assertIn("build/android${API_LEVEL}-certification", source)
        self.assertIn("com.android.internal.systemui.navbar.gestural", source)
        self.assertIn("com.android.internal.systemui.navbar.threebutton", source)
        self.assertIn("settings put secure navigation_mode", source)
        self.assertIn("30|31|32|33|34|35|36)", source)
        for token in (
            "adb devices -l",
            "getprop ro.build.version.sdk",
            "getprop ro.build.version.release",
            "getprop sys.boot_completed",
            "dumpsys activity top",
            "dumpsys window",
            "dumpsys input",
            "dumpsys SurfaceFlinger",
            "dumpsys gfxinfo",
            "dumpsys media_session",
            "adb logcat -d -b all -v threadtime",
            "kill -3",
            "InputDispatcher",
            "ActivityTaskManager",
            "WindowManager",
            "DocumentsUI",
            "MediaCodec",
            "ExoPlayer",
            "TextureView",
        ):
            self.assertIn(token, source)

    def test_cross_app_back_instrumentation_is_device_driven(self):
        source = (ROOT / "android/app/src/androidTest/kotlin/com/reiflix/reiflix_local/BackAndSettingsReturnInstrumentedTest.kt").read_text(encoding="utf-8")
        self.assertNotIn("ActivityScenario", source)
        self.assertIn("UiDevice", source)
        self.assertIn("currentPackageName", source)
        self.assertIn("device.pressBack()", source)
        self.assertIn("ActivityLifecycleMonitorRegistry", source)
        self.assertIn("Stage.RESUMED", source)
        self.assertIn("safPickerPending", source)
        self.assertIn("appSystemBackFromChildActivityReturnsToReiAnix", source)
        self.assertIn("runOnMainBounded", source)
        self.assertNotIn("runOnMainSync", source)
        self.assertNotIn("executeShellCommand", source)

    def test_player_system_back_requires_reiflix_foreground_return(self):
        source = (ROOT / "android/app/src/androidTest/kotlin/com/reiflix/reiflix_local/NativePlayerPlaybackInstrumentedTest.kt").read_text(encoding="utf-8")
        self.assertIn("UiDevice.pressBack()", source)
        self.assertIn("waitForReiAnixMainActivityForeground()", source)
        self.assertIn("Stage.RESUMED", source)
        self.assertIn("MainActivity", source)
    def test_workflow_generated_json_validation_uses_safe_heredoc(self):
        workflow = (ROOT / ".github/workflows/build_apk.yml").read_text(encoding="utf-8")
        self.assertIn("python - <<'PY'", workflow)
        self.assertIn("Invalid generated JSON {path}: {exc}", workflow)
        self.assertIn("def strip_trailing_commas(raw):", workflow)
        self.assertIn('if "{{cookiecutter." in raw or "{%" in raw:', workflow)
        self.assertIn('Unrendered Jinja JSON template (skipped):', workflow)
        self.assertIn('raw[j] in "}]"', workflow)
        self.assertNotIn("python -c \\\"", workflow)
        self.assertNotIn("|| true", workflow)

    def test_template_preparation_copies_overlay_and_installs_post_generation_hook(self):
        with tempfile.TemporaryDirectory() as d:
            template = Path(d) / "template"; template.mkdir()
            (template / "cookiecutter.json").write_text(json.dumps({"project_name": "demo"}), encoding="utf-8")
            result = subprocess.run(
                [sys.executable, str(PREPARE_TEMPLATE), "--template", str(template), "--overlay", str(ROOT / "android")],
                cwd=ROOT, capture_output=True, text=True,
            )
            self.assertEqual(result.returncode, 0, result.stderr)
            copied = template / "reiflix_android_overlay" / "app" / "src" / "main" / "kotlin" / "com" / "reiflix" / "reiflix_local"
            self.assertTrue((copied / "bridge" / "NativeMailbox.kt").is_file())
            hook_path = template / "hooks" / "post_gen_project.py"
            hook = hook_path.read_text(encoding="utf-8")
            self.assertIn("NativePlayerActivity", hook)
            self.assertIn("shutil.copytree(source, destination, dirs_exist_ok=True)", hook)
            self.assertIn("media3-exoplayer:1.11.1", hook)
            self.assertIn("MANAGE_EXTERNAL_STORAGE", hook)
            self.assertIn('main.set(launch_attr, "singleTask")', hook)
            self.assertIn('main.set(document_launch_attr, "never")', hook)
            self.assertIn("compileSdk = 36", hook)
            self.assertNotIn("__REIFLIX_OVERLAY_APP__", hook)
            self.assertIn(f'Path({str((template / "reiflix_android_overlay" / "app").resolve())!r})', hook)

            rendered = Path(d) / "rendered" / "android" / "app"
            (rendered / "src" / "main").mkdir(parents=True)
            wrapper = rendered.parent / "gradlew"
            wrapper.write_text("#!/bin/sh\nexit 0\n", encoding="utf-8")
            wrapper.chmod(0o755)
            (rendered / "build.gradle").write_text("plugins {}\ncompileSdk = 35\ndependencies { implementation \'androidx.media3:media3-exoplayer:1.11.1\' }\n", encoding="utf-8")
            (rendered / "src" / "main" / "AndroidManifest.xml").write_text(
                '<manifest xmlns:android="http://schemas.android.com/apk/res/android"><application><activity android:name=".MainActivity" /></application></manifest>',
                encoding="utf-8",
            )
            # Cookiecutter executes a temporary copy of the rendered hook.
            # Reproduce that behavior so the test catches __file__-relative paths.
            runtime_hook = Path(d) / "cookiecutter_tmp_post_gen_project.py"
            shutil.copy2(hook_path, runtime_hook)
            hook_result = subprocess.run([sys.executable, str(runtime_hook)],
                                         cwd=rendered.parents[1], capture_output=True, text=True)
            self.assertEqual(hook_result.returncode, 0, hook_result.stderr)
            rendered_manifest = rendered / "src" / "main" / "AndroidManifest.xml"
            manifest_text = rendered_manifest.read_text(encoding="utf-8")
            self.assertIn("NativePlayerActivity", manifest_text)
            self.assertIn("@style/ReiAnixTheme", manifest_text)
            self.assertIn("enableOnBackInvokedCallback", manifest_text)
            tree = ET.parse(rendered_manifest)
            android_ns = "http://schemas.android.com/apk/res/android"
            main = next(
                activity for activity in tree.getroot().find("application").findall("activity")
                if activity.get(f"{{{android_ns}}}name") == "com.reiflix.reiflix_local.MainActivity"
            )
            self.assertEqual(main.get(f"{{{android_ns}}}launchMode"), "singleTask")
            self.assertEqual(main.get(f"{{{android_ns}}}documentLaunchMode"), "never")
            self.assertIn("media3-exoplayer:1.11.1", (rendered / "build.gradle").read_text(encoding="utf-8"))
            self.assertIn("compileSdk 36", (rendered / "build.gradle").read_text(encoding="utf-8"))

    def test_native_thumbnail_pipeline_uses_metadata_retriever_and_mailbox_reference(self):
        extractor = (ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/storage/VideoThumbnailExtractor.kt").read_text(encoding="utf-8")
        activity = (ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/MainActivity.kt").read_text(encoding="utf-8")
        bridge = (ROOT / "core/android_bridge.py").read_text(encoding="utf-8")
        self.assertIn("MediaMetadataRetriever", extractor)
        self.assertIn("getScaledFrameAtTime", extractor)
        self.assertIn("output.fd.sync()", extractor)
        self.assertIn('temp.renameTo(target)', extractor)
        self.assertIn('"extract_thumbnail" -> {', activity)
        self.assertIn("requestThumbnail(intent.data, requestId)", activity)
        self.assertIn('JSONObject().put("type", "thumbnail_ready")', activity)
        self.assertIn('async def request_thumbnail', bridge)
        self.assertNotIn("Bitmap", activity.split("private fun requestThumbnail", 1)[1].split("private fun releaseTree", 1)[0])


    def test_manifest_verifier_parses_aapt2_without_fixed_indentation(self):
        import importlib.util

        spec = importlib.util.spec_from_file_location("verify_apk_manifest", ROOT / "scripts" / "verify_apk_manifest.py")
        module = importlib.util.module_from_spec(spec)
        assert spec.loader is not None
        spec.loader.exec_module(module)

        sample = """
E: manifest
  E: uses-permission
    A: android:name(0x01010003)="android.permission.READ_EXTERNAL_STORAGE"
    A: android:maxSdkVersion(0x01010271)=(type 0x10)0x20
  E: activity
   A: android:name(0x01010003)="com.reiflix.reiflix_local.MainActivity"
   A: android:launchMode(0x0101003f)=(type 0x10)0x2
   A: android:documentLaunchMode(0x01010314)=(type 0x10)0x3
   A: android:exported(0x01010010)=(type 0x12)0xffffffff
   E: intent-filter
      E: data
       A: android:scheme(0x01010027)="reiflix"
       A: android:host(0x01010028)="native"
"""
        block = module.extract_activity_block(sample, "com.reiflix.reiflix_local.MainActivity")
        self.assertIsNotNone(block)
        self.assertTrue(module.has_attribute(block, "launchMode", "0x00000002", "0x2", "=2", "singleTask"))
        self.assertTrue(module.has_attribute(block, "documentLaunchMode", "0x00000003", "0x3", "=3", "never"))
        self.assertTrue(module.has_attribute(block, "exported", "0xffffffff", "true"))
        self.assertTrue(module.has_deep_link(block))
        self.assertTrue(module.has_launchable_activity(
            "launchable-activity: name='com.reiflix.reiflix_local.MainActivity' label='' icon=''",
            "com.reiflix.reiflix_local.MainActivity",
        ))
        self.assertTrue(module.has_max_sdk_32_for_legacy_permission(sample))

    def test_main_handles_non_destructive_volume_change_events(self):
        source = (ROOT / "main.py").read_text(encoding="utf-8")
        service = (ROOT / "core" / "library_service.py").read_text(encoding="utf-8")
        store = (ROOT / "core" / "library_store.py").read_text(encoding="utf-8")
        self.assertIn("event_type == 'volume_changed'", source)
        self.assertIn("library.ingest_native_volume_change", source)
        self.assertIn("set_native_volume_states", service + store)
        start = source.index("event_type == 'volume_changed':")
        end = source.index("event_type == 'saf_inventory':", start)
        self.assertNotIn("reconcile_missing", source[start:end])


    def test_workflow_rejects_development_artifacts_from_apk(self):
        pyproject = (ROOT / "pyproject.toml").read_text(encoding="utf-8")
        workflow = (ROOT / ".github/workflows/build_apk.yml").read_text(encoding="utf-8")
        self.assertIn('[tool.flet.app]', pyproject)
        for entry in (
            '"tests"', '"docs"', '".github"', '"android"', '"scripts"',
            '".pytest_cache"', '".mypy_cache"', '"README.md"',
            '"BACKUP_RESTORE.md"', '".env.example"', '".gitignore"',
            '"pyproject.toml"', '"requirements.txt"',
        ):
            self.assertIn(entry, pyproject)
        self.assertIn("Remove development caches before Flet packaging", workflow)
        self.assertIn("Verify APK contains no development test/docs artifacts", workflow)
        self.assertIn('"tests/", "docs/", ".github/", "android/", "scripts/"', workflow)
        self.assertIn('".pytest_cache/", ".mypy_cache/", "__pycache__/", ".git/"', workflow)
        self.assertIn('"Flat .pyc files are allowed because Flet 0.86 compiles the runtime application to bytecode."', workflow)

    def test_workflow_validates_effective_manifest_and_hash(self):
        workflow = (ROOT / ".github/workflows/build_apk.yml").read_text(encoding="utf-8")
        verifier = (ROOT / "scripts/verify_apk_manifest.py").read_text(encoding="utf-8")
        self.assertIn("python scripts/verify_apk_manifest.py", workflow)
        self.assertIn("aapt2", workflow)
        self.assertIn("sha256sum", workflow)
        self.assertIn("MAIN_LAUNCH_MODE_ATTRIBUTE = \"android:launchMode\"", verifier)
        self.assertIn("MAIN_DOCUMENT_LAUNCH_MODE_ATTRIBUTE = \"android:documentLaunchMode\"", verifier)
        self.assertIn("has_attribute(main_block, \"launchMode\"", verifier)
        self.assertIn("has_attribute(main_block, \"documentLaunchMode\"", verifier)
        self.assertIn("has_package_contract", verifier)
        self.assertIn("has_target_sdk_36", verifier)
        self.assertIn("expected_version_code", verifier)
        self.assertIn("expected-version-code", verifier)
        self.assertIn('expected_version_name: str = "0.2.1"', verifier)
        self.assertIn('--expected-version-name', verifier)

    def test_packaged_manifest_validator_checks_identity_and_target_sdk(self):
        verifier = (ROOT / "scripts" / "verify_apk_manifest.py").read_text(encoding="utf-8")
        self.assertIn('com\\.reiflix\\.reiflix_local', verifier)
        self.assertIn('versionCode=', verifier)
        self.assertIn('versionName=', verifier)
        self.assertIn("targetSdkVersion", verifier)

    def test_player_orientation_and_responsive_overlay_contract(self):
        manifest = (ROOT / "android/app/src/main/AndroidManifest.xml").read_text(encoding="utf-8")
        player = (ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/NativePlayerActivity.kt").read_text(encoding="utf-8")
        template = PREPARE_TEMPLATE.read_text(encoding="utf-8")
        self.assertIn('android:screenOrientation="fullSensor"', manifest)
        self.assertIn("SCREEN_ORIENTATION_FULL_SENSOR", player)
        self.assertIn("FrameLayout.LayoutParams.MATCH_PARENT", player)
        self.assertIn("applyRootInsets", player)
        self.assertIn("WindowInsetsCompat.Type.systemBars()", player)
        self.assertIn("WindowInsetsCompat.Type.displayCutout()", player)
        self.assertIn("RESIZE_MODE_ZOOM", player)
        self.assertIn("RESIZE_MODE_FIT", player)
        self.assertIn("showAspectSelection", player)
        self.assertIn('arrayOf("Ajustar", "Preencher")', player)
        self.assertIn('screen_attr: "fullSensor"', template)
        self.assertTrue((ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/storage/VideoThumbnailExtractor.kt").is_file())

    def test_source_manifest_and_template_contract_cannot_revert_to_single_top(self):
        manifest = (ROOT / "android/app/src/main/AndroidManifest.xml").read_text(encoding="utf-8")
        template = PREPARE_TEMPLATE.read_text(encoding="utf-8")
        self.assertIn('android:launchMode="singleTask"', manifest)
        self.assertIn('android:documentLaunchMode="never"', manifest)
        self.assertIn('android:enableOnBackInvokedCallback="true"', manifest)
        self.assertIn('main.set(launch_attr, "singleTask")', template)
        self.assertIn('main.set(document_launch_attr, "never")', template)
        self.assertIn('enableOnBackInvokedCallback', template)
        self.assertNotIn('main.set(launch_attr, "singleTop")', template)

    def test_external_settings_return_uses_one_activity_result_launcher(self):
        main = (ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/MainActivity.kt").read_text(encoding="utf-8")
        self.assertIn("registerForActivityResult(ActivityResultContracts.StartActivityForResult())", main)
        self.assertIn('SETTINGS_LAUNCH kind=', main)
        self.assertIn('SETTINGS_RETURN kind=', main)
        self.assertIn('handleBroadSettingsReturn(requestId, "activity_result")', main)
        self.assertIn('handleBroadSettingsReturn(pendingBroadRequestId, "onResume")', main)
        self.assertNotIn("startActivity(packageIntent)", main)
        self.assertNotIn("startActivity(globalIntent)", main)
        self.assertNotIn("startActivity(appDetailsIntent)", main)

    def test_android_back_is_not_translated_into_a_mailbox_event(self):
        main = (ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/MainActivity.kt").read_text(encoding="utf-8")
        self.assertNotIn('put("type", "android_back")', main)
        self.assertIn("import androidx.activity.OnBackPressedCallback", main)
        self.assertIn("private fun installSystemBackHandler()", main)
        self.assertIn("onBackPressedDispatcher.addCallback(", main)
        self.assertIn("engine.navigationChannel.popRoute()", main)
        self.assertNotIn("finish()", main[main.index("private fun installSystemBackHandler"):main.index("private fun persistedSafTreeUris")])

    def test_native_player_back_logs_use_explicit_player_back_marker(self):
        player = (ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/NativePlayerActivity.kt").read_text(encoding="utf-8")
        self.assertIn("PLAYER_BACK BACK_BUTTON_TOUCH", player)
        self.assertIn("PLAYER_BACK ANDROID_BACK", player)


    def test_external_settings_launcher_retains_pending_kind_until_return(self):
        main = (ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/MainActivity.kt").read_text(encoding="utf-8")
        block = main[main.index("private fun launchExternalSettings"):main.index("private fun openBroadStorageSettings", main.index("private fun launchExternalSettings"))]
        self.assertIn("var launched = false", block)
        self.assertIn("launched = true", block)
        self.assertIn("if (!launched)", block)
        self.assertNotIn("finally {\n                externalSettingsKind = null", block)


    def test_main_activity_delegates_back_to_flet_and_keeps_activity_result_callbacks(self):
        main = (ROOT / "android" / "app" / "src" / "main" / "kotlin" / "com" / "reiflix" / "reiflix_local" / "MainActivity.kt").read_text(encoding="utf-8")
        self.assertIn("import androidx.activity.OnBackPressedCallback", main)
        self.assertIn("onBackPressedDispatcher.addCallback(", main)
        self.assertIn("engine.navigationChannel.popRoute()", main)
        self.assertNotIn("override fun onBackPressed()", main)
        self.assertNotIn("return@registerForActivityResult", main)
        self.assertIn("handleTreePickerResult(result)", main)
        self.assertIn("import io.flutter.embedding.android.FlutterFragmentActivity", main)
        self.assertIn("class MainActivity : FlutterFragmentActivity()", main)
        self.assertNotIn("import io.flutter.embedding.android.FlutterActivity", main)

        python = (ROOT / "main.py").read_text(encoding="utf-8")
        self.assertIn("page.on_view_pop = handle_flet_view_pop", python)
        self.assertIn("page.views.extend(views)", python)

    def test_settings_permission_controls_are_wired(self):
        settings = (ROOT / "views" / "settings_view.py").read_text(encoding="utf-8")
        main = (ROOT / "main.py").read_text(encoding="utf-8")
        bridge = (ROOT / "core" / "android_bridge.py").read_text(encoding="utf-8")
        self.assertIn("on_request_video_access", settings)
        self.assertIn("on_open_broad_storage", settings)
        self.assertIn("request_video_access", main)
        self.assertIn("open_broad_storage_access", main)
        self.assertIn("request_media_access", bridge)
        self.assertIn("open_broad_storage_settings", bridge)

    def test_saf_picker_requests_only_persisted_read_access(self):
        main = (ROOT / "android" / "app" / "src" / "main" / "kotlin" / "com" / "reiflix" / "reiflix_local" / "MainActivity.kt").read_text(encoding="utf-8")
        picker = main[main.index("private fun openTreePicker"):main.index("override fun onWindowFocusChanged", main.index("private fun openTreePicker"))]
        for token in (
            "Intent.ACTION_OPEN_DOCUMENT_TREE",
            "Intent.FLAG_GRANT_READ_URI_PERMISSION",
            "Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION",
            "Intent.FLAG_GRANT_PREFIX_URI_PERMISSION",
        ):
            self.assertIn(token, picker)
        self.assertNotIn("Intent.FLAG_GRANT_WRITE_URI_PERMISSION", picker)
        self.assertIn("treePicker.launch(pickerIntent)", main)
        self.assertNotIn("SafPickerProxyActivity", main)

    def test_startup_uses_activity_saf_inventory_instead_of_self_deep_link(self):
        source = (ROOT / "main.py").read_text(encoding="utf-8")
        self.assertNotIn("await bridge.verify_tree(folder['path'])", source)
        self.assertIn("authoritative SAF grant inventory", source)
        activity = (ROOT / "android" / "app" / "src" / "main" / "kotlin" / "com" / "reiflix" / "reiflix_local" / "MainActivity.kt").read_text(encoding="utf-8")
        self.assertIn("publishSafInventory()", activity)
        self.assertIn('persistedUriPermissions', activity)
        self.assertIn('JSONObject().put("type", "saf_inventory")', activity)
        self.assertIn('JSONObject().put("type", "saf_inventory")', activity)

    def test_main_activity_delegates_system_ui_to_controller_and_reapplies_on_resume(self):
        main = (ROOT / "android" / "app" / "src" / "main" / "kotlin" / "com" / "reiflix" / "reiflix_local" / "MainActivity.kt").read_text(encoding="utf-8")
        controller = (ROOT / "android" / "app" / "src" / "main" / "kotlin" / "com" / "reiflix" / "reiflix_local" / "player" / "SystemUiController.kt").read_text(encoding="utf-8")
        styles = (ROOT / "android" / "app" / "src" / "main" / "res" / "values" / "styles.xml").read_text(encoding="utf-8")
        self.assertIn("private lateinit var systemUiController: SystemUiController", main)
        self.assertIn("systemUiController = SystemUiController(window)", main)
        self.assertIn("override fun onResume()", main)
        self.assertIn("applyApplicationSystemUi()", main)
        self.assertIn("WindowCompat.getInsetsController(window, window.decorView)", controller)
        self.assertIn("setDecorFitsSystemWindows(window, false)", controller)
        self.assertNotIn("setDecorFitsSystemWindows(window, true)", controller)
        self.assertIn("show(WindowInsetsCompat.Type.systemBars())", controller)
        self.assertIn("hide(WindowInsetsCompat.Type.systemBars())", controller)
        self.assertIn("BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE", controller)
        self.assertNotIn("applyImmersiveSystemUi()", main)
        main_style = styles.split('<style name="ReiAnixPlayerTheme"', 1)[0]
        player_style = styles.split('<style name="ReiAnixPlayerTheme"', 1)[1]
        self.assertNotIn('<item name="android:windowFullscreen">true</item>', main_style)
        self.assertNotIn('<item name="android:windowFullscreen">true</item>', player_style)

    def test_disabled_player_gestures_are_silent(self):
        player = (ROOT / "android" / "app" / "src" / "main" / "kotlin" / "com" / "reiflix" / "reiflix_local" / "NativePlayerActivity.kt").read_text(encoding="utf-8")
        self.assertNotIn('showFeedback("Gesto de brilho desligado"', player)
        self.assertNotIn('showFeedback("Gesto de volume desligado"', player)
        self.assertIn("if (brightnessGesturesEnabled)", player)
        self.assertIn("if (volumeGesturesEnabled)", player)

    def test_settings_nested_back_uses_navigation_controller_contract(self):
        main = (ROOT / "main.py").read_text(encoding="utf-8")
        navigation = (ROOT / "core" / "navigation.py").read_text(encoding="utf-8")
        self.assertIn("def navigate_settings_category(label):", main)
        self.assertIn("navigation.push_settings(label)", main)
        self.assertIn('if action in {"previous", "settings_inner"}:', main)
        self.assertIn('if action == "settings_inner":', main)
        self.assertEqual(navigation.count('return "settings_inner"'), 1)
        self.assertNotIn("SETTINGS_INNER_BACK", main)
        self.assertNotIn("settings_system_back", main)

    def test_settings_back_is_not_registered_as_a_second_back_system(self):
        settings = (ROOT / "views" / "settings_view.py").read_text(encoding="utf-8")
        main = (ROOT / "main.py").read_text(encoding="utf-8")
        navigation = (ROOT / "core" / "navigation.py").read_text(encoding="utf-8")
        self.assertIn("on_open_settings_category=navigate_settings_category", main)
        self.assertIn("settings_path_provider=", main)
        self.assertIn("navigation.settings_path", main)
        self.assertIn("def back_to_categories", settings)
        self.assertNotIn("def handle_system_back()", settings)
        self.assertNotIn("on_register_system_back", settings)
        self.assertNotIn("settings_system_back", main)
        self.assertNotIn("SETTINGS_INNER_BACK", main)
        self.assertIn("def back(self) -> str:", navigation)
        self.assertIn('return "settings_inner"', navigation)

    def test_native_host_and_player_use_immersive_system_bars(self):
        main = (ROOT / "android" / "app" / "src" / "main" / "kotlin" / "com" / "reiflix" / "reiflix_local" / "MainActivity.kt").read_text(encoding="utf-8")
        controller = (ROOT / "android" / "app" / "src" / "main" / "kotlin" / "com" / "reiflix" / "reiflix_local" / "player" / "SystemUiController.kt").read_text(encoding="utf-8")
        player = (ROOT / "android" / "app" / "src" / "main" / "kotlin" / "com" / "reiflix" / "reiflix_local" / "NativePlayerActivity.kt").read_text(encoding="utf-8")
        self.assertIn("systemUiController = SystemUiController(window)", main)
        self.assertIn("applyApplicationSystemUi()", main)
        self.assertNotIn("applyImmersiveSystemUi()", main)
        self.assertIn("setDecorFitsSystemWindows(window, false)", controller)
        self.assertIn("show(WindowInsetsCompat.Type.systemBars())", controller)
        self.assertIn("hide(WindowInsetsCompat.Type.systemBars())", controller)
        self.assertIn("systemUiController = SystemUiController(window)", player)
        self.assertIn("systemUiController.applyImmersive()", player)
        self.assertIn("systemUiController.applyNormal(useContextAppearance = false)", player)
        self.assertIn("ViewCompat.setOnApplyWindowInsetsListener(root)", player)
        self.assertNotIn("WindowInsetsControllerCompat(window, window.decorView)", player)
        self.assertIn("BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE", controller)

    def test_player_exit_is_not_suppressed_after_normal_completion(self):
        player = (ROOT / "android" / "app" / "src" / "main" / "kotlin" / "com" / "reiflix" / "reiflix_local" / "NativePlayerActivity.kt").read_text(encoding="utf-8")
        self.assertIn("private var suppressExitEvent = false", player)
        self.assertIn("private fun reportPlayerExit", player)
        self.assertIn("val shouldReportExit = isFinishing && !suppressExitEvent && !exitReported && !isChangingConfigurations", player)
        self.assertIn("if (shouldReportExit)", player)
        self.assertIn('reportPlayerExit("activity_finish")', player)

    def test_template_requires_the_system_ui_controller(self):
        source = PREPARE_TEMPLATE.read_text(encoding="utf-8")
        self.assertIn("SystemUiController.kt", source)
        self.assertIn("immersive system-bar host policy", source)

    def test_native_mailbox_uses_the_flet_application_data_subdirectory(self):
        mailbox = (ROOT / "android" / "app" / "src" / "main" / "kotlin" / "com" / "reiflix" / "reiflix_local" / "bridge" / "NativeMailbox.kt").read_text(encoding="utf-8")
        self.assertIn('File(context.filesDir, "data")', mailbox)
        self.assertIn('val queue = File(dataDirectory, QUEUE)', mailbox)

    def test_refresh_recovers_when_a_saf_scan_cannot_start(self):
        source = (ROOT / "main.py").read_text(encoding="utf-8")
        refresh = source[source.index("async def refresh_library"):source.index("async def login")]
        self.assertIn("scan_coordinator.request(", refresh)
        self.assertIn("ScanOrigin.USER_REFRESH", refresh)
        self.assertIn('if transition.kind == "blocked"', refresh)

    def test_folder_removal_is_blocked_while_refresh_is_active(self):
        source = (ROOT / "main.py").read_text(encoding="utf-8")
        start = source.index("    async def remove_folder(reference):")
        end = source.index("    def account():", start)
        block = source[start:end]
        self.assertIn("if scan_coordinator.active or saf_selection.pending:", block)
        self.assertIn("store.remove_folder(reference)", block)

    def test_folder_removal_waits_for_native_release_event(self):
        main = (ROOT / "main.py").read_text(encoding="utf-8")
        self.assertIn("pending_folder_removals={}", main)
        self.assertIn("pending_folder_removals[reference]", main)
        self.assertIn("event_type == 'saf_released'", main)
        self.assertIn("store.remove_folder(tree_uri)", main)
        self.assertIn('"remove_saf"', main)
        self.assertIn('"SUCCESS"', main)

    def test_folder_removal_releases_saf_permission_before_database_removal(self):
        bridge = (ROOT / "core" / "android_bridge.py").read_text(encoding="utf-8")
        activity = (ROOT / "android" / "app" / "src" / "main" / "kotlin" / "com" / "reiflix" / "reiflix_local" / "MainActivity.kt").read_text(encoding="utf-8")
        main = (ROOT / "main.py").read_text(encoding="utf-8")
        self.assertIn('async def release_tree(self, tree_uri: str)', bridge)
        self.assertIn('"release_tree" -> {', activity)
        self.assertIn("releaseTree(intent.data?.getQueryParameter(\"tree_uri\"), requestId)", activity)
        self.assertIn("releasePersistableUriPermission", activity)
        self.assertIn("await bridge.release_tree(reference)", main)

    def test_settings_exposes_folder_removal_callback(self):
        settings = (ROOT / "views" / "settings_view.py").read_text(encoding="utf-8")
        main = (ROOT / "main.py").read_text(encoding="utf-8")
        self.assertIn("on_remove_folder", settings)
        self.assertIn("on_remove_folder(ref)", settings)
        self.assertIn("remove_folder", main)

    def test_refresh_library_skips_revoked_saf_trees_until_permission_returns(self):
        source = (ROOT / "main.py").read_text(encoding="utf-8")
        refresh = source[source.index("async def refresh_library"):source.index("async def login", source.index("async def refresh_library"))]
        self.assertIn("scan_coordinator.request(", refresh)
        self.assertIn("ScanOrigin.USER_REFRESH", refresh)
        self.assertNotIn("bridge.rescan_tree(", refresh)

    def test_refresh_library_waits_for_every_saf_scan_result_or_error(self):
        source = (ROOT / "main.py").read_text(encoding="utf-8")
        self.assertIn("scan_coordinator", source)
        self.assertIn("handle_native_event", source)
        self.assertNotIn("pending_native_scans", source)
        self.assertNotIn("finish_native_scan()", source)
        self.assertIn("if event_type == 'saf_error':", source)

    def test_native_player_entry_requires_a_persisted_saf_document(self):
        main = (ROOT / "android" / "app" / "src" / "main" / "kotlin" / "com" / "reiflix" / "reiflix_local" / "MainActivity.kt").read_text(encoding="utf-8")
        scanner = (ROOT / "android" / "app" / "src" / "main" / "kotlin" / "com" / "reiflix" / "reiflix_local" / "scanner" / "SafScanner.kt").read_text(encoding="utf-8")
        self.assertIn("SafScanner.isAuthorizedDocument(this, localUri)", (ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/NativePlayerActivity.kt").read_text(encoding="utf-8"))
        self.assertIn("DocumentsContract.getDocumentId(documentUri)", scanner)
        self.assertIn("treeIdentity(p.uri)", scanner)

    def test_saf_scanner_uses_iterative_traversal_and_partial_results(self):
        scanner = (ROOT / "android" / "app" / "src" / "main" / "kotlin" / "com" / "reiflix" / "reiflix_local" / "scanner" / "SafScanner.kt").read_text(encoding="utf-8")
        self.assertIn("ArrayDeque<Pair<String,String>>()", scanner)
        self.assertIn("pending.removeLast()", scanner)
        self.assertIn('.put("partial",partial)', scanner)
        self.assertIn("DocumentsContract.buildChildDocumentsUriUsingTree", scanner)
        self.assertIn("DocumentsContract.buildDocumentUriUsingTree", scanner)
        self.assertIn("COLUMN_DOCUMENT_ID", scanner)
        self.assertIn("COLUMN_MIME_TYPE", scanner)
        self.assertIn("Log.w(", scanner)

    def test_native_player_entry_rejects_non_local_deep_link_uris(self):
        player = (ROOT / "android" / "app" / "src" / "main" / "kotlin" / "com" / "reiflix" / "reiflix_local" / "NativePlayerActivity.kt").read_text(encoding="utf-8")
        self.assertIn("val resolvedUri = normalizeLocalReference(rawUri)", player)
        self.assertIn("if (resolvedUri == null)", player)
        self.assertIn('showPlayerError("Referência local inválida.", "invalid_uri")', player)
        self.assertIn("A reprodução aceita somente referências locais content:// ou file://.", player)

    def test_native_player_rechecks_saf_authorization_before_media3_start(self):
        player = (ROOT / "android" / "app" / "src" / "main" / "kotlin" / "com" / "reiflix" / "reiflix_local" / "NativePlayerActivity.kt").read_text(encoding="utf-8")
        self.assertIn("SafScanner.isAuthorizedDocument(this, localUri)", player)
        self.assertIn("MediaStoreScanner.isAuthorizedDocument(this, localUri)", player)
        self.assertIn('contentResolver.openFileDescriptor(localUri, "r")', player)
        self.assertIn("Este arquivo não pertence a uma pasta autorizada pelo ReiAnix.", player)

    def test_android_bridge_accepts_only_local_media_references(self):
        from core.android_bridge import AndroidBridge

        self.assertTrue(AndroidBridge.is_local_media_reference(
            "content://com.android.providers.media.documents/document/video%3A1"
        ))
        self.assertTrue(AndroidBridge.is_local_media_reference("/sdcard/video.mkv"))
        self.assertTrue(AndroidBridge.is_local_media_reference("file:///sdcard/video.mkv"))
        for value in ("", "http://example/video.mkv", "https://example/video.m3u8"):
            self.assertFalse(AndroidBridge.is_local_media_reference(value))
        self.assertEqual(
            AndroidBridge.normalize_local_media_reference("/sdcard/video.mkv"),
            Path("/sdcard/video.mkv").resolve().as_uri(),
        )
        self.assertEqual(
            AndroidBridge.normalize_local_media_reference("content://provider/video/1"),
            "content://provider/video/1",
        )

    def test_player_preflight_uses_authorized_source_and_real_readability(self):
        player = (ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/NativePlayerActivity.kt").read_text(encoding="utf-8")
        self.assertIn("normalizeLocalReference(rawUri)", player)
        self.assertIn('contentResolver.openFileDescriptor(localUri, "r")', player)
        self.assertIn("file.exists()", player)
        self.assertIn("file.canRead()", player)
        self.assertIn("MediaStore.AUTHORITY", player)
        self.assertIn("A permissão para ler vídeos foi revogada.", player)
        self.assertIn("Arquivo local removido ou indisponível.", player)
        self.assertIn("O provedor local não está disponível", player)

    def test_player_recreates_with_track_selection_and_pip_is_optional(self):
        player = (ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/NativePlayerActivity.kt").read_text(encoding="utf-8")
        manifest = (ROOT / "android/app/src/main/AndroidManifest.xml").read_text(encoding="utf-8")
        template = PREPARE_TEMPLATE.read_text(encoding="utf-8")
        self.assertIn("TrackSelectionParameters.fromBundle(bundle)", player)
        self.assertIn('outState.putBundle("track_selection_parameters"', player)
        self.assertIn('outState.putBoolean("play_when_ready", player.playWhenReady)', player)
        self.assertIn('getBoolean("play_when_ready")', player)
        self.assertIn("PictureInPictureParams.Builder()", player)
        self.assertIn("setAutoEnterEnabled(", player)
        self.assertIn("player.playWhenReady && player.isPlaying", player)
        self.assertIn("PackageManager.FEATURE_PICTURE_IN_PICTURE", player)
        self.assertIn("canEnterPictureInPicture()", player)
        self.assertIn("android.software.picture_in_picture", manifest)
        self.assertIn('android:required="false"', manifest)
        self.assertIn("android.software.picture_in_picture", template)
        self.assertIn('pip_feature.set("{" + ANDROID + "}required", "false")', template)

    def test_native_player_uses_only_supported_media3_track_selection_apis(self):
        player = (ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/NativePlayerActivity.kt").read_text(encoding="utf-8")
        self.assertNotIn("setSelectTextByDefault(", player)
        self.assertIn("setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)", player)
        self.assertIn("setPreferredTextLanguage(", player)

    def test_packaged_manifest_verifier_checks_native_player_pip_contract(self):
        verifier = (ROOT / "scripts/verify_apk_manifest.py").read_text(encoding="utf-8")
        self.assertIn("PLAYER_ACTIVITY", verifier)
        self.assertIn("PIP_FEATURE", verifier)
        self.assertIn("supportsPictureInPicture", verifier)
        self.assertIn("launchMode=singleTop", verifier)
        self.assertIn("has_optional_feature", verifier)

    def test_saf_regrant_path_persists_before_scanning_and_reports_revocation(self):
        main = (ROOT / "android" / "app" / "src" / "main" / "kotlin" / "com" / "reiflix" / "reiflix_local" / "MainActivity.kt").read_text(encoding="utf-8")
        scanner = (ROOT / "android" / "app" / "src" / "main" / "kotlin" / "com" / "reiflix" / "reiflix_local" / "scanner" / "SafScanner.kt").read_text(encoding="utf-8")
        self.assertIn("val flags = resultIntent.flags", main)
        self.assertIn("SafScanner.persistPermission(this, uri, flags)", main)
        self.assertIn('publishScanRequest("PERMISSION_CHANGE"', main)
        self.assertNotIn("scanTree(uri.toString(), requestId)", main)
        self.assertIn("takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION)", scanner)
        self.assertIn("check(hasPersistedReadPermission(context,uri))", scanner)
        self.assertIn("SafScanner.inspectTree(this, treeUri, requirePersisted = true)", main)
        self.assertIn("A permissão desta pasta foi removida.", main)

    def test_player_rejects_removed_or_invalid_saf_documents_without_starting_media3(self):
        main = (ROOT / "android" / "app" / "src" / "main" / "kotlin" / "com" / "reiflix" / "reiflix_local" / "MainActivity.kt").read_text(encoding="utf-8")
        player = (ROOT / "android" / "app" / "src" / "main" / "kotlin" / "com" / "reiflix" / "reiflix_local" / "NativePlayerActivity.kt").read_text(encoding="utf-8")
        handoff_start = main.index("    private fun openPlayer(data: Uri?, commandReceivedAtMs: Long = 0L)")
        handoff_end = main.index("    private fun clearPendingPlay()", handoff_start)
        handoff = main[handoff_start:handoff_end]
        self.assertNotIn("SafScanner.isAuthorizedDocument(this, localUri)", handoff)
        self.assertNotIn("MediaStoreScanner.isAuthorizedDocument(this, localUri)", handoff)
        self.assertIn("playerActivityLauncher.launch(intent)", handoff)
        self.assertIn("SafScanner.isAuthorizedDocument(this, localUri)", player)
        self.assertIn("MediaStoreScanner.isAuthorizedDocument(this, localUri)", player)
        self.assertIn("BroadStorageScanner.isAuthorizedFile(this, localUri)", player)
        self.assertIn('showPlayerError("Arquivo local inválido.", "missing_uri")', player)
        self.assertNotIn('reportError("Arquivo local inválido.")', player)

    def test_native_player_accepts_saf_media_store_and_broad_paths(self):
        main = (ROOT / "android" / "app" / "src" / "main" / "kotlin" / "com" / "reiflix" / "reiflix_local" / "MainActivity.kt").read_text(encoding="utf-8")
        player = (ROOT / "android" / "app" / "src" / "main" / "kotlin" / "com" / "reiflix" / "reiflix_local" / "NativePlayerActivity.kt").read_text(encoding="utf-8")
        bridge = (ROOT / "core" / "android_bridge.py").read_text(encoding="utf-8")
        request = (ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/player/NativePlayerRequest.kt").read_text(encoding="utf-8")
        handoff = main[main.index("    private fun openPlayer"):main.index("    private fun clearPendingPlay", main.index("    private fun openPlayer"))]
        self.assertIn('localUri.scheme?.lowercase() !in setOf("content", "file")', handoff)
        self.assertNotIn("SafScanner.isAuthorizedDocument(this, localUri)", handoff)
        self.assertNotIn("MediaStoreScanner.isAuthorizedDocument(this, localUri)", handoff)
        self.assertNotIn("BroadStorageScanner.isAuthorizedFile(this, localUri)", handoff)
        self.assertNotIn("validatePlayerSource(localUri)", handoff)
        self.assertIn("SafScanner.isAuthorizedDocument(this, localUri)", player)
        self.assertIn("NativePlayerRequest.fromBridgeUri(source)", main)
        self.assertIn("playerRequest.toIntent(this, localUri)", main)
        self.assertIn('.putExtra("uri", normalizedUri.toString())', request)
        self.assertIn(".setUri(mediaUri)", player)
        self.assertIn("validateLocalSource", player)
        self.assertIn("normalize_local_media_reference", bridge)
        self.assertIn("os.path.isabs(value)", bridge)
        self.assertIn("Uri.fromFile(File(reference).canonicalFile)", player)
        self.assertNotIn("/storage/emulated/0", main + player)

    def test_native_player_error_does_not_emit_a_second_exit_event(self):
        player = (ROOT / "android" / "app" / "src" / "main" / "kotlin" / "com" / "reiflix" / "reiflix_local" / "NativePlayerActivity.kt").read_text(encoding="utf-8")
        error = player.index("override fun onPlayerError")
        end = player.index("private fun configureWindow", error)
        block = player[error:end]
        self.assertIn('put("errorCode", technicalCode)', block)
        self.assertIn('put("errorMessage", detail)', block)
        self.assertIn("showPlayerError(", block)
        self.assertNotIn("finishPlayer(", block)

    def test_native_player_next_previous_suppress_normal_exit(self):
        player = (ROOT / "android" / "app" / "src" / "main" / "kotlin" / "com" / "reiflix" / "reiflix_local" / "NativePlayerActivity.kt").read_text(encoding="utf-8")
        request = player.index("private fun requestEpisode")
        seek = player.index("private fun seekToSavedPosition")
        block = player[request:seek]
        self.assertIn('saveProgress("player_progress", force = true)', block)
        self.assertNotIn("suppressExitEvent = true", block)
        self.assertIn("episodeChangePending = true", block)
        self.assertIn("keepActivity=true", player)
        self.assertIn("player_next_request", player)
        self.assertIn("player_previous_request", player)

    def test_saf_scanner_contains_provider_error_recovery_for_inaccessible_documents(self):
        scanner = (ROOT / "android" / "app" / "src" / "main" / "kotlin" / "com" / "reiflix" / "reiflix_local" / "scanner" / "SafScanner.kt").read_text(encoding="utf-8")
        self.assertIn("runCatching", scanner)
        self.assertIn("catch(e:Exception)", scanner)
        self.assertIn('errors.put("Não foi possível ler:', scanner)
        self.assertIn('errors.put("Não foi possível acessar:', scanner)
        self.assertIn('.put("partial",partial)', scanner)

    def test_google_identity_emits_only_token_free_validated_profile_fields(self):
        source = (ROOT / "android" / "app" / "src" / "main" / "kotlin" / "com" / "reiflix" / "reiflix_local" / "bridge" / "GoogleIdentity.kt").read_text(encoding="utf-8")
        self.assertIn('"google_sign_in_started"', source)
        self.assertIn("validatedClaims(credential.idToken, serverClientId, nonce)", source)
        self.assertIn('.put("id", credential.uniqueId)', source)
        self.assertIn("claims.subject != credential.uniqueId", source)
        self.assertNotIn('.put("idToken"', source)


class TestSafSelectionRegistration(unittest.TestCase):
    def test_picker_registers_grant_before_scan(self):
        main = (ROOT / "android" / "app" / "src" / "main" / "kotlin" / "com" / "reiflix" / "reiflix_local" / "MainActivity.kt").read_text(encoding="utf-8")
        self.assertIn('put("selected", true)', main)
        self.assertIn('SafScanner.displayName(this, uri)', main)
        block = main[main.index("private fun handleTreePickerResult"):main.index("private fun logLifecycle", main.index("private fun handleTreePickerResult"))]
        self.assertLess(block.index('put("selected", true)'), block.index('publishScanRequest("PERMISSION_CHANGE"'))
        self.assertLess(block.index('SafScanner.persistPermission(this, uri, flags)'), block.index('publishScanRequest("PERMISSION_CHANGE"'))

    def test_python_registers_selected_saf_tree_before_ingest_result(self):
        source = (ROOT / "main.py").read_text(encoding="utf-8")
        self.assertIn("if payload.get('selected'):", source)
        self.assertIn("store.add_folder(", source)
        self.assertIn("kind='saf'", source)
        self.assertIn("authorization='granted'", source)

    def test_saf_scanner_has_safe_display_name_fallback(self):
        scanner = (ROOT / "android" / "app" / "src" / "main" / "kotlin" / "com" / "reiflix" / "reiflix_local" / "scanner" / "SafScanner.kt").read_text(encoding="utf-8")
        self.assertIn("fun displayName(context:Context,treeUri:Uri):String", scanner)
        self.assertIn("DocumentFile.fromTreeUri(context,treeUri)?.name", scanner)

    def test_invalid_scan_command_reports_an_error_instead_of_hanging_refresh(self):
        main = (ROOT / "android" / "app" / "src" / "main" / "kotlin" / "com" / "reiflix" / "reiflix_local" / "MainActivity.kt").read_text(encoding="utf-8")
        self.assertIn('if (reference.isNullOrBlank()) {', main)
        self.assertIn('JSONObject().put("type", "saf_error")', main)
        self.assertIn('A pasta SAF não foi informada corretamente.', main)


class TestSafScannerHardening(unittest.TestCase):
    def test_scanner_has_revisit_guard_and_progress_callback(self):
        scanner = (ROOT / "android" / "app" / "src" / "main" / "kotlin" / "com" / "reiflix" / "reiflix_local" / "scanner" / "SafScanner.kt").read_text(encoding="utf-8")
        self.assertIn("onProgress:((JSONObject)->Unit)?=null", scanner)
        self.assertIn("visited=HashSet<String>()", scanner)
        self.assertIn("if(!visited.add(parentId))", scanner)
        self.assertNotIn('put("pending", pending.size)', scanner)

    def test_main_activity_publishes_scan_progress_before_final_result(self):
        main = (ROOT / "android" / "app" / "src" / "main" / "kotlin" / "com" / "reiflix" / "reiflix_local" / "scanner" / "NativeScanRunner.kt").read_text(encoding="utf-8")
        progress = main.index('put("type", "saf_scan_progress")')
        final = main.index('put("type", "saf_scan")', progress)
        self.assertLess(progress, final)
        self.assertIn('put("phase", "scanning")', main)

    def test_python_consumes_saf_scan_progress(self):
        source = (ROOT / "main.py").read_text(encoding="utf-8")
        self.assertIn("event_type == 'saf_scan_progress'", source)
        self.assertIn("Verificando pasta…", source)
        self.assertIn("payload.get('directories')", source)


class TestMediaStoreScannerOptimization(unittest.TestCase):
    def test_media_store_caches_volume_uuid_lookup_per_volume(self):
        scanner = (ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/scanner/MediaStoreScanner.kt").read_text(encoding="utf-8")
        self.assertIn("val volumeUuidCache = HashMap<String, String>()", scanner)
        self.assertIn("val baseVolumeUuid = volumeUuidCache.getOrPut(volumeName)", scanner)
        self.assertIn("volumeUuidCache.getOrPut(actualVol)", scanner)

    def test_saf_filters_directory_entries_before_building_document_uris(self):
        scanner = (ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/scanner/SafScanner.kt").read_text(encoding="utf-8")
        cursor_block_start = scanner.index("val directoriesToVisit=mutableListOf<Pair<String,String>>()")
        cursor_block_end = scanner.index("batches.flush()", cursor_block_start)
        block = scanner[cursor_block_start:cursor_block_end]
        self.assertIn("val directoriesToVisit=mutableListOf<Pair<String,String>>()", block)
        self.assertIn("val videoChildren=mutableListOf<Child>()", block)
        self.assertLess(block.index("videoChildren.add(Child"), block.index("DocumentsContract.buildDocumentUriUsingTree"))
        self.assertNotIn("val children=mutableListOf<Child>()", block)


class TestNativePlayerHardening(unittest.TestCase):
    def test_native_player_reapplies_immersive_mode_on_resume(self):
        player = (ROOT / "android" / "app" / "src" / "main" / "kotlin" / "com" / "reiflix" / "reiflix_local" / "NativePlayerActivity.kt").read_text(encoding="utf-8")
        self.assertIn("override fun onResume()", player)
        self.assertIn("super.onResume()", player)
        self.assertIn("enterImmersiveMode()", player)

    def test_player_uses_media3_dependencies(self):
        gradle = (ROOT / "android" / "app" / "build.gradle.kts").read_text(encoding="utf-8")
        self.assertIn('implementation("androidx.media3:media3-exoplayer:1.11.1")', gradle)
        self.assertIn('implementation("androidx.media3:media3-ui:1.11.1")', gradle)


class TestFletAsyncCallbacks(unittest.TestCase):
    def test_organize_view_does_not_pass_coroutine_objects_to_page_run_task(self):
        source = (ROOT / "views" / "organize_view.py").read_text(encoding="utf-8")
        self.assertNotIn("page.run_task(lambda:", source)
        self.assertIn("async def handle_request_video_access", source)
        self.assertIn("await _invoke_callback(on_request_video_access)", source)
        self.assertIn("async def handle_add_folder", source)
        self.assertIn("await _invoke_callback(on_add_folder)", source)

    def test_settings_confirm_uses_an_async_event_handler(self):
        source = (ROOT / "views" / "settings_view.py").read_text(encoding="utf-8")
        self.assertNotIn("page.run_task(lambda:", source)
        self.assertIn("async def run(_):", source)
        self.assertIn("inspect.isawaitable(result)", source)


class BuildIdentityContractTests(unittest.TestCase):
    def test_main_does_not_reference_legacy_back_started(self):
        main = (ROOT / "main.py").read_text(encoding="utf-8")
        self.assertNotIn("back_started", main)
        self.assertIn("back_state", main)
        self.assertIn("from core.build_identity import as_dict as build_identity", main)
        self.assertIn('diagnostics.record("BUILD_IDENTITY"', main)

    def test_project_pins_python_312_for_flet_086(self):
        project = (ROOT / "pyproject.toml").read_text(encoding="utf-8")
        workflow = (ROOT / ".github/workflows/build_apk.yml").read_text(encoding="utf-8")
        self.assertIn('requires-python = ">=3.12,<3.13"', project)
        self.assertIn('flet==0.86.5', project)
        self.assertIn('--python-version "3.12"', workflow)
        self.assertIn("assert flet.__version__ == '0.86.5'", workflow)

    def test_workflow_generates_and_verifies_packaged_python_identity(self):
        workflow = (ROOT / ".github/workflows/build_apk.yml").read_text(encoding="utf-8")
        self.assertIn("scripts/generate_build_identity.py", workflow)
        self.assertIn("scripts/verify_python_bundle.py", workflow)
        self.assertIn(".reiflix-build-identity.json", workflow)
        self.assertIn("Verify packaged Python identity and Back symbols", workflow)
        self.assertIn("mkdir -p build", workflow)
        self.assertIn("git checkout -- core/build_identity.py", workflow)
        self.assertIn('python-version "3.12"', workflow)
        self.assertIn('versionName=\'0.2.1\'', workflow)

    def test_fast_apk_workflow_does_not_run_kotlin_unit_tests(self):
        workflow = (ROOT / ".github/workflows/build_apk.yml").read_text(encoding="utf-8")
        prepare = (ROOT / "scripts/prepare_flet_template.py").read_text(encoding="utf-8")
        certification = (ROOT / "scripts/release_certification.py").read_text(encoding="utf-8")
        self.assertIn("flet-build-template.zip", workflow)
        self.assertIn("assets/app.zip", workflow)
        self.assertNotIn("flet clear-cache", workflow)
        self.assertNotIn("Run Kotlin unit tests", workflow)
        self.assertNotIn(":app:testDebugUnitTest", prepare)
        self.assertIn(":app:testDebugUnitTest", certification)
        self.assertIn("SERIOUS_PYTHON_SITE_PACKAGES", certification)


class NativeMainActivityDecompositionTests(unittest.TestCase):
    def test_main_activity_delegates_scan_batch_publication(self):
        main = (ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/scanner/NativeScanRunner.kt").read_text(encoding="utf-8")
        publisher = (ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/scanner/NativeScanPublisher.kt").read_text(encoding="utf-8")
        self.assertNotIn("private fun publishNativeScanBatch(", main)
        self.assertNotIn("NativeIndex.prepareBatch(", main)
        self.assertIn("NativeScanPublisher.publish(", main)
        self.assertIn("NativeIndex.prepareBatch(", publisher)
        self.assertIn("NativeMailbox.writeOrThrow(", publisher)

    def test_main_activity_delegates_media_store_retry_scheduling(self):
        main = (ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/MainActivity.kt").read_text(encoding="utf-8")
        scheduler = (ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/scanner/MediaStoreRetryScheduler.kt").read_text(encoding="utf-8")
        self.assertNotIn("private fun scheduleMediaStoreScanRequest(", main)
        self.assertNotIn("mediaStoreRetryScheduled", main)
        self.assertIn("MediaStoreRetryScheduler.schedule(", main)
        self.assertIn("AtomicBoolean(false)", scheduler)
        self.assertIn("MediaStoreScanner.hasReadPermission(appContext)", scheduler)
        self.assertIn("NativeMailbox.write(", scheduler)
