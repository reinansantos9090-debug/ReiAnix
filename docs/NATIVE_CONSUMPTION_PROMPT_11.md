# Native consumption contract — Prompt 11

The native ReiAnixConsumptionService uses the existing ReiAnixSqliteLibraryRepository and canonical filesDir/data/library.sqlite3. It creates no parallel database, table, or identity namespace. The canonical episode ID is the write key. Media3-facing positions use milliseconds while the existing episodes.progress and episodes.duration columns remain seconds.

The service provides progress recovery, progress writes, explicit watched/unwatched state, Continue Watching, and recently watched queries. Negative positions and invalid durations are rejected. Known duration bounds a saved position; unknown duration remains unknown. The existing product completion criterion of 90% is retained, and completion is never inferred from zero/unknown duration.

Repository writes are transactional and reject an older playedAtEpochSeconds update when a newer last_played_at already exists, protecting persisted state from delayed callbacks. SQLite errors and missing episode IDs propagate rather than being reported as successful writes. Existing schema v29, migration history, identifiers, and data are unchanged.

## Integration boundary

NativePlayerActivity checkpoints the canonical numeric episode ID through ReiAnixConsumptionService on its dedicated progress worker before publishing the established event to NativeMailbox. Successful native writes carry nativeProgressPersisted=true; main.py still validates session/request/episode identity and publishes the library refresh, but skips LibraryStore.save_progress for that same event. If native persistence fails or no canonical numeric episode ID is available, the existing LibraryStore path remains the compatibility fallback. Player generation and transition metadata are carried with the event so the existing Python callback guard can reject stale notifications. The player consults the native service for resume only when neither saved Activity state nor an explicit positionMs intent extra exists.

## Validation

The service policy tests cover unknown versus saved positions, watched/in-progress state, and millisecond units. Run the existing manual Kotlin JVM workflow or :app:testDebugUnitTest in a configured Android build environment. No workflow triggers were changed.