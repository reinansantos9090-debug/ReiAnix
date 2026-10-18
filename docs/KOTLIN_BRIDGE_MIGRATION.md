# ReiAnix — Kotlin Bridge Migration (Prompt 18/29)

## Scope and baseline

This document records the selective bridge changes made for Prompt 18/29. The implementation is based on the official repository state from `main` at baseline commit `9df33cd5c86f0ca7676af94b50b54f8a8ebb46ec` (Prompt 17/29). The runtime is still hybrid, and the application's communication is not 100% Kotlin.

The architectural invariant remains: the canonical LibraryStore SQLite database is the source of persisted library/settings state. IPC files, snapshots, mailbox envelopes and ViewModel flows are transport or projections, not a second source of truth.

## Current communication map and classification

### Group A — native paths used when the required Kotlin services exist

- **Catalog reads and filtered library pages:** `ReiAnixLibraryViewModel` queries the shared `ReiAnixSqliteLibraryRepository` / `ReiAnixLibraryQueryService`, projects records through `ReiAnixNativeLibraryCatalogProjector` and the existing `LibraryUiMappers`, and updates the native page state. The stable anime/episode identifiers and the existing UI models are preserved.
- **Post-scan page refresh:** the compatibility repository still starts the native scan and observes command state, but it no longer starts another `load_library_page` request through the Python Compose bridge when a scan completes. The ViewModel's canonical SQLite refresh already rebuilds the catalog and active page. This removes a redundant cross-language request without removing explicit legacy commands that may still have consumers.
- **Settings values:** `ReiAnixSettingsRepository` reads and writes through `ReiAnixSqliteSettingsRepository` against the existing preferences table. If the optional legacy `settings.json` projection is missing, unreadable, stale or invalid while canonical SQLite preferences were read successfully, Compose keeps working from those confirmed values. The last known account/storage fields and pending operation states remain intact; a failed projection is not treated as a failed canonical preferences read.
- **Request lifecycle state:** `NativeRequestState` now prevents lifecycle regressions. A stale event cannot change a terminal request back to a non-terminal state, a running operation cannot return to received/queued, and a queued operation cannot return to received. Reads/serialized snapshots of its in-memory state are synchronized.
- **Direct settings/catalog state observation:** StateFlow remains the UI observation mechanism; existing repositories and ViewModels retain ownership of domain state, mutations and UI projections.

### Group B — partially migrated contracts retained for legacy consumers

- **`ComposeLibraryBridge` (`core/compose_library_bridge.py`):** retained for Python-produced projection/compatibility commands and scan/storage/onboarding state still observed at the native repository boundary. It is not used as the canonical writer for the native catalog. The Python implementation and its snapshot schema were not deleted because scan, storage and legacy consumers remain connected to the contract.
- **`ComposeSettingsBridge` (`core/compose_settings_bridge.py`):** retained as an auxiliary settings/account/storage projection and legacy command-result contract. It no longer determines the canonical persisted preference values when Kotlin has successfully read SQLite. Existing result files continue to correlate operations by request ID.
- **`NativeMailbox`:** retained as the durable Kotlin-to-Python event transport for real remaining consumers. The envelope contract (`eventId`, `eventVersion`, `eventType`, timestamps and optional `requestId`) and atomic publication stay unchanged. Best-effort telemetry still uses its bounded executor; control-plane writes retain their durability behavior.
- **`AndroidBridge` / `MainActivity`:** retained for command and lifecycle contracts that still cross the hybrid boundary, including platform SAF/MediaStore and permission operations, compatibility navigation, runtime notifications and the established player launch lifecycle. Kotlin handlers continue to own operations implemented natively.
- **`NativeCommandDispatcher`:** retained for legacy Python-to-Android internal player, thumbnail and player-transition cancellation commands. The native Media3 player remains the only player; this dispatcher is not a second domain service.
- **Post-commit notifications:** native settings changes and AniList metadata changes still notify Python where the legacy runtime refreshes derived projections or other consumers. A notification marked `nativePersisted` or `nativeHandled` must remain a notification, not trigger a second write/execution.

These contracts intentionally remain while any legitimate consumer needs them. They were not globally renamed or deleted, and Flet/Flutter were not removed.

### Group C — Python runtime and compatibility responsibilities still present

- `main.py` still starts the hybrid runtime, drains/acknowledges mailbox events, and dispatches legacy consumers.
- The Python Compose bridges still derive JSON projections from the Python LibraryService/LibraryStore and settings/account/storage providers for surfaces and capabilities that have not been fully disconnected from the runtime.
- `core/android_bridge.py`, `core/navigation.py`, the legacy Flet surfaces and Python-side handlers remain where the active application still has callers or state synchronization dependencies.
- Scanner/storage lifecycle, account, diagnostics, artwork invalidation, player progress and navigation include event contracts that have native producers but still have Python-side consumers. Those paths are not considered removable just because the native producer exists.

The above dependencies remain scheduled for later migration prompts. No stubbed success response or replacement persistence store was introduced.

## Request and response contracts

- Existing request IDs remain the identity for a specific operation. Player session/generation/transition IDs retain their existing semantics and are not substituted with message types.
- `NativeRequestState` only accepts forward lifecycle transitions and serializes state access. Duplicate/stale lifecycle updates are rejected instead of reopening terminal operations.
- Command result file schemas and request correlation used by the current library/settings repositories remain unchanged.
- A successful database write is still confirmed at the persistence boundary; mailbox publication reports only whether the notification was written. It does not redefine the outcome of the already committed write.
- The existing revision/generation checks in snapshot and page reconciliation remain in place to prevent stale state from replacing newer UI state.

## Files changed in this prompt

- `NativeRequestState.kt`: monotonic lifecycle-state transitions and synchronized state reads/serialization.
- `ReiAnixSettingsRepository.kt`: canonical SQLite settings remain available even if the optional Python settings projection fails; preserves account/storage projection state and operation acknowledgements when falling back.
- `ReiAnixLibraryRepository.kt`: removes its automatic duplicate Python `load_library_page` request after a native scan. It continues to support explicit bridge/page commands and legacy snapshots.
- `ReiAnixLibraryViewModel.kt`: invokes the native scan request without passing page filters into a legacy post-scan IPC continuation; its existing native catalog refresh handles the new page.
- `tests/test_bridge_cleanup.py`: adds source-level contracts for lifecycle monotonicity, canonical settings fallback and native paging ownership.
- `docs/KOTLIN_BRIDGE_MIGRATION.md`: this document.

## Tests and validation policy

The existing Python regression suite remains enabled by the repository workflows. Kotlin/JVM tests were not run manually, and no emulator/instrumented tests or new workflows were added. The final CI status and produced artifacts must be confirmed from the actual workflow runs associated with the final commit before this prompt is considered complete.

## Remaining dependencies and limitations

- Full Python/Flet/Flutter runtime removal is out of scope for Prompt 18/29.
- The mailbox and Python bridges remain required for active legacy consumers, platform command workflows, auxiliary storage/onboarding/scan projections, and compatibility notifications.
- The Compose library bridge still publishes a derived snapshot; this prompt retains its contract for storage and legacy state instead of inventing a parallel event system.
- Native settings still use mailbox notifications after successful SQLite commits so Python consumers can update their derived settings projection. The database remains canonical.
- These changes do not claim device-level runtime validation. Actual SAF revocation, storage/provider behavior and live hybrid UI interaction require device validation and are not established by compilation alone.
