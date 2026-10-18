import re
import unittest
from pathlib import Path

from core.google_account import normalize_google_profile


ROOT = Path(__file__).resolve().parents[1]
GOOGLE_IDENTITY = (
    ROOT
    / "android"
    / "app"
    / "src"
    / "main"
    / "kotlin"
    / "com"
    / "reiflix"
    / "reiflix_local"
    / "bridge"
    / "GoogleIdentity.kt"
)
GRADLE = ROOT / "android" / "app" / "build.gradle.kts"
WORKFLOW = ROOT / ".github" / "workflows" / "build_apk.yml"


class GoogleProfileTests(unittest.TestCase):
    def test_normalize_rejects_malformed_native_profiles(self):
        self.assertIsNone(normalize_google_profile(None))
        self.assertIsNone(normalize_google_profile({}))
        self.assertIsNone(normalize_google_profile({"id": "abc"}))
        self.assertIsNone(normalize_google_profile({"id": "abc", "email": "invalid"}))

    def test_normalize_keeps_only_durable_profile_fields(self):
        profile = normalize_google_profile({
            "id": "  google-subject  ",
            "email": "user@example.com",
            "name": "User",
            "picture": "https://example.com/avatar.jpg",
            "idToken": "must-not-survive",
            "token": "must-not-survive",
        })
        self.assertEqual(profile, {
            "id": "google-subject",
            "name": "User",
            "email": "user@example.com",
            "picture": "https://example.com/avatar.jpg",
        })
        self.assertNotIn("idToken", profile)
        self.assertNotIn("token", profile)


class GoogleAndroidIntegrationTests(unittest.TestCase):
    def test_credential_flow_uses_stable_unique_id_and_never_sends_token(self):
        source = GOOGLE_IDENTITY.read_text(encoding="utf-8")
        self.assertIn("credential.uniqueId", source)
        self.assertIn('credential.email', source)
        self.assertNotIn('.put("idToken"', source)
        self.assertNotIn('payload.put("idToken"', source)

    def test_credential_request_binds_a_nonce_and_validates_it(self):
        source = GOOGLE_IDENTITY.read_text(encoding="utf-8")
        self.assertIn("generateNonce()", source)
        self.assertIn(".setNonce(nonce)", source)
        self.assertIn('val nonce = payload.optString("nonce")', source)
        self.assertIn("nonce != expectedNonce", source)
        self.assertIn("SecureRandom()", source)

    def test_claim_validation_checks_issuer_audience_expiry_nonce_and_subject(self):
        source = GOOGLE_IDENTITY.read_text(encoding="utf-8")
        for marker in (
            'issuer !in setOf("https://accounts.google.com", "accounts.google.com")',
            "audienceMatches",
            'payload.optLong("exp", 0L)',
            'payload.optString("sub").trim()',
            "expiresAt <= System.currentTimeMillis() / 1000",
        ):
            self.assertIn(marker, source)

    def test_web_client_id_is_validated_before_credential_manager(self):
        source = GOOGLE_IDENTITY.read_text(encoding="utf-8")
        self.assertIn("isWebClientId(serverClientId)", source)
        self.assertIn('endsWith(".apps.googleusercontent.com")', source)

    def test_credential_errors_have_actionable_categories(self):
        source = GOOGLE_IDENTITY.read_text(encoding="utf-8")
        for code in (
            '"no_credential"',
            '"unsupported"',
            '"provider_configuration"',
            '"invalid_credential"',
            '"credential_error"',
        ):
            self.assertIn(code, source)

    def test_main_maps_google_errors_without_exposing_tokens(self):
        source = (ROOT / "main.py").read_text(encoding="utf-8")
        for code in ("no_credential", "unsupported", "provider_configuration", "invalid_credential", "configuration_required"):
            self.assertIn(code, source)
        self.assertNotIn("event.get('idToken')", source)
        self.assertNotIn("event.get('token')", source)

    def test_current_google_dependencies_are_supported_release_targets(self):
        gradle = GRADLE.read_text(encoding="utf-8")
        self.assertIn('androidx.credentials:credentials:1.6.0', gradle)
        self.assertIn('androidx.credentials:credentials-play-services-auth:1.6.0', gradle)
        self.assertIn('googleid:1.2.0', gradle)

    def test_workflow_validates_the_public_web_client_id_shape(self):
        workflow = WORKFLOW.read_text(encoding="utf-8")
        self.assertIn("REIFLIX_GOOGLE_WEB_CLIENT_ID", workflow)
        self.assertIn('endswith(".apps.googleusercontent.com")', workflow)
        self.assertIn("login will show configuration required", workflow)

    def test_google_client_id_shape_matches_google_oauth_format(self):
        pattern = re.compile(r"^[0-9]+-[a-z0-9-]+\.apps\.googleusercontent\.com$")
        self.assertRegex("123456789-example.apps.googleusercontent.com", pattern)
        self.assertNotRegex("not-a-google-client-id", pattern)


if __name__ == "__main__":
    unittest.main()
