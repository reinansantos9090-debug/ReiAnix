# ReiAnix native media domain and identity

## Current record identity

- `AnimeRecordId` wraps the existing `anime.id` SQLite primary key.
- `AniListCatalogId` represents the optional external catalogue ID. It is not an anime primary key.
- `EpisodeRecordId` wraps the existing `episodes.id` primary key. The current schema stores episode identification, playback state, file location and media identity on this record; this change does not assert that a separate persisted file table exists.
- `LogicalEpisodeIdentity` is a nullable projection derived from the persisted anime ID, season and usable episode/absolute number. A title or filename alone never creates a logical episode identity.
- `CanonicalMediaIdentity` represents stable location evidence when available. A URI is retained separately because content URIs can change or become unavailable when Android storage permissions change.
- `MediaLocationKind` distinguishes a raw filesystem path, an empty/unresolved value, `file://`, generic `content://`, SAF tree, SAF document, and other URI schemes using only the stored string. Empty strings are not filesystem paths. SAF is identified conservatively using known DocumentsProvider authorities and exact path-segment structures; unknown providers remain generic `CONTENT_URI`. A malformed URI with a recognizable scheme is not downgraded to a filesystem path. Classification neither grants access nor creates a persisted identity.

The existing SQLite records remain the canonical persistence records. `SqliteDomainMappers.kt` provides read-only projections and preserves raw JSON columns (aliases, genres, user tags, and manual metadata ownership) rather than trying to parse and reserialize them. No table, column, primary key, index, database version or migration was changed.

## Identity rules ported to Kotlin

`data/media/LocalMediaIdentity` follows the contract from `core/media_identity.py`:

- Shared storage requires both a recognized storage location and a non-empty relative path.
- `/storage/emulated/0` maps to the primary volume. `primary`, `external_primary` and `external` are normalized to the same volume key.
- Relative path segments are URI-decoded, slash-normalized, resolved conservatively for `.` and `..`, and compared case-insensitively.
- External-storage SAF tree IDs are combined with the supplied document-relative path. Generic/cloud document providers are not assumed to point to primary shared storage.
- If no stable location evidence is present, identity remains null. For a non-filesystem content provider, a provider-scoped URI identity is used, not a guessed shared-storage identity.
- File size and modification time are discovery attributes, not inputs to a stable media identity.

`data/media/MediaTitleNormalizer` keeps title normalization separate from the physical-media identity algorithm. It does not fuzzy-match or promote a normalized title into an identifier. `data/media/MediaFilenameParser` recognizes explicit season/episode markers before considering numeric suffixes and exposes confidence/evidence/uncertainty for callers. Decimal episode syntax is bounded to 1–4 integer digits and up to 2 fractional digits; absolute episode numbering remains integer-based. For `12.125.mkv` and equivalent comma-separated numeric-only stems, the parser keeps `kind=UNKNOWN`, low confidence, null episode/absolute numbers, and the explicit `unsupported_numeric_suffix_decimal_precision` diagnostic in `identificationSource`, `unresolved`, and `ambiguities`. `NxM` markers participate in file-versus-folder season conflict detection.

## Python/Kotlin parity and boundary

The current Python parser and its tests remain in place. The pure-JVM helpers under the existing `data/media` package cover the core location identity contract and the main deterministic filename patterns (SxxExx, Temp/Temporada + E, NxM, episode markers, specials, movies, absolute markers and conservative numeric suffixes). Explicit season conflicts and multi-episode ranges are marked as unresolved rather than silently treated as unambiguous.

This step intentionally does not switch the existing scanner, library service, player, or legacy Python bridge to the new Kotlin parser. Their orchestration is outside this prompt's scope. Wiring discovery results to these domain helpers should happen with the scanner/library migration step, with cross-language fixture tests expanded against any remaining edge cases in `core/library_parser.py`; until then the legacy behavior remains available and is not removed.

## Playback, metadata and data integrity

The domain projection retains the existing episode row ID, anime foreign reference, progress, duration, watched flag, last-played timestamp, source/confidence/manual override flags, availability state and media identity. The model is a read projection only: it does not create database rows, rewrite IDs, update progress or mutate manual/user-owned metadata. Existing repository update operations continue to own writes to SQLite.

The domain models deliberately allow incomplete optional metadata and nullable logical identities. Episode numbers remain `Double?` and can preserve fractional values. `SqliteDomainMappers.kt` is read-only and retains persisted primary/foreign IDs, raw JSON, manual metadata ownership, AniList IDs, playback state and availability. No schema migration, data deletion, scanner rewrite, AniList integration or player rewrite was performed in this step.

## Validation workflow

The existing `.github/workflows/kotlin_jvm_tests.yml` workflow is triggered only by `workflow_dispatch`; it was not dispatched. Three existing Kotlin/JVM test files in the PR were extended in its prior Prompt 02.2 history with parser-decimal, URI/SAF and selected Unicode assertions; no test was added or modified during this closeout. The automatic `build_apk.yml` run `39180837684` succeeded for the pre-closeout SHA `5feecb570b9e25e8843d04b57fb9c25dc0a7dd0b` and produced APK/ZIP Actions artifacts, but it predates this closeout commit and does not validate the new HEAD. It did not run `:app:testDebugUnitTest`. The final automatic build result for the resulting HEAD is recorded in PR #199 after completion; JVM test assertions remain uncompiled/unexecuted in this task and are not considered approved.

The manual Kotlin/JVM workflow prepares the Flet-rendered Android host and generates the canonical SQLite test fixture before executing `:app:testDebugUnitTest`; do not trigger it from this prompt.


## Scope of Unicode normalization parity

The Kotlin genre normalizer explicitly handles `ß`/`ss` and Greek final sigma `ς`/`σ` after NFKD, in addition to accent removal, separator normalization and whitespace collapse. This is not a full implementation of every Unicode mapping in Python `str.casefold()`. Existing alias/registry identity is preserved; broader Unicode cases have not been asserted by executed tests in this prompt.


## Native discovery and recovery integration

The Android-native scanners call `DiscoveredMediaMetadata.attach`, which invokes `MediaFilenameParser`. The resulting `parsedMedia` fields flow through `NativeScanRunner` into `NativeDiscoveredMedia` and the existing Kotlin library indexing service. During recovery, older durable snapshots can predate that structured field; the recovery mapper now re-parses their stored relative path/file name in Kotlin when the parser contract is absent or incomplete. It uses a temporary JSON object and does not rewrite the durable discovery snapshot.

This removes the need for a Python parse fallback in the native scan/recovery flow. It does not remove `core/library_parser.py` globally: Python-owned filesystem scans and legacy compatibility ingestion without Kotlin parser metadata still use it until those consumers are migrated.


Recovery parsing validates the serialized numeric fields before trusting a previously stored `parsedMedia` object. Missing, incomplete or malformed numeric values are rebuilt from the stored relative path/file name through `MediaFilenameParser` Kotlin; the saved `NativeIndex` snapshot itself remains untouched.


The legacy-compatible Python ingest boundary can now consume the structured `parsedMedia` output produced by the native scanner and avoid reparsing those records in Python. This contract path is accepted only when required strings, kinds, confidence, numeric fields and evidence arrays are valid. Documents from desktop/older producers without a complete valid contract still use the Python parser, so this change does not remove that dependency globally.
