# ReiAnix — metadata providers and TMDB artwork fallback

## Architecture and persistence

The provider contract lives in ReiAnixMetadataProviders.kt. ReiAnixMetadataProviderRegistry bounds concurrent provider calls, applies a per-provider timeout, keeps partial successes when a different provider fails, and propagates caller cancellation. Both the existing unauthenticated AniList GraphQL client and the official TMDB REST API are adapted to the same query/result contract. AniList has higher provider priority; TMDB remains the optional fallback. Provider results keep the provider ID, provider-specific external ID, the query title used for matching, confidence, and provenance.

The app keeps AniList metadata and its existing poster URL first. When that poster is absent or cannot be materialized, the common registry queries the enabled AniList and TMDB providers concurrently. AniList is preferred when confidence is equal; TMDB is used when it provides the best valid result. Provider results are associated with the existing artwork rows (source_ref=<provider>:<external-id>:<title-fingerprint>) and passed to the existing artwork materializer. The persisted anime.cover_url is not overwritten; after a successful download the established cover_cache pointer is updated only when the current title still validates the stored fingerprint and no manual artwork field owns that pointer.

No table, database, download pipeline, image cache, or local/anilist identifier was added. The canonical v29 SQLite schema remains unchanged. The TMDB external ID lives in the existing artwork source_ref; it is distinct from the local anime ID and AniList ID. The app continues to use its existing Coil rendering and cache/materialization path.

## TMDB requests

The TMDB adapter uses the official API v3 endpoints:

- GET https://api.themoviedb.org/3/search/tv for series/anime.
- GET https://api.themoviedb.org/3/search/movie for movies.
- Authentication is the official Authorization: Bearer <API Read Access Token> header, not a URL query parameter.
- Poster URLs are built as https://image.tmdb.org/t/p/w500/<poster_path>. Only poster_path is considered for cover selection; search-result backdrops are not promoted to posters.

Titles are normalized for accents, punctuation, and whitespace. Results must match a supplied title or original title exactly after normalization. When the library and TMDB both have a year, differences over one year are rejected. The request tries the canonical, English, romanized, native, and stored alias titles, with a bounded number of variants. This deliberately favors avoiding homonyms/sequel mismatches over fuzzy false positives.

Requests have bounded connect/read timeouts and response size. HTTP 429 and 5xx receive at most two short retries and the Retry-After value is capped. Authentication, HTTP, transport, invalid JSON, empty result, timeout, and provider failure are kept distinct without copying tokens or response bodies into diagnostics. An absent token skips TMDB cleanly; the existing AniList/local artwork path is not disabled by missing TMDB credentials.

## Configure credentials

Provide one of these at build time:

- environment variable REIANIX_TMDB_READ_ACCESS_TOKEN, or
- Gradle property reianixTmdbReadAccessToken.

Do not commit the token in gradle.properties, source code, CI logs, issue reports, or documentation. No backend or server-side secret store has been introduced. A token embedded into an Android APK can be extracted from the distributed package; build-time injection prevents accidental source-control exposure but does not make a client-side token secret. Restrict/rotate credentials as appropriate. Commercial distribution or a credential that must remain confidential requires a separately approved architecture and the appropriate service/license agreement.

The provider is enabled by default through metadata.tmdb_enabled; the Settings screen allows it to be disabled. The existing metadata.anilist_enabled preference also controls whether the AniList adapter searches. When the BuildConfig token is empty, TMDB is listed as skipped and the existing no-credential AniList provider remains usable.

## Attribution, copyright, and release obligations

Official references:

- Authentication: https://developer.themoviedb.org/docs/authentication-application
- TV search: https://developer.themoviedb.org/reference/search-tv
- Movie search: https://developer.themoviedb.org/docs/search-and-query-for-details
- Image URL construction: https://developer.themoviedb.org/docs/image-basics
- Rate limiting: https://developer.themoviedb.org/docs/rate-limiting
- FAQ and API attribution: https://developer.themoviedb.org/docs/faq
- Approved logos and attribution: https://www.themoviedb.org/about/logos-attribution

The TMDB FAQ states that its free API is for non-commercial use with attribution. It requires this prominent notice in an About/Credits section:

> This product uses the TMDB API but is not endorsed or certified by TMDB.

The About screen includes that exact notice. The FAQ also requires an approved TMDB logo in the application; the approved logo asset has not yet been bundled in this change and must be added before a release that uses TMDB data. Do not alter an official logo's colors, aspect ratio, or orientation. Commercial use requires contacting TMDB for a separate license. TMDB's API legal notice says it does not claim ownership of all data/images returned by its API; downstream image rights and applicable copyright obligations still need to be respected.

This documentation and runtime integration do not constitute a legal clearance. Confirm that the intended distribution is non-commercial or obtain the appropriate license, add the approved official logo, and review attribution in the final packaged UI before release.

## Verification limits

The tests in ReiAnixMetadataProvidersTest.kt cover title normalization, URL safety, homonym/year rejection, missing credentials, official TV search response parsing, Bearer header handling, token non-disclosure, invalid JSON, empty results, and provider-failure isolation. Per repository AGENTS.md, Kotlin/JVM tests are reserved for the user's designated manual validation stage and were not run by this change. An authenticated live TMDB request was not performed because no token was supplied. CI/workflow and Android build outcomes must be recorded separately from static review.
