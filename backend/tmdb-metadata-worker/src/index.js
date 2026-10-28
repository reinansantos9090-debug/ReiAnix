const TMDB_API_BASE = "https://api.themoviedb.org/3";
const SEARCH_ROUTE = "/v1/metadata/tmdb/search";
const MAX_REQUEST_BYTES = 4096;
const MAX_QUERY_LENGTH = 160;
const MAX_RESULTS = 20;
const MAX_RESPONSE_BYTES = 2 * 1024 * 1024;

function jsonResponse(status, payload) {
  return new Response(JSON.stringify(payload), {
    status,
    headers: {
      "content-type": "application/json; charset=utf-8",
      "cache-control": "no-store",
      "x-content-type-options": "nosniff",
    },
  });
}

function safePosterPath(value) {
  if (typeof value !== "string" || !value.startsWith("/") || value.startsWith("//")) return null;
  if (/[\s?#\u0000-\u001f]/.test(value) || !/^\/[A-Za-z0-9._/-]+$/.test(value)) return null;
  if (value.split("/").some((part) => part === "." || part === "..")) return null;
  return value;
}

function projectResult(item, mediaKind) {
  if (!item || typeof item !== "object" || !Number.isSafeInteger(item.id) || item.id <= 0) return null;
  if (item.adult === true) return null;

  const posterPath = safePosterPath(item.poster_path);
  if (!posterPath) return null;

  if (mediaKind === "tv") {
    if (typeof item.name !== "string" || typeof item.original_name !== "string") return null;
    return {
      id: item.id,
      adult: false,
      name: item.name.slice(0, MAX_QUERY_LENGTH),
      original_name: item.original_name.slice(0, MAX_QUERY_LENGTH),
      first_air_date: typeof item.first_air_date === "string" ? item.first_air_date.slice(0, 10) : "",
      poster_path: posterPath,
    };
  }

  if (typeof item.title !== "string" || typeof item.original_title !== "string") return null;
  return {
    id: item.id,
    adult: false,
    title: item.title.slice(0, MAX_QUERY_LENGTH),
    original_title: item.original_title.slice(0, MAX_QUERY_LENGTH),
    release_date: typeof item.release_date === "string" ? item.release_date.slice(0, 10) : "",
    poster_path: posterPath,
  };
}

async function readStreamTextLimited(stream, maxBytes) {
  if (!stream) return "";
  const reader = stream.getReader();
  const chunks = [];
  let size = 0;
  try {
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      size += value.byteLength;
      if (size > maxBytes) {
        try { await reader.cancel(); } catch {}
        return null;
      }
      chunks.push(value);
    }
  } finally {
    reader.releaseLock();
  }

  const bytes = new Uint8Array(size);
  let offset = 0;
  for (const chunk of chunks) {
    bytes.set(chunk, offset);
    offset += chunk.byteLength;
  }
  return new TextDecoder().decode(bytes);
}

async function readJsonBody(request) {
  const contentLength = request.headers.get("content-length");
  if (contentLength !== null) {
    const declaredLength = Number(contentLength);
    if (!Number.isSafeInteger(declaredLength) || declaredLength < 0 || declaredLength > MAX_REQUEST_BYTES) {
      return null;
    }
  }
  let text;
  try {
    text = await readStreamTextLimited(request.body, MAX_REQUEST_BYTES);
  } catch {
    return null;
  }
  if (text === null) return null;
  try {
    return JSON.parse(text);
  } catch {
    return null;
  }
}

export async function handleRequest(request, env, fetchImpl = globalThis.fetch) {
  const url = new URL(request.url);

  if (url.pathname === "/healthz") {
    if (request.method !== "GET") return jsonResponse(405, { error: "method_not_allowed" });
    if (
      !String(env?.TMDB_READ_ACCESS_TOKEN || "").trim() ||
      typeof env?.TMDB_RATE_LIMITER?.limit !== "function"
    ) {
      return jsonResponse(503, { error: "service_not_configured" });
    }
    return jsonResponse(200, { status: "ok" });
  }

  if (url.pathname !== SEARCH_ROUTE) return jsonResponse(404, { error: "not_found" });
  if (request.method !== "POST") return jsonResponse(405, { error: "method_not_allowed" });

  const token = String(env?.TMDB_READ_ACCESS_TOKEN || "").trim();
  if (!token || !env?.TMDB_RATE_LIMITER || typeof env.TMDB_RATE_LIMITER.limit !== "function") {
    return jsonResponse(503, { error: "service_not_configured" });
  }

  // Cloudflare supplies this header at the edge. Fail closed rather than trusting caller-
  // supplied X-Forwarded-For values that could be forged to bypass per-IP limits.
  const clientIp = request.headers.get("CF-Connecting-IP");
  if (!clientIp) return jsonResponse(400, { error: "client_address_required" });

  let rateLimit;
  try {
    rateLimit = await env.TMDB_RATE_LIMITER.limit({ key: clientIp });
  } catch {
    return jsonResponse(503, { error: "rate_limit_unavailable" });
  }
  if (!rateLimit?.success) return jsonResponse(429, { error: "rate_limited" });

  const input = await readJsonBody(request);
  if (!input || typeof input !== "object" || Array.isArray(input)) {
    return jsonResponse(400, { error: "invalid_json" });
  }
  const query = typeof input.query === "string" ? input.query.trim() : "";
  const mediaKind = input.mediaKind;
  const language = input.language === undefined ? "pt-BR" : input.language;
  const year = input.year;

  if (!query || query.length > MAX_QUERY_LENGTH) return jsonResponse(400, { error: "invalid_query" });
  if (mediaKind !== "tv" && mediaKind !== "movie") return jsonResponse(400, { error: "invalid_media_kind" });
  if (typeof language !== "string" || !/^(?:[a-z]{2}-[A-Z]{2}|[a-z]{2})$/.test(language)) {
    return jsonResponse(400, { error: "invalid_language" });
  }
  if (year !== undefined && (!Number.isInteger(year) || year < 1900 || year > 2100)) {
    return jsonResponse(400, { error: "invalid_year" });
  }

  const upstreamUrl = new URL(`${TMDB_API_BASE}/search/${mediaKind}`);
  upstreamUrl.searchParams.set("query", query);
  upstreamUrl.searchParams.set("include_adult", "false");
  upstreamUrl.searchParams.set("language", language);
  upstreamUrl.searchParams.set("page", "1");
  if (year !== undefined) {
    upstreamUrl.searchParams.set(mediaKind === "movie" ? "year" : "first_air_date_year", String(year));
  }

  let upstream;
  try {
    upstream = await fetchImpl(upstreamUrl.toString(), {
      method: "GET",
      redirect: "error",
      headers: {
        accept: "application/json",
        authorization: `Bearer ${token}`,
      },
      signal: AbortSignal.timeout(12_000),
    });
  } catch {
    return jsonResponse(502, { error: "metadata_upstream_unavailable" });
  }

  if (!upstream.ok) {
    return jsonResponse(upstream.status === 429 ? 429 : 502, {
      error: upstream.status === 429 ? "metadata_upstream_rate_limited" : "metadata_upstream_failure",
    });
  }

  let raw;
  try {
    raw = await readStreamTextLimited(upstream.body, MAX_RESPONSE_BYTES);
  } catch {
    return jsonResponse(502, { error: "metadata_upstream_invalid_response" });
  }
  if (raw === null) return jsonResponse(502, { error: "metadata_response_too_large" });

  let payload;
  try {
    payload = JSON.parse(raw);
  } catch {
    return jsonResponse(502, { error: "metadata_upstream_invalid_response" });
  }

  if (!payload || !Array.isArray(payload.results)) {
    return jsonResponse(502, { error: "metadata_upstream_invalid_response" });
  }

  const results = payload.results
    .slice(0, MAX_RESULTS)
    .map((item) => projectResult(item, mediaKind))
    .filter(Boolean);

  return jsonResponse(200, { results });
}

export default {
  fetch(request, env) {
    return handleRequest(request, env);
  },
};
