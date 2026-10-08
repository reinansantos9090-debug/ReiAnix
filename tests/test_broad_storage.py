import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

class TestBroadStorageArchitecture(unittest.TestCase):
    def test_manifest_and_scanner(self):
        manifest = (ROOT / "android/app/src/main/AndroidManifest.xml").read_text(encoding="utf-8")
        scanner = (ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/scanner/BroadStorageScanner.kt").read_text(encoding="utf-8")
        self.assertIn("MANAGE_EXTERNAL_STORAGE", manifest)
        self.assertIn("Environment.isExternalStorageManager()", scanner)
        self.assertIn("fun accessSnapshot(context: Context): JSONObject", scanner)
        self.assertIn('child == "data" || child == "obb"', scanner)
        self.assertIn(".nomedia", scanner)
        self.assertIn('"volumeName"', scanner)
        self.assertIn('"permissionAuthority"', scanner)
        self.assertIn('"type", "ACCESS_DENIED"', scanner)
        self.assertIn('"VOLUME_UNMOUNTED"', scanner)

    def test_inaccessible_nested_directory_is_a_partial_scan(self):
        scanner = (ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/scanner/BroadStorageScanner.kt").read_text(encoding="utf-8")
        self.assertIn('.put("type", "ACCESS_DENIED")', scanner)
        self.assertIn('.put("type", "DIRECTORY_NOT_FOUND")', scanner)
        self.assertIn('.put("partial", errors.length() > 0 || cancelled)', scanner)

    def test_bridge_accepts_content_file_and_broad_path_references(self):
        from core.android_bridge import AndroidBridge
        self.assertTrue(AndroidBridge.is_local_media_reference("content://media/external/video/1"))
        self.assertTrue(AndroidBridge.is_local_media_reference("file:///storage/emulated/0/a.mkv"))
        self.assertTrue(AndroidBridge.is_local_media_reference("/storage/emulated/0/a.mkv"))
        self.assertFalse(AndroidBridge.is_local_media_reference("https://example.invalid/a.mkv"))

    def test_ingestion_accepts_file_uri(self):
        source = (ROOT / "core/library_service.py").read_text(encoding="utf-8")
        self.assertIn("unquote(urlparse(uri).path)", source)
        self.assertIn('uri.startswith("file://")', source)

    def test_main_activity_exposes_explicit_permission_actions(self):
        source = (ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/MainActivity.kt").read_text(encoding="utf-8")
        self.assertIn('"request_media_access" -> requestMediaAccess()', source)
        self.assertIn('"open_broad_storage_settings" -> {', source)
        self.assertIn("pendingBroadRequestId = pendingRequestId", source)
        self.assertIn("openBroadStorageSettings()", source)
        self.assertIn('"check_storage_access" -> {', source)
        self.assertIn('put("type", "broad_storage_status")', source)

    def test_bridge_exposes_permission_actions(self):
        source = (ROOT / "core/android_bridge.py").read_text(encoding="utf-8")
        self.assertIn('async def request_media_access(self)', source)
        self.assertIn('async def check_storage_access(self)', source)
        self.assertIn('async def open_broad_storage_settings(self)', source)

    def test_settings_shows_permission_rationale_and_controls(self):
        settings = (ROOT / "views/settings_view.py").read_text(encoding="utf-8")
        self.assertIn("Permissão de vídeos:", settings)
        self.assertIn("Acesso amplo:", settings)
        self.assertIn("Adicionar pasta", settings)
        self.assertIn("Verificar permissão de vídeos", settings)
        self.assertIn("Armazenamento amplo", settings)

    def test_refresh_requests_broad_storage(self):
        source = (ROOT / "main.py").read_text(encoding="utf-8")
        self.assertIn("scan_coordinator.request(", source)
        self.assertIn("ScanOrigin.USER_REFRESH", source)
        self.assertNotIn("await bridge.scan_all_storage()", source)
        self.assertIn("event_type == 'broad_storage_scan'", source)

    def test_broad_access_uses_only_android_authoritative_special_permission(self):
        source = (ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/MainActivity.kt").read_text(encoding="utf-8")
        scanner = (ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/scanner/BroadStorageScanner.kt").read_text(encoding="utf-8")
        runner = (ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/scanner/NativeScanRunner.kt").read_text(encoding="utf-8")
        self.assertIn("Environment.isExternalStorageManager()", scanner)
        self.assertIn("permissionAuthority", scanner)
        self.assertNotIn("legacyBroadPermissionRequester", source)
        self.assertNotIn("READ_EXTERNAL_STORAGE", scanner)
        self.assertIn("NativeIndex.failActiveGenerations", runner)

    def test_nomedia_directory_filtering_supported(self):
        broad_scanner = (ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/scanner/BroadStorageScanner.kt").read_text(encoding="utf-8")
        saf_scanner = (ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/scanner/SafScanner.kt").read_text(encoding="utf-8")
        self.assertIn('.equals(".nomedia", ignoreCase = true)', broad_scanner)
        self.assertIn('nomediaDirectories', broad_scanner)
        self.assertIn('name.equals(".nomedia",ignoreCase=true)', saf_scanner)
        self.assertIn('nomediaDirectories', saf_scanner)

    def test_intent_fallback_chain_in_main_activity(self):
        source = (ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/MainActivity.kt").read_text(encoding="utf-8")
        self.assertIn("ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION", source)
        self.assertIn("ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION", source)
        self.assertIn("ACTION_APPLICATION_DETAILS_SETTINGS", source)

    def test_player_pip_has_manifest_and_device_feature_fallback(self):
        manifest = (ROOT / "android/app/src/main/AndroidManifest.xml").read_text(encoding="utf-8")
        player = (ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/NativePlayerActivity.kt").read_text(encoding="utf-8")
        template = (ROOT / "scripts/prepare_flet_template.py").read_text(encoding="utf-8")
        self.assertIn('android:supportsPictureInPicture="true"', manifest)
        self.assertIn('android.software.picture_in_picture', manifest)
        self.assertIn('android:required="false"', manifest)
        self.assertIn("PackageManager.FEATURE_PICTURE_IN_PICTURE", player)
        self.assertIn("setAutoEnterEnabled(", player)
        self.assertIn("player.playWhenReady && player.isPlaying", player)
        self.assertIn("android.software.picture_in_picture", template)

    def test_volume_identity(self):
        source = (ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/scanner/BroadStorageScanner.kt").read_text(encoding="utf-8")
        self.assertIn('"volumeId"', source)
        self.assertIn("volume.mediaStoreVolumeName", source)
        self.assertIn("volume.isRemovable", source)

if __name__ == "__main__":
    unittest.main()


class TestBroadStorageVolumeHardening(unittest.TestCase):
    def test_broad_scanner_checks_per_volume_all_files_access(self):
        source = (ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/scanner/BroadStorageScanner.kt").read_text(encoding="utf-8")
        self.assertIn("Environment.isExternalStorageManager(root.file)", source)
        self.assertIn("allFilesAccessForPath", source)
        self.assertIn("val volumeStillAuthorized", source)
        self.assertIn("volumeStillAuthorized", source)

    def test_broad_scanner_matches_nova_local_extension_surface(self):
        source = (ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/scanner/BroadStorageScanner.kt").read_text(encoding="utf-8")
        for extension in ("3g2","3gp","asf","divx","f4v","mpeg","mpg","ogm","ogv","ogx","vob","wtv","webm"):
            self.assertIn('"' + extension + '"', source)
