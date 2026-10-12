# Prompt 14 — Native SAF file-operation layer

This increment adds a native SAF operation service without replacing the existing scanner, catalog, Python store, or reconciliation owner.

## Current implementation

- `SafFileOperations` lists documents and exposes info/create/rename/move/delete operations using `DocumentFile`, `ContentResolver`, and `DocumentsContract`.
- Only `content://` document URIs with persisted SAF authorization are accepted. The service never derives filesystem paths from content URIs.
- Operation outcomes distinguish success from permission denial, missing items, invalid names, conflicts, unsupported provider behavior, partial/ambiguous outcomes, and generic failures.
- Create and rename reject path separators, traversal names, control characters, and overlong names, and check destination-name conflicts before mutation.
- Rename/create results are checked before returning success. Move uses the provider's document-move contract only; it does not claim a move when the provider cannot confirm it, and does not fall back to an unsafe copy/delete sequence.
- Delete reports success only after a follow-up existence check; ambiguous results remain recoverable failures.
- `ReiAnixLibraryViewModel` exposes suspend wrappers so Compose callers can invoke the native service from coroutine scopes.

## Important boundaries

The service intentionally does not write catalog rows. The current canonical catalog writer remains the existing Python `LibraryStore` / `LibraryService` path and the Kotlin SQLite repository is documented as read-only. Updating SQLite directly here would introduce a second writer and risk history, progress, metadata, and AniList associations. A successful SAF operation must be followed by the existing canonical command/reconciliation flow before the catalog is considered synchronized; callers must not update persisted identity on a failure or partial result.

The existing Organize screen currently presents catalog collections and storage onboarding, not a direct file-browser workflow. These wrappers are available to native callers, but the UI does not yet expose rename/move/delete/create-folder controls or a confirmation dialog. Destructive UI wiring and canonical catalog mutation are therefore not claimed complete in this prompt.

## Tests and validation

`SafFileOperationsTest` adds Robolectric tests for rejecting filesystem URIs, validating unsafe names before mutation, and reporting missing persisted grants. These tests use synthetic URIs and do not touch user files. They were committed but could not be executed in this GitHub-only editing session because no checked-out Gradle/Flet host workspace or Android runtime is available here. Device-level SAF provider behavior remains unverified.
