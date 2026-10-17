import gzip
import json
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


class NativeGradleBuildContractTests(unittest.TestCase):
    def test_single_native_app_module_reuses_the_existing_android_sources(self):
        settings = (ROOT / "settings.gradle.kts").read_text(encoding="utf-8")
        module = (ROOT / "android/app/build.gradle.kts").read_text(encoding="utf-8")
        self.assertIn('include(":app")', settings)
        self.assertIn('project(":app").projectDir = file("android/app")', settings)
        self.assertIn('applicationId = "com.reiflix.reiflix_local"', module)
        self.assertIn("class MainActivity : ComponentActivity()", (
            ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/MainActivity.kt"
        ).read_text(encoding="utf-8"))
        self.assertIn('android:name=".NativePlayerActivity"', (
            ROOT / "android/app/src/main/AndroidManifest.xml"
        ).read_text(encoding="utf-8"))

    def test_gradle_kotlin_jdk_and_android_sdk_versions_are_aligned(self):
        root_build = (ROOT / "build.gradle.kts").read_text(encoding="utf-8")
        wrapper = (ROOT / "gradle/wrapper/gradle-wrapper.properties").read_text(encoding="utf-8")
        module = (ROOT / "android/app/build.gradle.kts").read_text(encoding="utf-8")
        self.assertIn('com.android.application") version "8.9.1"', root_build)
        self.assertIn('org.jetbrains.kotlin.android") version "2.2.0"', root_build)
        self.assertIn('org.jetbrains.kotlin.plugin.compose") version "2.2.0"', root_build)
        self.assertIn("gradle-8.11.1-bin.zip", wrapper)
        self.assertIn("jvmToolchain(17)", module)
        self.assertIn("compileSdk = 36", module)
        self.assertIn("minSdk = 24", module)
        self.assertIn("targetSdk = 36", module)
        self.assertTrue((ROOT / "gradle/wrapper/gradle-wrapper.jar").is_file())

    def test_embedded_sqlite_contract_matches_the_real_python_library_store(self):
        encoded = (ROOT / "build-logic/native-sqlite-contract-v29.json.gz.b64").read_text(
            encoding="ascii"
        ).strip()
        contract = json.loads(gzip.decompress(__import__("base64").b64decode(encoded)))
        self.assertEqual(contract["schema_version"], 29)
        self.assertEqual(contract["sqlite_user_version"], 0)
        self.assertEqual(len(contract["tables"]), 14)
        self.assertGreaterEqual(len(contract["indexes"]), 40)
        self.assertTrue({
            "tables", "columns", "table_contract", "indexes", "foreign_keys", "triggers"
        }.issubset(contract.keys()))

        from scripts.generate_native_sqlite_test_fixture import build_fixture

        generated = build_fixture()
        generated_contract = {
            key: value for key, value in generated.items() if key != "statements"
        }
        self.assertEqual(contract, generated_contract)

    def test_module_generates_the_contract_only_when_the_canonical_asset_is_absent(self):
        module = (ROOT / "android/app/build.gradle.kts").read_text(encoding="utf-8")
        self.assertIn("GZIPInputStream", module)
        self.assertIn("native-sqlite-contract-v29.json.gz.b64", module)
        self.assertIn("if (!canonicalContractAsset.isFile)", module)
        self.assertIn('resValue("string", "reiflix_google_web_client_id", providers.environmentVariable("REIFLIX_GOOGLE_WEB_CLIENT_ID").orElse("").get())', module)
        self.assertIn('tasks.named("preBuild")', module)
        self.assertNotIn('implementation("io.coil-kt:coil-okhttp:2.7.0")', module)

    def test_remote_artwork_fallback_uses_supported_coil_v2_network_support(self):
        module = (ROOT / "android/app/build.gradle.kts").read_text(encoding="utf-8")
        artwork = (ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/ui/artwork/ReiAnixLocalArtwork.kt").read_text(
            encoding="utf-8"
        )
        manifest = (ROOT / "android/app/src/main/AndroidManifest.xml").read_text(encoding="utf-8")

        # Coil 2.7.0 brings OkHttp-backed network fetching through coil-compose -> coil -> coil-base.
        # The separate coil-network-okhttp artifact belongs to Coil 3, not this Coil 2 dependency line.
        self.assertIn('implementation("io.coil-kt:coil-compose:2.7.0")', module)
        self.assertNotIn('implementation("io.coil-kt:coil-okhttp:2.7.0")', module)
        self.assertIn('source.startsWith("https://", ignoreCase = true)', artwork)
        self.assertIn('source.startsWith("http://", ignoreCase = true)', artwork)
        self.assertIn('android.permission.INTERNET', manifest)

    def test_native_workflow_builds_and_verifies_apk_and_zip_without_legacy_setup(self):
        workflow = (ROOT / ".github/workflows/native_gradle_apk.yml").read_text(encoding="utf-8")
        self.assertIn("./gradlew :app:assembleDebug", workflow)
        self.assertIn("android/app/build/outputs/apk/debug/app-debug.apk", workflow)
        self.assertIn("sha256sum", workflow)
        self.assertIn("unzip -tq", workflow)
        self.assertIn("distribution-manifest.json", workflow)
        self.assertIn("actions/upload-artifact@v4", workflow)
        self.assertNotIn("actions/setup-python", workflow)
        self.assertNotIn("subosito/flutter-action", workflow)
        self.assertNotIn("flet build", workflow.lower())
        self.assertNotIn("serious_python", workflow.lower())
        self.assertNotIn(":app:test", workflow)
        self.assertNotIn("connectedDebugAndroidTest", workflow)

    def test_flet_package_excludes_the_separate_build_tooling(self):
        config = (ROOT / "pyproject.toml").read_text(encoding="utf-8")
        exclude_line = next(line for line in config.splitlines() if line.startswith("exclude = "))
        for item in (
            '"gradle"', '"gradlew"', '"gradlew.bat"', '"settings.gradle.kts"',
            '"build.gradle.kts"', '"gradle.properties"', '"build-logic"',
        ):
            self.assertIn(item, exclude_line)


if __name__ == "__main__":
    unittest.main()
