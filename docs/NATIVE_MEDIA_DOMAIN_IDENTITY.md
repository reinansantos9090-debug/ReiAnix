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

`data/media/MediaTitleNormalizer` keeps title normalization separate from the physical-media identity algorithm. It does not fuzzy-match or promote a normalized title into an identifier. `data/media/MediaFilenameParser` recognizes explicit season/episode markers before considering numeric suffixes and exposes confidence/evidence/uncertainty for callers. Decimal episode syntax is bounded to 1–4 integer digits and up to 2 fractional digits; absolute episode numbering remains integer-based, and numeric-only decimal filenames remain ambiguous.

## Python/Kotlin parity and boundary

The current Python parser and its tests remain in place. The pure-JVM helpers under the existing `data/media` package cover the core location identity contract and the main deterministic filename patterns (SxxExx, Temp/Temporada + E, NxM, episode markers, specials, movies, absolute markers and conservative numeric suffixes). Explicit season conflicts and multi-episode ranges are marked as unresolved rather than silently treated as unambiguous.

This step intentionally does not switch the existing scanner, library service, player, or legacy Python bridge to the new Kotlin parser. Their orchestration is outside this prompt's scope. Wiring discovery results to these domain helpers should happen with the scanner/library migration step, with cross-language fixture tests expanded against any remaining edge cases in `core/library_parser.py`; until then the legacy behavior remains available and is not removed.

## Playback, metadata and data integrity

The domain projection retains the existing episode row ID, anime foreign reference, progress, duration, watched flag, last-played timestamp, source/confidence/manual override flags, availability state and media identity. The model is a read projection only: it does not create database rows, rewrite IDs, update progress or mutate manual/user-owned metadata. Existing repository update operations continue to own writes to SQLite.

The domain models deliberately allow incomplete optional metadata and nullable logical identities. Episode numbers remain `Double?` and can preserve fractional values. `SqliteDomainMappers.kt` is read-only and retains persisted primary/foreign IDs, raw JSON, manual metadata ownership, AniList IDs, playback state and availability. No schema migration, data deletion, scanner rewrite, AniList integration or player rewrite was performed in this step.

## Validation workflow

The existing `.github/workflows/kotlin_jvm_tests.yml` workflow is triggered only by `workflow_dispatch`; it is not automatically run by pull requests. It was not dispatched during Prompt 02.1, in accordance with the repository's execution restrictions. The PR workflow `build_apk.yml` ran the Python regression suite, validated the Python application, built the APK, and explicitly verified release Kotlin compilation; it did not run the Kotlin/JVM unit-test suite. Therefore, a successful APK build is not evidence that the JVM parser, URI/SAF, identity, or genre-normalization assertions passed. Those tests remain pending explicit authorization for the designated manual validation stage.

The manual Kotlin/JVM workflow prepares the Flet-rendered Android host and generates the canonical SQLite test fixture before executing `:app:testDebugUnitTest`; do not trigger it from this prompt.


## Scope of Unicode normalization parity

The Kotlin genre normalizer explicitly handles `ß`/`ss` and Greek final sigma `ς`/`σ` after NFKD, in addition to accent removal, separator normalization and whitespace collapse. This is not a full implementation of every Unicode mapping in Python `str.casefold()`. Existing alias/registry identity is preserved; broader Unicode cases have not been asserted by executed tests in this prompt.
