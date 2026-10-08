# AniList Matching 2.0

ReiAnix keeps local media and the local catalog authoritative. AniList is used only for metadata and entity identification.

## Matching layers

`library_parser.py` identifies the likely local anime title and episode context without network access. `organizer_ai.py` ranks the AniList candidates returned by the existing `AniListClient`.

The matcher uses canonical, romaji, native, English titles and synonyms. Matching text keeps Unicode scripts intact and removes release/technical noise only for comparison.

## Confidence

Automatic confirmation requires a deterministic score threshold plus a safety margin over the second candidate. Candidate scores also consider compatible format and, when explicitly represented by the candidate title, season context. Popularity and average score are not used as identity truth.

Ambiguous candidates remain pending for manual selection.

## Match state

Match state lives on the existing `anime` row and uses the existing SQLite database. States include `unmatched`, `matched`, `manual`, `ambiguous`, `not_found`, `network_error`, and `rate_limited`.

A manual selection has precedence over automatic matching. Unlinking clears the AniList ID and invalidates cached AniList metadata without deleting the local anime or its episodes.

## Offline behavior

A persisted AniList ID and metadata are reused offline. A missing network connection is never converted into `not_found`, and no matching path invokes Storage/SAF/MediaStore/scanning.

## Genre integration

When a match supplies genres, they continue through the existing GenreRegistry from the existing genre integration. No second genre store is introduced.
