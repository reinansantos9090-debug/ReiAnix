import tempfile
import threading
import unittest
from pathlib import Path

from core.library_service import LibraryService

ROOT = Path(__file__).resolve().parents[1]
NATIVE_DIR = ROOT / 'android/app/src/main/kotlin/com/reiflix/reiflix_local'
SCANNER_DIR = NATIVE_DIR / 'scanner'
STORAGE_DIR = NATIVE_DIR / 'storage'
BATCH_SIZE = 250

class ProbeStore:
    def __init__(self):
        self.run_id = 1
        self.progress = []
        self.account_data = {}
        self.scan = None
        self.reconcile_calls = 0
        self.snapshot = []
    def scan_by_id(self, scan_id):
        return self.scan
    def begin_scan(self, **kwargs):
        self.scan = {'id': self.run_id, 'scan_id': kwargs.get('scan_id'), 'status': 'running'}
        return self.run_id
    def latest_completed_native_generation(self, *args):
        return None
    def has_observation_for_generation(self, *args, **kwargs):
        return False
    def add_folder(self, *args, **kwargs):
        return None
    def reconcile_scope_generation(self, *args, **kwargs):
        self.reconcile_calls = getattr(self, "reconcile_calls", 0) + 1
        return 7
    def generation_anime_ids(self, *args, **kwargs):
        return set()
    def update_folder_status(self, *args, **kwargs):
        return None
    def catalog(self):
        return []
    def mark_snapshot(self, value):
        self.snapshot = value
    def account(self):
        return self.account_data
    def update_scan_progress(self, run_id, summary, **kwargs):
        self.progress.append(dict(summary))
        if self.scan is not None:
            self.scan.update({
                k: v for k, v in kwargs.items()
                if k in {"batch_id", "batch_number", "batch_size", "discovered", "processed"}
            })
        return True
    def finish_scan(self, run_id, summary):
        if self.scan is not None:
            self.scan.update(summary)
            self.scan["status"] = summary.get("status", "completed")
        return True

class BatchServiceProbe(LibraryService):
    def __init__(self):
        self.store = ProbeStore()
        self._scan_lock = threading.Lock()
        self.artwork = None
    def _record_document(self, *, document, result, **kwargs):
        result.files += 1
        result.new += 1
        result.videos += 1
        return document['uri']

def documents(count, offset=0):
    for index in range(offset, offset + count):
        yield {'uri': f'content://library-scale/{index}', 'name': f'{index}.mp4'}

