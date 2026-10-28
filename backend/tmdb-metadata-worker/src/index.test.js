import assert from "node:assert/strict";
import test from "node:test";
import { handleRequest } from "./index.js";

const SERVER_ONLY_TOKEN = "test-server-side-token-never-return";
const SEARCH_URL = "https://worker.example.invalid/v1/metadata/tmdb/search";

function makeEnv(overrides = {}) {
  return {
    TMDB_READ_ACCESS_TOKEN: SERVER_ONLY_TOKEN,
    TMDB_RATE_LIMITER: {
      limit: async () => ({ success: true }),
    },
    ...overrides,
  };
}

function request(payload, { method = "POST", headers = {} } = {}) {
  return new Request(SEARCH_URL, {
    method,
    headers: {
      "content-type": "application/json",
      "cf-connecting-ip": "203.0.113.10",
      ...headers,
    },
    body: method === "GET" || method === "HEAD" ? undefined : JSON.stringify(payload),
  });
}

test("health endpoint reports readiness without returning the server secret", async () => {
  const response = await handleRequest(
    new Request("https://worker.example.invalid/healthz"),
    makeEnv(),
    async () => { throw new Error("upstream should not be called"); },
  );
  assert.equal(response.status, 200);
  const body = await response.text();
  assert.deepEqual(JSON.parse(body), { status: "ok" });
  assert.equal(body.includes(SERVER_ONLY_TOKEN), false);
});

test("TV search authenticates to TMDB server-side and projects only poster metadata", async () => {
  let outbound;
  const response = await handleRequest(
    request({ query: "Cowboy Bebop", mediaKind: "tv", language: "pt-BR", year: 1998, includeAdult: false }),
    makeEnv(),
    async (url, options) => {
      outbound = { url: new URL(url), options };
      return new Response(JSON.stringify({
        results: [
          {
            id: 123,
            adult: false,
            name: "Cowboy Bebop",
            original_name: "Cowboy Bebop",
            first_air_date: "1998-10-24",
            poster_path: "/cowboy.jpg",
            backdrop_path: "/not-returned.jpg",
            overview: "not needed by the client",
          },
          {
            id: 456,
            adult: true,
            name: "Adult result",
            original_name: "Adult result",
            first_air_date: "2000-01-01",
            poster_path: "/adult.jpg",
          },
          {
            id: 457,
            name: "Missing adult classification",
            original_name: "Missing adult classification",
            first_air_date: "2000-01-01",
            poster_path: "/unknown-adult.jpg",
          },
          {
            id: 458,
            adult: false,
            name: "   ",
            original_name: "Empty title",
            first_air_date: "2000-01-01",
            poster_path: "/empty-title.jpg",
          },
          {
            id: 789,
            adult: false,
            name: "Missing poster",
            original_name: "Missing poster",
            first_air_date: "2000-01-01",
            poster_path: "//evil.example/poster.jpg",
          },
        ],
      }), { status: 200, headers: { "content-type": "application/json" } });
    },
  );

  assert.equal(response.status, 200);
  assert.equal(outbound.url.hostname, "api.themoviedb.org");
  assert.equal(outbound.url.pathname, "/3/search/tv");
  assert.equal(outbound.url.searchParams.get("first_air_date_year"), "1998");
  assert.equal(outbound.options.headers.authorization, `Bearer ${SERVER_ONLY_TOKEN}`);
  assert.equal(outbound.options.headers.authorization.includes(SERVER_ONLY_TOKEN), true);

  const bodyText = await response.text();
  assert.equal(bodyText.includes(SERVER_ONLY_TOKEN), false);
  const body = JSON.parse(bodyText);
  assert.equal(body.results.length, 1);
  assert.deepEqual(body.results[0], {
    id: 123,
    adult: false,
    name: "Cowboy Bebop",
    original_name: "Cowboy Bebop",
    first_air_date: "1998-10-24",
    poster_path: "/cowboy.jpg",
  });
});

