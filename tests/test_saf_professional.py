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
        self.assertIn("sqliteLibraryRepository.upsertSafFolder(", source)
        self.assertIn('scanTree(configuredReference, requestId)', source)
        self.assertIn('"nativeFolderConfigOwner", true', source)
        self.assertNotIn('publishScanRequest("PERMISSION_CHANGE", "saf", uri.toString()', source)

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

    def test_picker_requests_persistable_read_and_write_grants(self):
        source = MAIN_ACTIVITY.read_text(encoding="utf-8")
        picker = source[source.index("private fun openTreePicker"):source.index("override fun onWindowFocusChanged", source.index("private fun openTreePicker"))]
        for token in (
            "Intent.ACTION_OPEN_DOCUMENT_TREE",
            "Intent.FLAG_GRANT_READ_URI_PERMISSION",
            "Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION",
            "Intent.FLAG_GRANT_PREFIX_URI_PERMISSION",
        ):
            self.assertIn(token, picker)
        self.assertIn("Intent.FLAG_GRANT_WRITE_URI_PERMISSION", picker)


    def test_saf_identity_reconciliation_uses_canonical_mailbox_and_store(self):
        store = (ROOT / "core/library_store.py").read_text(encoding="utf-8")
        service = (ROOT / "core/library_service.py").read_text(encoding="utf-8")
        main = MAIN.read_text(encoding="utf-8")
        view_model = (
            ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/viewmodel/ReiAnixLibraryViewModel.kt"
        ).read_text(encoding="utf-8")
        self.assertIn("reconcile_saf_document_identity_change", store)
        self.assertIn("UPDATE episode_observations", store)
        self.assertIn("reconcile_saf_document_identity_change", service)  # compatibility for older queued events
        native_repository = (
            ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/data/sqlite/ReiAnixSqliteLibraryRepository.kt"
        ).read_text(encoding="utf-8")
        self.assertIn("reconcileSafDocumentIdentityChange(", native_repository)
        self.assertIn("nativeLibraryRepository.reconcileSafDocumentIdentityChange(", view_model)
        self.assertIn("nativeIdentityOwner", view_model)
        self.assertIn("saf_document_identity_change", main)
        self.assertIn("payload.get('nativeIdentityOwner') is True", main)
        self.assertIn("NativeMailbox.write(appContext, event)", view_model)
        self.assertIn("treeUri", view_model)
        self.assertIn("catalogReconciliationQueued", view_model)
        self.assertIn("asyncio.create_task(_request_reconciled_saf_rescan())", main)
        self.assertIn("target_tree_uri=tree_uri", main)
        self.assertNotIn("library.sqlite", view_model)
        operations = (
            ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/storage/SafFileOperations.kt"
        ).read_text(encoding="utf-8")
        self.assertIn("mutationAttempted", operations)
        self.assertNotIn("runCatching { DocumentsContract.isChildDocument", operations)
        self.assertIn("a movimentação foi recusada por segurança", operations.lower())
        self.assertIn("FailureKind.UNSUPPORTED", operations)
        self.assertIn("FailureKind.PARTIAL", operations)

    def test_native_saf_file_manager_uses_scoped_grants_and_confirms_operations(self):
        operations = (
            ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/storage/SafFileOperations.kt"
        ).read_text(encoding="utf-8")
        manager = (
            ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/ui/storage/ReiAnixSafFileManager.kt"
        ).read_text(encoding="utf-8")
        organize = (
            ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/ui/organize/ReiAnixOrganize.kt"
        ).read_text(encoding="utf-8")
        for token in (
            "grantAllows",
            "grant.isWritePermission",
            "SafScanner.isAuthorizedDocumentForTree",
            "FailureKind.PARTIAL",
            "DocumentsContract.moveDocument",
            "DocumentsContract.createDocument",
            "DocumentsContract.renameDocument",
            "buildChildDocumentsUriUsingTree",
            "findParentInTree",
            "CancellationException",
        ):
            self.assertIn(token, operations)
        for token in ("createSafDirectory", "renameSafItem", "moveSafItem", "deleteSafItem",
                      "viewModel.refresh()", "recoverable"):
            self.assertIn(token, manager)
        self.assertIn("ReiAnixSafFileManagerRoute", organize)
        self.assertIn("Gerenciar arquivos locais (SAF)", organize)
        self.assertIn("preservação de histórico e progresso não está garantida", manager)
        self.assertIn("preserve o registro", operations)

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

    def test_document_authorization_uses_provider_scoped_uri_not_document_id_prefix(self):
        source = SAF.read_text(encoding="utf-8")
        tree_scoped = source[
            source.index("fun isAuthorizedDocumentForTree"):
            source.index("fun isAuthorizedDocument(context")
        ]
        general = source[
            source.index("fun isAuthorizedDocument(context"):
            source.index("private fun isDocumentWithinTree")
        ]
        for block in (tree_scoped, general):
            self.assertIn("isDocumentWithinTree(context", block)
            self.assertNotIn("isWithinTree(tree.documentId, documentId)", block)
        helper = source[
            source.index("private fun isDocumentWithinTree"):
            source.index("fun displayName")
        ]
        self.assertIn("hasPersistedReadPermission(context, treeUri)", helper)
        self.assertIn("DocumentsContract.buildDocumentUriUsingTree(treeUri, tree.documentId)", helper)
        self.assertIn("DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)", helper)
        self.assertIn("DocumentsContract.isChildDocument(context.contentResolver, rootDocument, candidate)", helper)
        self.assertIn("DocumentsContract.buildChildDocumentsUriUsingTree", source)
        self.assertIn("DocumentsContract.isChildDocument", source)
        self.assertNotIn("isDocumentIdWithinTree", source)

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


    def test_saf_removal_releases_permission_before_single_canonical_sqlite_delete(self):
        view_model = (
            ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/viewmodel/ReiAnixLibraryViewModel.kt"
        ).read_text(encoding="utf-8")
        remove_request = view_model[
            view_model.index("fun removeSafTree("):view_model.index("fun onSafRemovalTerminal(", view_model.index("fun removeSafTree("))
        ]
        self.assertNotIn("nativeLibraryRepository.removeSafFolder", remove_request)
        self.assertIn('createNativeStorageActionIntent(appContext, "release_tree", normalized)', remove_request)
        self.assertIn("pendingSafRemovalReferences.add(normalized)", remove_request)

        source = MAIN_ACTIVITY.read_text(encoding="utf-8")
        release = source[source.index("private fun releaseTree("):source.index("private fun verifyTree(", source.index("private fun releaseTree("))]
        permission_release = release.index("contentResolver.releasePersistableUriPermission(")
        sqlite_remove = release.index("appDependencies.sqliteLibraryRepository.removeSafFolder(normalizedReference)")
        self.assertLess(permission_release, sqlite_remove)
        self.assertEqual(1, release.count("appDependencies.sqliteLibraryRepository.removeSafFolder("))
        self.assertIn("PERSISTED_PERMISSION_RELEASE_FAILED", release)
        self.assertIn("SQLITE_SOURCE_REMOVAL_FAILED", release)
        self.assertIn("sourceAccessRecoveryPersisted", release)
        self.assertIn("onSafRemovalTerminal(normalizedReference)", release)
        self.assertIn("SAF_RELEASE_DUPLICATE_JOINED", release)

    def test_global_native_scan_waits_for_each_authorized_source_and_records_terminal_results(self):
        source = MAIN_ACTIVITY.read_text(encoding="utf-8")
        start = source.index("private fun scanConfiguredSafRoots(")
        end = source.index("private fun scanTree(", start)
        block = source[start:end]
        status_calculation = block.index("val status = when {")
        terminal_event = block.index('.put("type", "saf_library_scan_complete")', status_calculation)
        scheduled_sources = (
            "pendingSources += sourceKey to scanId",
            "pendingSources += MediaStoreScanner.SOURCE to mediaScanId",
            "pendingSources += BroadStorageScanner.SOURCE to broadScanId",
        )
        await_loop = block.index("for ((source, scanId) in pendingSources)")
        for scheduled in scheduled_sources:
            self.assertIn(scheduled, block)
            self.assertLess(block.index(scheduled), await_loop)
        self.assertLess(await_loop, terminal_event)
        self.assertIn("NativeScanController.progress.first", block)
        self.assertIn('put("sourceResults", sourceResults)', block)
        self.assertIn('put("requestedSources", requestedSources)', block)
        self.assertIn("requestedSources == 0 -> \"FAILED\"", block)
        self.assertIn("successfulScopes == requestedSources && failedScopes == 0", block)
        self.assertIn('put("noSourcesAvailable", requestedSources == 0)', block)
        self.assertNotIn('configuredScopes == 0 -> "FAILED"', block)
        self.assertIn('"ACTIVITY_NOT_RESUMED"', block)

    def test_scan_source_helpers_join_existing_runs_instead_of_dispatching_duplicates(self):
        source = MAIN_ACTIVITY.read_text(encoding="utf-8")
        for marker in ("private fun scanMediaStore(", "private fun scanAllStorage("):
            start = source.index(marker)
            next_marker = source.find("\n    private fun ", start + len(marker))
            block = source[start:next_marker if next_marker >= 0 else len(source)]
            self.assertIn("): String?", block[:160])
            self.assertIn("NativeScanController.activeScanId(", block)
            self.assertIn('put("phase", "already_running")', block)
            self.assertIn("NativeScanController.launch(scanId)", block)

    def test_resumed_refresh_includes_authorized_sources_and_does_not_consume_active_scan_hints(self):
        source = MAIN_ACTIVITY.read_text(encoding="utf-8")
        resumed = source[
            source.index('"scan_library" -> {'):
            source.index('"play" -> {', source.index('"scan_library" -> {'))
        ]
        self.assertIn("includeAuthorizedDiagnostics = true", resumed)

        media_scan = source[
            source.index("private fun scanMediaStore("):
            source.index("private fun cancelNativeScans(", source.index("private fun scanMediaStore("))
        ]
        self.assertLess(
            media_scan.index("if (!NativeScanController.begin(scanId, MediaStoreScanner.SOURCE))"),
            media_scan.index("MediaStoreScanner.clearChangeNotification()"),
        )
        self.assertIn('put("phase", "already_running")', media_scan)


    def test_global_scan_terminal_statuses_distinguish_success_partial_failure_cancel_and_empty(self):
        source = MAIN_ACTIVITY.read_text(encoding="utf-8")
        start = source.index("private fun scanConfiguredSafRoots(")
        end = source.index("private fun scanTree(", start)
        block = source[start:end]
        for contract in (
            'requestedSources == 0 -> "FAILED"',
            'cancelledScopes == requestedSources && successfulScopes == 0 && failedScopes == 0 -> "CANCELLED"',
            'successfulScopes == requestedSources && failedScopes == 0 && cancelledScopes == 0 -> "COMPLETED"',
            'successfulScopes > 0 -> "PARTIAL"',
            'put("noSourcesAvailable", requestedSources == 0)',
            'put("sourceResults", sourceResults)',
            'recordSourceStartFailure(MediaStoreScanner.SOURCE, "scan_not_started")',
            'recordSourceStartFailure(BroadStorageScanner.SOURCE, "scan_not_started")',
            'put("requestId", requestId ?: "")',
            'put("scanId", scanId)',
        ):
            self.assertIn(contract, block)
        self.assertIn("MediaStoreScanner.hasReadPermission(this@MainActivity)", block)
        self.assertIn("BroadStorageScanner.hasAccess(this@MainActivity)", block)
        self.assertIn("ScanLifecycleState.CANCELLED ->", block)
        self.assertIn("ScanLifecycleState.PARTIAL ->", block)

    def test_saf_removal_errors_are_explicit_and_cancellation_is_not_swallowed(self):
        source = MAIN_ACTIVITY.read_text(encoding="utf-8")
        release = source[
            source.index("private fun releaseTree("):
            source.index("private fun verifyTree(", source.index("private fun releaseTree("))
        ]
        for code in (
            '"MISSING_TREE_URI"',
            '"INVALID_TREE_URI"',
            '"PERSISTED_PERMISSION_RELEASE_FAILED"',
            '"SQLITE_SOURCE_REMOVAL_FAILED"',
        ):
            self.assertIn(code, release)
        self.assertIn("catch (cancelled: CancellationException)", release)
        self.assertIn("throw cancelled", release)
        self.assertIn("alreadyAbsent", release)
        self.assertIn("sourceAlreadyAbsent", release)
        self.assertIn("sourceAccessRecoveryPersisted", release)
        self.assertIn('updateSafFolderAccess(', release)
        self.assertIn('"unavailable"', release)
        self.assertIn("pendingSafReleaseWaiters.remove(normalizedReference)", release)
        self.assertIn('.put("requestId", waiterId)', release)

    def test_cancelled_global_scan_remains_cancelled_in_the_compose_command_result(self):
        source = MAIN.read_text(encoding="utf-8")
        start = source.index("elif event_type == 'saf_library_scan_complete':")
        end = source.index("elif event_type == 'saf_scan':", start)
        block = source[start:end]
        self.assertIn('if native_status == "CANCELLED":', block)
        self.assertIn('"refresh",\n                                            "CANCELLED"', block)
        self.assertIn('"native_scan_cancelled" if native_status == "CANCELLED" else "native_scan_failed"', block)
        self.assertNotIn('if native_status == "CANCELLED":\n                                        compose_library_bridge.write_command_result(\n                                            event_request_id,\n                                            "refresh",\n                                            "FAILED"', block)


    def test_python_refresh_error_uses_global_source_count_not_saf_folder_count(self):
        source = MAIN.read_text(encoding="utf-8")
        block = source[source.index("elif event_type == 'saf_library_scan_complete':"):source.index("elif event_type == 'saf_scan':", source.index("elif event_type == 'saf_library_scan_complete':"))]
        self.assertIn('payload.get("noSourcesAvailable") is True', block)
        self.assertIn('payload.get("requestedSources")', block)
        self.assertNotIn('if payload.get(\'configuredScopes\') == 0', block)


if __name__ == "__main__":
    unittest.main()
