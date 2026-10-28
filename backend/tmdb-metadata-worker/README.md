# ReiAnix TMDB metadata backend

This is a deployable Cloudflare Worker implementation for the single metadata operation used by the Android TMDB fallback. **It is source code only; no Worker has been deployed and no public backend URL is configured by this repository change.** Do not treat TMDB fallback as live until an owner deploys and verifies this service.

## API contract

- `GET /healthz` returns `{"status":"ok"}` only when the Worker has its server-side TMDB secret and rate-limit binding.
- `POST /v1/metadata/tmdb/search` accepts JSON with `query`, `mediaKind` (`tv` or `movie`), optional `year`, and optional `language`.
- The Worker calls only the fixed TMDB search endpoint for the allowed media kind. The TMDB read token is sent only from Worker to TMDB in an Authorization header.
- The response projects only IDs, title/original title, date, adult flag and poster path needed by the existing Android metadata provider. It never relays the token or the full upstream response.
- Inputs have strict length/type validation. Missing secret/rate-limit binding fails closed. The configured rate limiter uses the request IP from Cloudflare's `CF-Connecting-IP` header; the Worker does not trust caller-supplied `X-Forwarded-For`.

IP rate limiting and an allowlisted API operation reduce abuse but are not strong user authentication or proof that a request came from the official app. Before production distribution, configure Cloudflare rules/quotas and add a stronger client attestation/authorization policy appropriate to the app (for example, a server-verified Play Integrity flow). Never embed a shared API key in the Android app; a client-side key can also be extracted.

## Deploy (requires the owner's Cloudflare account)

1. Install a supported Node.js runtime and Wrangler CLI, then authenticate Wrangler to the correct Cloudflare account.
2. From this directory, run `npm test`.
3. Deploy the Worker code first. It fails closed with HTTP 503 while the server-side token is missing:

   ```sh
   npx wrangler deploy
   ```

4. Add the NEW TMDB read token as an encrypted Worker secret. Wrangler prompts for the value and deploys a new Worker version:

   ```sh
   npx wrangler secret put TMDB_READ_ACCESS_TOKEN
   ```

   Paste the credential only into the Wrangler terminal prompt. Do not put it in this README, GitHub issues, Android project properties, GitHub Actions secrets/variables, Gradle properties, or application source. Never reuse the token that was embedded in APK 7433.

5. Check `GET https://<your-deployed-worker-host>/healthz`, then send a real `POST /v1/metadata/tmdb/search` request for a known title. Confirm HTTP 200, valid JSON and at least one result with a usable `poster_path`. The repository Actions workflow repeats these live checks and refuses to build an APK when the backend URL is missing or validation fails. Do not claim the integration is live until these checks pass.

6. Configure the repository **Actions variable** `REIANIX_TMDB_METADATA_BACKEND_URL` to the HTTPS origin of the deployed Worker (for example, a hostname supplied by Cloudflare). This is a public endpoint address, not a secret. No backend URL has been guessed or set in this repository.

## Local tests and limits

Run `npm test` for simulated upstream responses and request validation. Those tests are unit tests with a mocked TMDB fetch; they do **not** validate a live TMDB credential or a deployed backend. The deployment/live integration remains BLOCKED until an owner has the required Cloudflare account/domain, configures the encrypted secret, deploys the Worker, and supplies/verifies its real origin.