test("movie searches use the movie year parameter and return movie-only fields", async () => {
  let outbound;
  const response = await handleRequest(
    request({ query: "Ghost in the Shell", mediaKind: "movie", language: "en-US", year: 1995 }),
    makeEnv(),
    async (url) => {
      outbound = new URL(url);
      return new Response(JSON.stringify({
        results: [{
          id: 11,
          adult: false,
          title: "Ghost in the Shell",
          original_title: "攻殻機動隊",
          release_date: "1995-11-18",
          poster_path: "/ghost.jpg",
        }],
      }), { status: 200 });
    },
  );
  assert.equal(response.status, 200);
  assert.equal(outbound.searchParams.get("year"), "1995");
  assert.equal(outbound.searchParams.has("first_air_date_year"), false);
  assert.equal(JSON.parse(await response.text()).results[0].title, "Ghost in the Shell");
});

test("invalid method, media kind, year, and oversized query are rejected before TMDB", async () => {
  let calls = 0;
  const fetchImpl = async () => { calls += 1; return new Response("{}", { status: 200 }); };
  assert.equal((await handleRequest(request({}, { method: "GET" }), makeEnv(), fetchImpl)).status, 405);
  assert.equal((await handleRequest(request({ query: "x", mediaKind: "person" }), makeEnv(), fetchImpl)).status, 400);
  assert.equal((await handleRequest(request({ query: "x".repeat(161), mediaKind: "tv" }), makeEnv(), fetchImpl)).status, 400);
  assert.equal((await handleRequest(request({ query: "x", mediaKind: "tv", year: 2200 }), makeEnv(), fetchImpl)).status, 400);
  assert.equal(calls, 0);
});

test("rate limit blocks the request before calling TMDB", async () => {
  let calls = 0;
  const env = makeEnv({ TMDB_RATE_LIMITER: { limit: async () => ({ success: false }) } });
  const response = await handleRequest(request({ query: "Cowboy Bebop", mediaKind: "tv" }), env, async () => {
    calls += 1;
    return new Response("{}", { status: 200 });
  });
  assert.equal(response.status, 429);
  assert.equal(calls, 0);
});

test("missing server credential fails closed and does not call TMDB", async () => {
  let calls = 0;
  const response = await handleRequest(
    request({ query: "Cowboy Bebop", mediaKind: "tv" }),
    makeEnv({ TMDB_READ_ACCESS_TOKEN: "" }),
    async () => { calls += 1; return new Response("{}", { status: 200 }); },
  );
  assert.equal(response.status, 503);
  assert.equal(calls, 0);
  assert.equal((await response.text()).includes(SERVER_ONLY_TOKEN), false);
});

test("TMDB failures do not expose server credentials or upstream bodies", async () => {
  const response = await handleRequest(
    request({ query: "Cowboy Bebop", mediaKind: "tv" }),
    makeEnv(),
    async () => new Response(SERVER_ONLY_TOKEN, { status: 401 }),
  );
  assert.equal(response.status, 502);
  const body = await response.text();
  assert.equal(body.includes(SERVER_ONLY_TOKEN), false);
  assert.equal(body.includes("Authorization"), false);
});


test("readiness fails closed when the rate-limit binding is malformed", async () => {
  const response = await handleRequest(
    new Request("https://worker.example.invalid/healthz"),
    makeEnv({ TMDB_RATE_LIMITER: {} }),
    async () => { throw new Error("upstream should not be called"); },
  );
  assert.equal(response.status, 503);
  assert.deepEqual(JSON.parse(await response.text()), { error: "service_not_configured" });
});

test("oversized request bodies are rejected before calling TMDB", async () => {
  let calls = 0;
  const oversizedRequest = new Request(SEARCH_URL, {
    method: "POST",
    headers: {
      "content-type": "application/json",
      "cf-connecting-ip": "203.0.113.10",
    },
    body: JSON.stringify({
      query: "Cowboy Bebop",
      mediaKind: "tv",
      padding: "x".repeat(5000),
    }),
  });
  const response = await handleRequest(oversizedRequest, makeEnv(), async () => {
    calls += 1;
    return new Response("{}", { status: 200 });
  });
  assert.equal(response.status, 400);
  assert.equal(calls, 0);
});

test("oversized upstream responses are rejected without returning their body", async () => {
  const response = await handleRequest(
    request({ query: "Cowboy Bebop", mediaKind: "tv" }),
    makeEnv(),
    async () => new Response("x".repeat(2 * 1024 * 1024 + 1), { status: 200 }),
  );
  assert.equal(response.status, 502);
  const body = await response.text();
  assert.deepEqual(JSON.parse(body), { error: "metadata_response_too_large" });
  assert.equal(body.includes(SERVER_ONLY_TOKEN), false);
});
