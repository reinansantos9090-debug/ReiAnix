# Genre Registry

The ReiAnix Genre Registry is the single local identity layer for genres. It does not replace the existing library, catalog, AniList client, tags, collections, or classifier.

## Sources

Genres may arrive from AniList metadata, the local GenreClassifier, existing local metadata, or explicit user-created genres. AniList is a provider; the registry persists the resulting local identity.

## Identity

Each genre has a stable UUID, canonical name, normalized name, source, and system/custom flags. Aliases resolve to the same UUID. Normalization is for identity matching only; it is not translation and does not use fuzzy matching.

## Persistence

The registry uses the existing library.sqlite3. It adds genres, genre_aliases, and anime_genres tables to the existing database. Existing anime.genres JSON is retained for backward compatibility and migrated idempotently into the registry.

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
