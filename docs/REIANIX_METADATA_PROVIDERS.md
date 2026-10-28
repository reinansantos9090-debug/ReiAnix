# ReiAnix — metadata providers and TMDB artwork fallback

## Architecture and persistence

The provider contract lives in ReiAnixMetadataProviders.kt. ReiAnixMetadataProviderRegistry bounds concurrent provider calls, applies a per-provider timeout, keeps partial successes when a different provider fails, and propagates caller cancellation. The existing unauthenticated AniList GraphQL client and the TMDB metadata-backend adapter use the same query/result contract. AniList has higher provider priority; TMDB remains the optional fallback when a backend is configured. Provider results keep the provider ID, provider-specific external ID, the query title used for matching, confidence, and provenance.

The app keeps AniList metadata and its existing poster URL first. When that poster is absent or cannot be materialized, the common registry queries the enabled AniList and TMDB providers concurrently. AniList is preferred when confidence is equal; TMDB is used when it provides the best valid result. Provider results are associated with the existing artwork rows (source_ref=<provider>:<external-id>:<title-fingerprint>) and passed to the existing artwork materializer. The persisted anime.cover_url is not overwritten; after a successful download the established cover_cache pointer is updated only when the current title still validates the stored fingerprint and no manual artwork field owns that pointer.

No table, database, download pipeline, image cache, or local/anilist identifier was added. The canonical v29 SQLite schema remains unchanged. The TMDB external ID lives in the existing artwork source_ref; it is distinct from the local anime ID and AniList ID. The app continues to use its existing Coil rendering and cache/materialization path.

## TMDB requests and secret boundary

The Android app does **not** call the TMDB API directly and does not contain a TMDB read token. It makes a POST request to the configured backend origin:

- `POST /v1/metadata/tmdb/search`
- Request fields: `query`, `mediaKind` (`tv` or `movie`), optional `year`, and `language`.
- Response: a restricted `results` array containing only the IDs, titles, date, adult flag and `poster_path` fields required to resolve a candidate.
- The Android client does not send an Authorization header. The server makes the official TMDB request and uses its server-only `TMDB_READ_ACCESS_TOKEN` secret in the Authorization Bearer header.
- Poster URLs still use `https://image.tmdb.org/t/p/w500/<poster_path>`. Only a valid, single-slash `poster_path` is eligible; backdrops are not used as posters.

The Android provider continues to normalize titles and reject homonyms/season mismatches using the existing year/title policy. AniList retains higher priority. The metadata registry, artwork service, canonical SQLite v29 rows, artwork materializer and existing cache remain the same. No parallel database or artwork pipeline is introduced.

## Backend deployment and configuration

Source code for the backend is in `backend/tmdb-metadata-worker/`. It is a Cloudflare Worker implementation with one allowlisted search operation, input validation, response projection, per-IP edge rate limiting and no logging of credentials/upstream response bodies. The source includes unit tests for the request/response contract and error handling.

**The Worker has not been deployed by this repository change. No backend URL is set or claimed to exist.** The app therefore treats the provider as unconfigured when the public backend URL is empty and skips TMDB cleanly while AniList remains available.

To deploy it, the owner needs a Cloudflare account and control of the account where the service will run. From `backend/tmdb-metadata-worker/`:

1. Run `npm test`.
2. Authenticate Wrangler to the owner’s Cloudflare account.
3. Set the token as an encrypted server secret with `npx wrangler secret put TMDB_READ_ACCESS_TOKEN`; enter it only in the terminal prompt. Do not put it in the Android app, Gradle properties, repository files, GitHub Actions variables, or a build step.
4. Deploy using `npx wrangler deploy`, then verify `GET /healthz` and a live `POST /v1/metadata/tmdb/search` with valid JSON and usable poster paths.
5. Only after these live checks pass, configure the repository Actions **variable** `REIANIX_TMDB_METADATA_BACKEND_URL` to the real HTTPS origin supplied by the deployment. This public origin is not a credential and is the only TMDB-related value embedded in BuildConfig.

IP rate limiting and a restricted endpoint help prevent abuse but are not strong proof that a request came from the official app. Before production distribution, add a suitable server-verified app-attestation/authorization design and edge quotas. Do not embed a shared client key in the APK; it would be extractable too.

The previous APK 7433 included a credential-shaped JWT value in its compiled DEX, and the two Actions artifacts from run 7433 contain that APK. Do not redistribute or install those artifacts; remove them manually from the run page because this connector cannot delete workflow artifacts. The owner has explicitly chosen to retain the current token. That choice is respected here, but the value must be treated as already disclosed: storing it as a Cloudflare Worker secret prevents new APKs from including it, but does not invalidate copies that may have been extracted from APK 7433. The modified build workflow does not consume the GitHub Repository Secret. This repository change does not alter any credential or delete historical Actions artifacts.

## Attribution, copyright, and release obligations

Official references:

- Authentication: https://developer.themoviedb.org/docs/authentication-application
- TV search: https://developer.themoviedb.org/reference/search-tv
- Movie search: https://developer.themoviedb.org/docs/search-and-query-for-details
- Image URL construction: https://developer.themoviedb.org/docs/image-basics
- Rate limiting: https://developer.themoviedb.org/docs/rate-limiting
- FAQ and API attribution: https://developer.themoviedb.org/docs/faq
- Approved logos and attribution: https://www.themoviedb.org/about/logos-attribution

The current API Terms of Use require a TMDB logo and prominent attribution in the application, prohibit implying endorsement, and prohibit retaining any TMDB API data/content for longer than six months. The About screen displays the required FAQ wording:

> This product uses the TMDB API but is not endorsed or certified by TMDB.

It also displays the broader wording from the current API Terms:

> This product uses TMDB and the TMDB APIs but is not endorsed, certified, or otherwise approved by TMDB.

The approved short blue TMDB logo is bundled unmodified as android/app/src/main/assets/tmdb-logo.svg; its source and branding restrictions are recorded in android/app/src/main/assets/tmdb-logo.NOTICE. The existing artwork maintenance/backfill pass expires provider-owned tmdb: rows older than 180 days, clears derived cover-cache pointers when they are not user-owned, removes unreferenced managed files, then requests fresh artwork for affected anime through the existing pipeline. This does not change SQLite v29 or create a second cache. Manual/local artwork references are preserved.

The official API Terms expressly prohibit commercial use unless covered by a separate written agreement with TMDB. No such license has been provided or verified in this task, so no commercial authorization is claimed. TMDB's logo is less prominent than the ReiAnix app name and is not modified. The app continues to use the existing artwork storage/loading path; retained provider content is subject to the 180-day expiry policy. Cleanup runs whenever the existing artwork maintenance pass executes; no Android background-work infrastructure was added.

This documentation and runtime integration do not constitute legal clearance. The intended distribution/use category still needs to be confirmed. Any commercial use requires the applicable written license from TMDB. Do not use TMDB content for AI/ML uses prohibited by the current API Terms.

## Verification limits

The tests in ReiAnixMetadataProvidersTest.kt cover title normalization, URL safety, homonym/year rejection, missing backend configuration, request/response parsing, absence of client Authorization headers, invalid JSON, empty results, and provider-failure isolation. ReiAnixBuildSecretSafetyTest rejects the old BuildConfig token field and JWT-shaped string constants, and the APK workflow scans final APK entries for the legacy field name and JWT-shaped strings. Backend unit tests use mocked upstream responses and are not evidence of a live deployment. Live backend validation remains blocked until the owner deploys and configures a real HTTPS origin. CI/workflow results, mocked tests, live backend validation, and device testing must be reported separately.
