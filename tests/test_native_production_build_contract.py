import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
WORKFLOW = ROOT / ".github/workflows/build_apk.yml"
GRADLE = ROOT / "android/app/build.gradle.kts"
MANIFEST = ROOT / "android/app/src/main/AndroidManifest.xml"


class NativeProductionBuildContractTests(unittest.TestCase):
    def test_official_apk_job_uses_gradle_without_legacy_build_toolchain(self):
        workflow = WORKFLOW.read_text(encoding="utf-8")
        self.assertIn(":app:assembleRelease", workflow)
        self.assertIn("setup-java", workflow)
        self.assertIn("setup-android", workflow)
        self.assertNotIn("actions/setup-python", workflow)
        self.assertNotIn("subosito/flutter-action", workflow)
        self.assertNotIn("flet build", workflow.lower())
        self.assertNotIn("pip install", workflow.lower())
        self.assertIn("runtimeIndependenceCertified", workflow)

    def test_release_build_uses_monotonic_ci_version_code_and_signing_fallback(self):
        gradle = GRADLE.read_text(encoding="utf-8")
        self.assertIn('versionCode = providers.gradleProperty("reianixVersionCode").orElse("2").get().toInt()', gradle)
        self.assertIn("stableReleaseSigningConfigured", gradle)
        self.assertIn('signingConfigs.getByName("reianixRelease")', gradle)
        self.assertIn('signingConfigs.getByName("debug")', gradle)

    def test_android_entry_points_and_canonical_schema_assets_remain_native(self):
        manifest = MANIFEST.read_text(encoding="utf-8")
        gradle = GRADLE.read_text(encoding="utf-8")
        self.assertIn('android:name=".ReiAnixApplication"', manifest)
        self.assertIn('android:name=".MainActivity"', manifest)
        self.assertIn('android:name=".NativePlayerActivity"', manifest)
        self.assertIn("native-sqlite-contract-v29.json", gradle)
        self.assertIn("native-sqlite-bootstrap-v29.json", gradle)


if __name__ == "__main__":
    unittest.main()