class TestScalability(unittest.TestCase):
    def test_duplicate_document_across_batches_is_deduplicated(self):
        service = BatchServiceProbe()
        doc = next(documents(1))
        first = service.ingest_documents_batch(
            "broad-storage", [doc],
            source_kind="broad_storage", scan_id="dup-scan",
            scope_kind="volume", scope_ref="external_primary",
            scan_generation=2, generation_id="native:test:2",
            batch_id="batch-1", batch_number=1, batch_size=1,
        )
        service.store.has_observation_for_generation = lambda *args, **kwargs: True
        second = service.ingest_documents_batch(
            "broad-storage", [doc],
            source_kind="broad_storage", scan_id="dup-scan",
            scope_kind="volume", scope_ref="external_primary",
            scan_generation=2, generation_id="native:test:2",
            batch_id="batch-2", batch_number=2, batch_size=1,
        )
        self.assertEqual(first["new"], 1)
        self.assertEqual(second["duplicates"], 1)

    def test_failed_finalization_does_not_reconcile(self):
        service = BatchServiceProbe()
        service.ingest_documents_batch(
            "broad-storage", list(documents(10)),
            source_kind="broad_storage", scan_id="failed-scan",
            scope_kind="volume", scope_ref="external_primary",
            scan_generation=3, generation_id="native:test:3",
            batch_id="batch-1", batch_number=1, batch_size=10,
        )
        result = service.finish_ingest_documents(
            "broad-storage", source_kind="broad_storage",
            scan_id="failed-scan", scope_kind="volume",
            scope_ref="external_primary", scan_generation=3,
            generation_id="native:test:3", status="failed",
        )
        self.assertEqual(result.status, "failed")
        self.assertEqual(service.store.reconcile_calls, 0)

    def test_completed_finalization_reconciles_once(self):
        service = BatchServiceProbe()
        service.ingest_documents_batch(
            "broad-storage", list(documents(10)),
            source_kind="broad_storage", scan_id="complete-scan",
            scope_kind="volume", scope_ref="external_primary",
            scan_generation=4, generation_id="native:test:4",
            batch_id="batch-1", batch_number=1, batch_size=10,
        )
        result = service.finish_ingest_documents(
            "broad-storage", source_kind="broad_storage",
            scan_id="complete-scan", scope_kind="volume",
            scope_ref="external_primary", scan_generation=4,
            generation_id="native:test:4", status="completed",
        )
        self.assertEqual(result.status, "completed")
        self.assertEqual(service.store.reconcile_calls, 1)

    def test_only_completed_generation_is_considered_latest(self):
        from core.library_store import LibraryStore
        with tempfile.TemporaryDirectory() as data_dir:
            store = LibraryStore(data_dir)
            cancelled = store.begin_scan(
                scan_id="cancelled-generation", source_kind="broad_storage",
                scope_kind="volume", scope_ref="external_primary",
                native_generation=9, generation_id="native:test:9",
            )
            store.finish_scan(cancelled, {"status": "cancelled"})
            self.assertIsNone(
                store.latest_completed_native_generation(
                    "broad_storage", "volume", "external_primary"
                )
            )
            completed = store.begin_scan(
                scan_id="completed-generation", source_kind="broad_storage",
                scope_kind="volume", scope_ref="external_primary",
                native_generation=10, generation_id="native:test:10",
            )
            store.finish_scan(completed, {"status": "completed"})
            self.assertEqual(
                10,
                store.latest_completed_native_generation(
                    "broad_storage", "volume", "external_primary"
                )
            )

    def test_scan_progress_is_persisted_in_existing_sqlite_store(self):
        from core.library_store import LibraryStore
        with tempfile.TemporaryDirectory() as data_dir:
            store = LibraryStore(data_dir)
            run_id = store.begin_scan(
                scan_id="persisted-scan", source_kind="broad_storage",
                scope_kind="volume", scope_ref="external_primary",
                native_generation=5, generation_id="native:test:5",
            )
            store.update_scan_progress(
                run_id, {"files": 250, "videos": 250, "new": 200},
                request_id="req-5", source="broad_storage",
                volume_id="external_primary", scope="external_primary",
                batch_id="batch-7", batch_number=7, batch_size=250,
                discovered=250, processed=250, inserted=200, elapsed_ms=12,
            )
            row = store.scan_by_id("persisted-scan")
            self.assertEqual(row["batch_id"], "batch-7")
            self.assertEqual(row["batch_size"], 250)
            self.assertEqual(row["processed"], 250)
            self.assertEqual(row["inserted_files"], 200)
    def run_load(self, total):
        service = BatchServiceProbe()
        peak_batch = 0
        processed = 0
        for start in range(0, total, BATCH_SIZE):
            batch = list(documents(min(BATCH_SIZE, total - start), start))
            peak_batch = max(peak_batch, len(batch))
            result = service.ingest_documents_batch(
                'broad-storage', batch, source_kind='broad_storage',
                scan_id='load-scan', scope_kind='volume', scope_ref='external_primary',
                scan_generation=1, generation_id='native:test:1',
                batch_id=f'batch-{start // BATCH_SIZE + 1}',
                batch_number=start // BATCH_SIZE + 1, batch_size=len(batch),
            )
            processed += int(result['processed'])
        self.assertEqual(processed, total)
        self.assertLessEqual(peak_batch, BATCH_SIZE)
        self.assertEqual(len(service.store.progress), (total + BATCH_SIZE - 1) // BATCH_SIZE)

    def test_10000_documents_incremental(self):
        self.run_load(10_000)

    def test_50000_documents_incremental(self):
        self.run_load(50_000)

    def test_100000_documents_incremental(self):
        self.run_load(100_000)

    def test_android_scanners_do_not_retain_global_document_arrays(self):
        broad = (SCANNER_DIR / 'BroadStorageScanner.kt').read_text(encoding='utf-8')
        saf = (SCANNER_DIR / 'SafScanner.kt').read_text(encoding='utf-8')
        media = (SCANNER_DIR / 'MediaStoreScanner.kt').read_text(encoding='utf-8')
        main = (NATIVE_DIR / 'MainActivity.kt').read_text(encoding='utf-8')
        for source in (broad, saf, media):
            self.assertIn('NativeBatch.Accumulator', source)
        self.assertNotIn('val docsByVolume =', broad)
        self.assertNotIn('val preparedDocuments =', broad)
        self.assertNotIn('val documents=JSONArray()', saf)
        self.assertNotIn('val documents=JSONArray()', media)
        self.assertIn('saf_scan_batch', main)
        self.assertIn('broad_storage_scan_batch', main)
        self.assertIn('mediastore_scan_batch', main)

    def test_batch_size_is_documented_and_bounded(self):
        native_batch = (STORAGE_DIR / 'NativeBatch.kt').read_text(encoding='utf-8')
        self.assertRegex(native_batch, r'DEFAULT_SIZE = 250')
        self.assertIn('value.coerceIn(MIN_SIZE, MAX_SIZE)', native_batch)

if __name__ == '__main__':
    unittest.main()
