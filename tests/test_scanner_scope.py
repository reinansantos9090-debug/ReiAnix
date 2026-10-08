import os
import tempfile
import unittest
from pathlib import Path

from core.library_service import LibraryService
from core.library_store import LibraryStore
from core.storage_access import saf_source_identity

ROOT = Path(__file__).resolve().parents[1]
SAF = ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/scanner/SafScanner.kt"
MEDIA = ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/scanner/MediaStoreScanner.kt"
BROAD = ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/scanner/BroadStorageScanner.kt"

class ScopePolicyTests(unittest.TestCase):
    ROOT_URI = "content://com.android.externalstorage.documents/tree/primary%3AAnime"
    ROOT_ID = "primary:Anime"
    ROOT_IDENTITY = "saf:com.android.externalstorage.documents:primary:Anime"

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.store = LibraryStore(self.tmp.name)
        self.service = LibraryService(self.store)
        self.store.add_folder(self.ROOT_URI, name="Anime", kind="saf", authorization="granted",
                              saf_authority="com.android.externalstorage.documents", saf_document_id=self.ROOT_ID,
                              saf_volume_id="primary", saf_identity=self.ROOT_IDENTITY)

    def tearDown(self):
        self.tmp.cleanup()

    def policy(self):
        return self.service._library_source_policy(self.ROOT_URI, "saf", self.ROOT_URI)

    def document(self, document_id, *, tree_uri=None, scope=None, authority="com.android.externalstorage.documents"):
        encoded = document_id.replace(":", "%3A").replace("/", "%2F").replace(" ", "%20")
        return {"uri": f"content://{authority}/document/{encoded}", "treeUri": tree_uri or self.ROOT_URI,
                "documentId": document_id, "scope": scope or self.ROOT_IDENTITY, "name": Path(document_id).name,
                "mimeType": "video/x-matroska"}

    def test_nested_files_are_inside_selected_source(self):
        policy = self.policy()
        for doc_id in ("primary:Anime/One Piece/episode01.mkv", "primary:Anime/One Piece/episode02.mkv", "primary:Anime/Naruto/Season 01/episode01.mp4"):
            self.assertTrue(self.service._validate_library_document_scope(self.document(doc_id), policy)[0])

    def test_neighboring_prefixes_are_rejected(self):
        policy = self.policy()
        for doc_id in ("primary:AnimeBackup/episode01.mp4", "primary:Anime2/episode01.mp4", "primary:Movies/episode01.mp4"):
            ok, reason = self.service._validate_library_document_scope(self.document(doc_id), policy)
            self.assertFalse(ok)
            self.assertEqual("OUTSIDE_SOURCE", reason)

    def test_wrong_tree_and_authority_are_rejected(self):
        policy = self.policy()
        other_tree = "content://com.android.externalstorage.documents/tree/primary%3AAnime2"
        doc = self.document("primary:Anime2/episode01.mp4", tree_uri=other_tree, scope=saf_source_identity(other_tree))
        ok, reason = self.service._validate_library_document_scope(doc, policy)
        self.assertFalse(ok)
        self.assertEqual("OUTSIDE_SOURCE", reason)
        doc = self.document("primary:Anime/episode01.mp4", authority="com.example.other")
        ok, reason = self.service._validate_library_document_scope(doc, policy)
        self.assertFalse(ok)

    def test_missing_document_id_can_be_recovered_from_uri(self):
        doc = self.document("primary:Anime/episode01.mp4")
        doc.pop("documentId")
        self.assertTrue(self.service._validate_library_document_scope(doc, self.policy())[0])

    def test_non_content_uri_is_rejected_for_saf(self):
        doc = self.document("primary:Anime/episode01.mp4")
        doc["uri"] = "/storage/emulated/0/Anime/episode01.mp4"
        ok, reason = self.service._validate_library_document_scope(doc, self.policy())
        self.assertFalse(ok)
        self.assertEqual("INVALID_URI", reason)

    def test_media_and_broad_are_never_library_sources(self):
        for kind in ("mediastore", "broad_storage", "broad-storage"):
            policy = self.service._library_source_policy("volume", kind, "volume")
            self.assertFalse(policy["ok"])
            self.assertEqual("NON_LIBRARY_SCANNER", policy["reason"])

    def test_missing_source_and_revoked_source_have_no_fallback(self):
        self.store.remove_folder(self.ROOT_URI)
        self.assertEqual("SOURCE_NOT_CONFIGURED", self.service._library_source_policy(self.ROOT_URI, "saf", self.ROOT_URI)["reason"])
        self.store.add_folder(self.ROOT_URI, name="Anime", kind="saf", authorization="revoked",
                              saf_authority="com.android.externalstorage.documents", saf_document_id=self.ROOT_ID,
                              saf_volume_id="primary", saf_identity=self.ROOT_IDENTITY)
        self.assertEqual("SOURCE_PERMISSION_LOST", self.service._library_source_policy(self.ROOT_URI, "saf", self.ROOT_URI)["reason"])

    def test_filesystem_boundary_and_symlink_escape(self):
        with tempfile.TemporaryDirectory() as root, tempfile.TemporaryDirectory() as outside:
            anime = Path(root) / "Anime"; anime.mkdir()
            inside = anime / "episode01.mkv"; inside.write_bytes(b"x")
            sibling = Path(root) / "AnimeBackup"; sibling.mkdir()
            external = Path(outside) / "external.mp4"; external.write_bytes(b"x")
            link = anime / "escape.mp4"
            try: link.symlink_to(external)
            except (OSError, NotImplementedError): self.skipTest("symlink not supported")
            policy = {"ok": True, "kind": "filesystem", "root": os.path.realpath(anime)}
            self.assertTrue(self.service._validate_library_document_scope({"uri": str(inside)}, policy)[0])
            self.assertFalse(self.service._validate_library_document_scope({"uri": str(sibling / "video.mp4")}, policy)[0])
            self.assertEqual("OUTSIDE_SOURCE", self.service._validate_library_document_scope({"uri": str(link)}, policy)[1])

    def test_batch_hard_gate_rejects_non_library_scanner(self):
        result = self.service.ingest_documents_batch("mediastore:external:video",
            [{"uri": "content://media/external/video/1", "name": "Movie S01E01.mkv"}],
            source_kind="mediastore", enforce_library_source=True)
        self.assertTrue(result["ignored"])
        self.assertEqual("NON_LIBRARY_SCANNER", result["reason"])
        self.assertEqual([], [f for f in self.store.folders() if f["kind"] == "mediastore"])

    def test_batch_rejects_outside_saf_document(self):
        result = self.service.ingest_documents_batch(self.ROOT_URI, [self.document("primary:AnimeBackup/episode01.mkv")],
            source_kind="saf", scope_kind="root", scope_ref=self.ROOT_URI, enforce_library_source=True)
        self.assertFalse(result.get("ignored", False) is False and result.get("ignored", 0) == 0)
        self.assertGreaterEqual(result["ignored"], 1)
        self.assertEqual(0, result["inserted"])
        self.assertEqual(0, result["updated"])
        self.assertTrue(any("OUTSIDE_SOURCE" in error for error in result["errors"]))

    def test_android_scanners_require_explicit_library_scope(self):
        broad = BROAD.read_text(encoding="utf-8"); media = MEDIA.read_text(encoding="utf-8")
        self.assertIn("authorizedRoots: List<File> = emptyList()", broad)
        self.assertIn("NO_CONFIGURED_LIBRARY_SOURCE", broad)
        self.assertIn("libraryScopes:Collection<LibraryScope> = emptyList()", media)
        self.assertIn("NO_CONFIGURED_LIBRARY_SOURCE", media)

    def test_saf_scanner_membership_defense(self):
        source = SAF.read_text(encoding="utf-8")
        for token in ("isDocumentIdWithinTree", "isAuthorizedDocumentForTree", "rejectedOutsideSource", "OUTSIDE_SOURCE"):
            self.assertIn(token, source)

    def test_fixture_boundary(self):
        with tempfile.TemporaryDirectory() as root:
            root_path = Path(root); anime = root_path / "Anime"; anime.mkdir()
            allowed = [anime/"One Piece"/"episode01.mkv", anime/"One Piece"/"episode02.mkv", anime/"Naruto"/"episode01.mp4"]
            external = [root_path/"DCIM"/"CAMERA"/"video.mp4", root_path/"Movies"/"movie.mp4", root_path/"Download"/"video.mp4",
                        root_path/"WhatsApp"/"Media"/"WhatsApp Video"/"video.mp4", root_path/"Instagram"/"video.mp4"]
            for p in allowed + external:
                p.parent.mkdir(parents=True, exist_ok=True); p.write_bytes(b"x")
            for p in allowed: self.assertTrue(self.service._filesystem_path_within_source(p, anime))
            for p in external: self.assertFalse(self.service._filesystem_path_within_source(p, anime))
            self.assertFalse(self.service._filesystem_path_within_source(root_path/"AnimeBackup"/"video.mp4", anime))

    def test_saf_scanner_rejects_outside_directories_before_traversal(self):
        source = SAF.read_text(encoding="utf-8")
        self.assertIn("for((childId,childName) in directoriesToVisit)", source)
        self.assertIn("if(!isDocumentIdWithinTree(identity.documentId, childId))", source)

    def test_media_store_rejects_empty_scope_path(self):
        source = MEDIA.read_text(encoding="utf-8")
        self.assertIn("if(root.isBlank()) return false", source)


if __name__ == "__main__":
    unittest.main()
