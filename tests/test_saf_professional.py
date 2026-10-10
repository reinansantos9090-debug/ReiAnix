import unittest
from pathlib import Path
import tempfile

from core.library_service import LibraryService
from core.library_store import LibraryStore

ROOT = Path(__file__).resolve().parents[1]
SAF = ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/scanner/SafScanner.kt"
MAIN_ACTIVITY = ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/MainActivity.kt"
MAILBOX = ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/bridge/NativeMailbox.kt"
MAIN = ROOT / "main.py"


class TestSafProfessionalContract(unittest.TestCase):
    def test_picker_result_checks_result_code_and_uri_before_persistence_or_scan(self):
        source = MAIN_ACTIVITY.read_text(encoding="utf-8")
        self.assertIn("result.resultCode != RESULT_OK || uri == null", source)
        self.assertIn("SafScanner.persistPermission(this, uri, flags)", source)
        self.assertIn('publishScanRequest("PERMISSION_CHANGE"', source)
        self.assertNotIn("scanTree(uri.toString(), requestId)", source)

    def test_picker_uses_direct_documents_ui_without_proxy_or_resolve_gate(self):
        source = MAIN_ACTIVITY.read_text(encoding="utf-8")
        picker = source[source.index("private fun openTreePicker"):source.index("override fun onWindowFocusChanged", source.index("private fun openTreePicker"))]
        self.assertIn("Intent.ACTION_OPEN_DOCUMENT_TREE", picker)
        self.assertIn("treePicker.launch(pickerIntent)", picker)
        self.assertIn("Intent.FLAG_GRANT_READ_URI_PERMISSION", picker)
        self.assertIn("Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION", picker)
        self.assertIn("Intent.FLAG_GRANT_PREFIX_URI_PERMISSION", picker)
        self.assertNotIn("SafPickerProxyActivity", picker)
        self.assertNotIn("proxyIntent", picker)
        self.assertNotIn("intent.resolveActivity(packageManager)", picker)

    def test_picker_has_focus_aware_watchdog_and_terminal_phases(self):
        source = MAIN_ACTIVITY.read_text(encoding="utf-8")
        for marker in (
            "SafPickerPhase",
            "WAITING_RESULT",
            "safPickerFocusLost",
            "SAF_PICKER_LAUNCH_TIMEOUT_MS",
            "SAF_PICKER_RETURN_GRACE_MS",
            "SAF_PICKER_TIMEOUT",
            "picker_watchdog",
        ):
            self.assertIn(marker, source)

    def test_picker_queues_the_real_request_id_across_lifecycle(self):
        source = MAIN_ACTIVITY.read_text(encoding="utf-8")
        self.assertIn('queueLifecycleAction("select_tree", correlationId)', source)
        self.assertIn("openTreePicker(requestId)", source)
        self.assertIn("openTreePicker(pendingRequestId)", source)

    def test_picker_validates_content_tree_uri_without_path_conversion(self):
        source = MAIN_ACTIVITY.read_text(encoding="utf-8")
        self.assertIn('uri.scheme?.lowercase() != "content"', source)
        self.assertIn("DocumentsContract.isTreeUri(uri)", source)
        self.assertNotIn('Uri.fromFile(File(uri.path', source)

    def test_manifest_and_generated_manifest_query_document_tree_visibility(self):
        manifest = (ROOT / "android/app/src/main/AndroidManifest.xml").read_text(encoding="utf-8")
        template = (ROOT / "scripts/prepare_flet_template.py").read_text(encoding="utf-8")
        self.assertIn("OPEN_DOCUMENT_TREE", manifest)
        self.assertIn('manifest.find("queries")', template)
        self.assertIn("android.intent.action.OPEN_DOCUMENT_TREE", template)

    def test_picker_uses_persistable_read_grant(self):
        source = MAIN_ACTIVITY.read_text(encoding="utf-8")
        picker = source[source.index("private fun openTreePicker"):source.index("override fun onWindowFocusChanged", source.index("private fun openTreePicker"))]
        for token in (
            "Intent.ACTION_OPEN_DOCUMENT_TREE",
            "Intent.FLAG_GRANT_READ_URI_PERMISSION",
            "Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION",
            "Intent.FLAG_GRANT_PREFIX_URI_PERMISSION",
        ):
            self.assertIn(token, picker)
        self.assertNotIn("Intent.FLAG_GRANT_WRITE_URI_PERMISSION", picker)

    def test_tree_identity_is_authority_document_and_volume_scoped(self):
        source = SAF.read_text(encoding="utf-8")
        self.assertIn("authority", source)
        self.assertIn("documentId", source)
        self.assertIn("volumeId", source)
        self.assertIn("StorageAuthorization.safIdentity(treeUri.toString())", source)
        self.assertIn("content://", source)
        authorization = (
            ROOT
            / "android/app/src/main/kotlin/com/reiflix/reiflix_local/storage/StorageAuthorization.kt"
        ).read_text(encoding="utf-8")
        self.assertIn('return "saf:" + authority.lowercase(java.util.Locale.ROOT) + ":" + documentId', authorization)

    def test_provider_states_are_explicit(self):
        source = SAF.read_text(encoding="utf-8")
        for state in ("STATUS_EMPTY_COMPLETE", "STATUS_PARTIAL", "STATUS_CANCELLED",
                      "STATUS_FAILED", "STATUS_REVOKED", "STATUS_UNAVAILABLE", "STATUS_COMPLETED"):
            self.assertIn(state, source)

    def test_explicitly_selected_saf_tree_scans_media_even_with_nomedia_markers(self):
        source = SAF.read_text(encoding="utf-8")
        self.assertIn('name.equals(".nomedia",ignoreCase=true)', source)
        self.assertIn("containsNoMediaMarker", source)
        self.assertIn("nomediaDirectories", source)
        self.assertIn("nomediaFiles", source)
        self.assertIn("SAF roots are explicitly selected by the user", source)
        self.assertNotIn("if(hasNoMedia)", source)
        marker = source.index("if(containsNoMediaMarker)")
        recurse = source.index("for((childId,childName) in directoriesToVisit)", marker)
        self.assertLess(marker, recurse)
        self.assertNotIn("return@use", source[marker:recurse])

    def test_metadata_absence_and_mime_fallback_are_not_fatal(self):
        source = SAF.read_text(encoding="utf-8")
        self.assertIn("metadataMissingSize", source)
        self.assertIn("metadataMissingModified", source)
        self.assertIn("mimeFallbacks", source)
        self.assertIn("application/octet-stream", source)

    def test_mailbox_classifies_saf_scan_lifecycle(self):
        source = MAILBOX.read_text(encoding="utf-8")
        self.assertIn('"CANCELLED" -> "scan_cancelled"', source)
        self.assertIn('"PARTIAL" -> "scan_partial"', source)
        self.assertIn('"FAILED" -> "scan_failed"', source)
        self.assertIn('"REVOKED" -> "saf_revoked"', source)
        self.assertIn('"UNAVAILABLE" -> "saf_unavailable"', source)

    def test_scan_failure_does_not_revoke_saf_authorization(self):
        source = MAIN.read_text(encoding="utf-8")
        block_start = source.find("elif event_type in {'saf_error','google_error'}:")
        block_end = source.find("elif event_type == 'google_error':", block_start + 1)
        if block_end < 0:
            block_end = len(source)
        block = source[block_start:block_end]
        self.assertIn("status == 'REVOKED'", block)
        self.assertIn("status in {'UNAVAILABLE', 'FAILED', 'PARTIAL'}", block)
        self.assertIn("update_folder_status(tree_uri, 'unavailable'", block)

    def test_persisted_tree_inventory_does_real_validation(self):
        source = MAIN_ACTIVITY.read_text(encoding="utf-8")
        self.assertIn("SafScanner.inspectTree(appContext, uri, requirePersisted = true)", source)
        self.assertIn("inventoryComplete", source)

    def test_store_persists_saf_identity(self):
        with tempfile.TemporaryDirectory() as data_dir:
            store = LibraryStore(data_dir)
            tree = "content://com.example.documents/tree/primary%3AMovies"
            store.add_folder(
                tree,
                name="Movies",
                kind="saf",
                saf_authority="com.example.documents",
                saf_document_id="primary:Movies",
                saf_volume_id="primary",
                saf_identity="saf:com.example.documents:primary:Movies",
            )
            folder = store.folders()[0]
            self.assertEqual("com.example.documents", folder["saf_authority"])
            self.assertEqual("primary:Movies", folder["saf_document_id"])
            self.assertEqual("primary", folder["saf_volume_id"])
            self.assertEqual("saf:com.example.documents:primary:Movies", folder["saf_identity"])

    def test_failed_native_ingest_keeps_previous_items_available(self):
        with tempfile.TemporaryDirectory() as data_dir:
            store = LibraryStore(data_dir)
            service = LibraryService(store)
            doc = {
                "uri": "content://provider/tree/document/1",
                "name": "Show S01E01.mkv",
                "relativePath": "Show S01E01.mkv",
                "mimeType": "video/x-matroska",
                "size": 100,
                "modifiedAt": 1000,
            }
            service.ingest_documents(
                "content://provider/tree/root",
                [doc],
                source_kind="saf",
                scan_id="ok",
                scan_stats={"status": "completed"},
            )
            service.ingest_documents(
                "content://provider/tree/root",
                [],
                source_kind="saf",
                scan_id="fail",
                scan_stats={"status": "failed"},
                scan_errors=["provider failure"],
            )
            row = store.physical_row(doc["uri"])
            self.assertFalse(row["missing"])
            self.assertEqual("available", row["availability_state"])
            self.assertEqual("error", store.scan_by_id("fail")["status"])


if __name__ == "__main__":
    unittest.main()
