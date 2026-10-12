# Native consumption contract — Prompt 11

The native ReiAnixConsumptionService uses the existing ReiAnixSqliteLibraryRepository and canonical filesDir/data/library.sqlite3. It creates no parallel database, table, or identity namespace. The canonical episode ID is the write key. Media3-facing positions use milliseconds while the existing episodes.progress and episodes.duration columns remain seconds.

The service provides progress recovery, progress writes, explicit watched/unwatched state, Continue Watching, and recently watched queries. Negative positions and invalid durations are rejected. Known duration bounds a saved position; unknown duration remains unknown. The existing product completion criterion of 90% is retained, and completion is never inferred from zero/unknown duration.

Repository writes are transactional and reject an older playedAtEpochSeconds update when a newer last_played_at already exists, protecting persisted state from delayed callbacks. SQLite errors and missing episode IDs propagate rather than being reported as successful writes. Existing schema v29, migration history, identifiers, and data are unchanged.

## Integration boundary

NativePlayerActivity continues publishing the established player event contract through NativeMailbox, currently consumed by main.py / LibraryStore and used for legacy UI refreshes. It is intentionally not switched to dual-write the SQLite service: that would introduce competing writers and could bypass existing event/UI contracts. The service is available for the native player integration cutover, but routing live player callbacks exclusively through it requires the matching bridge consumer to skip Python persistence while still processing notifications. That cutover is not claimed complete in this prompt.

## Validation

The service policy tests cover unknown versus saved positions, watched/in-progress state, and millisecond units. Run the existing manual Kotlin JVM workflow or :app:testDebugUnitTest in a configured Android build environment. No workflow triggers were changed.