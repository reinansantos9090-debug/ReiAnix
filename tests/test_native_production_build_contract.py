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

    def test_official_workflow_checks_out_and_stamps_the_exact_pr_head(self):
        workflow = WORKFLOW.read_text(encoding="utf-8")
        self.assertIn("ref: ${{ github.event.pull_request.head.sha || github.sha }}", workflow)
        self.assertIn('test "$(git rev-parse HEAD)" = "$REIANIX_SOURCE_SHA"', workflow)
        self.assertIn('printf \'  "commit": "%s",\\n\' "$REIANIX_SOURCE_SHA"', workflow)
        self.assertNotIn('printf \'  "commit": "%s",\\n\' "$GITHUB_SHA"', workflow)
        self.assertNotIn('= "$GITHUB_SHA"', workflow)

    def test_hybrid_runtime_build_is_not_an_automatic_release_workflow(self):
        legacy = (ROOT / ".github/workflows/build_hybrid_legacy.yml").read_text(encoding="utf-8")
        trigger = legacy.split("concurrency:", 1)[0]
        self.assertIn("workflow_dispatch:", trigger)
        self.assertNotIn("pull_request:", trigger)
        self.assertNotIn("push:", trigger)
        self.assertIn("flet build", legacy.lower())


    def test_official_artifact_publishes_its_actual_signing_certificate(self):
        workflow = WORKFLOW.read_text(encoding="utf-8")
        self.assertIn("apksigner", workflow)
        self.assertIn("signing-certificate.txt", workflow)
        self.assertIn("signingCertificateSha256", workflow)
        self.assertIn("supportedAbis", workflow)
        self.assertIn("runtimeIndependenceCertified", workflow)
        self.assertIn("debug-fallback", workflow)

    def test_current_docs_do_not_misdescribe_the_official_build_as_hybrid(self):
        gradle = GRADLE.read_text(encoding="utf-8")
        readme = (ROOT / "README.md").read_text(encoding="utf-8")
        build_docs = (ROOT / "docs/KOTLIN_NATIVE_GRADLE_BUILD.md").read_text(encoding="utf-8")
        cutover = (ROOT / "docs/PROMPT_28_RUNTIME_CUTOVER_STATUS.md").read_text(encoding="utf-8")
        self.assertNotIn("The official APK still uses Flet/Flutter tooling", gradle)
        self.assertIn("ambos os APKs", readme)
        self.assertIn("runtimeIndependenceCertified: false", readme)
        self.assertIn("historical", build_docs.lower())
        self.assertIn("supersedida", cutover)
        self.assertIn("PROMPT_30_2_APK_DIAGNOSTICS.md", build_docs)


if __name__ == "__main__":
    unittest.main()
