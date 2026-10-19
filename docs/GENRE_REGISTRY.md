# Genre Registry

The ReiAnix Genre Registry is the single local identity layer for genres. It does not replace the existing library, catalog, AniList client, tags, collections, or classifier.

## Sources

Genres may arrive from AniList metadata, the local GenreClassifier, existing local metadata, or explicit user-created genres. AniList is a provider; the registry persists the resulting local identity.

## Identity

Each genre has a stable UUID, canonical name, normalized name, source, and system/custom flags. Aliases resolve to the same UUID. Normalization is for identity matching only; it is not translation and does not use fuzzy matching. Kotlin normalization explicitly mirrors the Python case-fold results for `ß` and Greek final sigma (`ς`) after Unicode decomposition, but it is not a complete implementation of all Unicode mappings in Python `str.casefold()`.

## Persistence

Both legacy and native code paths use the existing `library.sqlite3` schema v29. The native `ReiAnixGenreRegistry` uses `ReiAnixSqliteDatabase`, does not create a second database or alter the schema, and exposes an explicit `initialize()` for idempotent backfill from legacy JSON. The Python registry remains necessary while Python service/runtime consumers still depend on it; its implementation is not removed by Prompt 02.

## Associations

An anime can have multiple genres. Associations use stable genre IDs and are counted with COUNT(DISTINCT anime_id), so episodes and duplicate physical files do not inflate genre counts.

## Offline behavior

The registry is entirely local. Once a genre is persisted, Organize, Details, search and filters can use it without contacting AniList. A failed AniList request does not clear persisted genres or associations.

## Boundaries

- Genre is not Tag.
- Genre is not Collection.
- Genre is not Organize Category.
- GenreClassifier infers local genres; GenreRegistry owns genre identity.
- The existing AniList client remains the only AniList client.


## Kotlin migration boundary

`ReiAnixGenreRegistry.kt` and `ReiAnixGenreClassifier.kt` are the existing native registry/classifier; Prompt 02 reuses them instead of creating competitors. Python `core/genre_registry.py` and `core/genre_classifier.py` remain during the hybrid transition because `core/library_service.py` and related consumers still use that path. Do not remove or disable the legacy path until consumers have switched, parity is validated, and the applicable later prompt authorizes the cutover.


## Prompt 02 validation evidence

`ReiAnixGenreClassifierTest.kt`, an existing Kotlin/JVM test file, contains selected regression examples for `Straße → strasse`, Greek final sigma `ς → σ`, accent removal and separator normalization as of the current PR. Those assertions have not been confirmed compiled or executed as a JVM suite; the successful APK workflow validates production compilation, not the test assertions. The manual `kotlin_jvm_tests.yml` workflow remains undispatched pending explicit authorization. This selected coverage does not imply full Unicode `str.casefold()` parity.
