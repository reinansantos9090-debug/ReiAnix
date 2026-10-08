import json
import tempfile
import threading
import time
import unittest
from pathlib import Path
from unittest.mock import patch
from urllib.error import HTTPError, URLError

from core.anilist import AniListClient


def media_payload(anilist_id=1):
    return {
        "id": anilist_id,
        "title": {"english": "Attack on Titan", "romaji": "Shingeki no Kyojin", "native": "進撃の巨人"},
        "synonyms": ["AOT"],
        "description": "Synopsis",
        "coverImage": {"extraLarge": "https://img.example/cover.jpg", "large": "https://img.example/cover-small.jpg"},
        "bannerImage": "https://img.example/banner.jpg",
        "genres": ["Action", "Drama"],
        "seasonYear": 2013,
        "season": "SPRING",
        "status": "FINISHED",
        "episodes": 25,
        "duration": 24,
        "averageScore": 91,
        "studios": {"nodes": [{"name": "WIT Studio"}]},
    }


class AniListClientTests(unittest.TestCase):
    def test_request_returns_graphql_data(self):
        client = AniListClient("/tmp/cache")
        response = {"data": {"Media": {"id": 123}}}
        fake = type("Response", (), {
            "__enter__": lambda self: self,
            "__exit__": lambda self, *args: None,
            "read": lambda self: json.dumps(response).encode(),
        })()
        with patch("core.anilist.urllib.request.urlopen", return_value=fake):
            self.assertEqual(client._request("query", {"id": 123}), response["data"])

    def test_request_timeout_fails_safe_without_retry_loop(self):
        client = AniListClient("/tmp/cache")
        with patch("core.anilist.urllib.request.urlopen", side_effect=TimeoutError("timeout")) as request:
            self.assertIsNone(client._request("query", {}))
            self.assertEqual(client.last_request_status, "network_error")
        request.assert_called_once()

    def test_request_fails_safe_for_network_error(self):
        client = AniListClient("/tmp/cache")
        with patch("core.anilist.urllib.request.urlopen", side_effect=URLError("offline")):
            self.assertIsNone(client._request("query", {}))

    def test_request_retries_once_after_rate_limit(self):
        client = AniListClient("/tmp/cache")
        response = {"data": {"Media": {"id": 123}}}
        fake = type("Response", (), {
            "__enter__": lambda self: self,
            "__exit__": lambda self, *args: None,
            "read": lambda self: json.dumps(response).encode(),
        })()
        rate_limited = HTTPError(client.endpoint, 429, "rate", {"Retry-After": "1"}, None)
        with patch("core.anilist.urllib.request.urlopen", side_effect=[rate_limited, fake]) as request, \
             patch("core.anilist.time.sleep") as sleep:
            self.assertEqual(client._request("query", {}), response["data"])
        sleep.assert_called_once_with(1.0)
        self.assertEqual(request.call_count, 2)

    def test_request_rate_limit_without_retry_after_fails_safely(self):
        client = AniListClient("/tmp/cache")
        rate_limited = HTTPError(client.endpoint, 429, "rate", {}, None)
        with patch("core.anilist.urllib.request.urlopen", side_effect=rate_limited) as request:
            self.assertIsNone(client._request("query", {}))
        request.assert_called_once()

    def test_request_fails_safe_for_graphql_error_and_malformed_json(self):
        client = AniListClient("/tmp/cache")
        graphql_error = {"errors": [{"message": "rate limited"}], "data": None}
        fake = type("Response", (), {
            "__enter__": lambda self: self,
            "__exit__": lambda self, *args: None,
            "read": lambda self: json.dumps(graphql_error).encode(),
        })()
        with patch("core.anilist.urllib.request.urlopen", return_value=fake):
            self.assertIsNone(client._request("query", {}))
        malformed = type("Response", (), {
            "__enter__": lambda self: self,
            "__exit__": lambda self, *args: None,
            "read": lambda self: b"not-json",
        })()
        with patch("core.anilist.urllib.request.urlopen", return_value=malformed):
            self.assertIsNone(client._request("query", {}))

    def test_localized_metadata_preserves_original_description(self):
        client = AniListClient("/tmp/cache")
        media = {
            "id": 99,
            "title": {"english": "Example", "romaji": "Example", "native": "例"},
            "description": "The original description stays intact.",
        }
        from unittest.mock import patch
        with patch.object(client, "localize_description_to_pt_br", return_value="A descrição traduzida."):
            metadata = client.metadata_from_media("Example", media, localize_description=True)
        self.assertEqual(metadata["description"], "A descrição traduzida.")
        self.assertEqual(metadata["description_original"], "The original description stays intact.")

    def test_foreign_metadata_defers_description_until_pt_br_translation_exists(self):
        client = AniListClient("/tmp/cache")
        media = {
            "id": 100,
            "title": {"english": "Example", "romaji": "Example", "native": "例"},
            "description": "The story follows a young hero who protects their people.",
        }
        metadata = client.metadata_from_media("Example", media, localize_description=False)
        self.assertIsNone(metadata["description"])
        self.assertEqual(
            metadata["description_original"],
            "The story follows a young hero who protects their people.",
        )

    def test_metadata_maps_anilist_fields(self):
        with tempfile.TemporaryDirectory() as directory:
            client = AniListClient(directory)
            with patch.object(client, "cache_cover", return_value=str(Path(directory) / "cover.jpg")):
                metadata = client.metadata_from_media("Local title", media_payload(99))
            self.assertEqual(metadata["anilist_id"], 99)
            self.assertEqual(metadata["title"], "Attack on Titan")
            self.assertEqual(metadata["romaji"], "Shingeki no Kyojin")
            self.assertEqual(json.loads(metadata["aliases"]), ["AOT"])
            self.assertEqual(json.loads(metadata["genres"]), ["Action", "Drama"])
            self.assertEqual(metadata["studio"], "WIT Studio")
            self.assertEqual(metadata["episodes_count"], 25)
            self.assertEqual(metadata["score"], 91)
            self.assertEqual(metadata["format"], None)
            self.assertEqual(metadata["banner_url"], "https://img.example/banner.jpg")
            self.assertEqual(metadata["status"], "FINISHED")
            self.assertEqual(metadata["duration"], 24)

    def test_metadata_with_chosen_id_does_not_crash_on_candidates_without_id(self):
        client = AniListClient("/tmp/cache")
        candidate = {"title": {"romaji": "Attack on Titan"}}
        with patch.object(client, "by_id", return_value=None), patch.object(client, "search", return_value=[candidate]):
            result = client.metadata("Attack on Titan", chosen_id=123)
        self.assertEqual(result["title"], "Attack on Titan")
        self.assertNotIn("anilist_id", result)

    def test_cover_cache_reuses_non_empty_file_without_network(self):
        with tempfile.TemporaryDirectory() as directory:
            client = AniListClient(directory)
            url = "https://img.example/cover.jpg"
            expected = Path(directory) / next(iter(Path(directory).iterdir()), "missing")
            name = __import__("hashlib").sha256(url.encode()).hexdigest() + ".jpg"
            expected = Path(directory) / name
            expected.write_bytes(b"cached")
            with patch("core.anilist.urllib.request.urlopen") as request:
                self.assertEqual(client.cache_cover(url), str(expected))
                request.assert_not_called()

    def test_cover_cache_rejects_empty_download_and_cleans_temp_file(self):
        with tempfile.TemporaryDirectory() as directory:
            client = AniListClient(directory)
            fake = type("Response", (), {
                "__enter__": lambda self: self,
                "__exit__": lambda self, *args: None,
                "read": lambda self: b"",
            })()
            with patch("core.anilist.urllib.request.urlopen", return_value=fake):
                self.assertEqual(client.cache_cover("https://img.example/empty.jpg"), "")
            self.assertEqual(list(Path(directory).iterdir()), [])

    def test_cover_cache_retries_after_previous_failure(self):
        with tempfile.TemporaryDirectory() as directory:
            client = AniListClient(directory)
            url = "https://img.example/retry.jpg"
            fake = type("Response", (), {
                "__enter__": lambda self: self,
                "__exit__": lambda self, *args: None,
                "read": lambda self: b"cover-bytes",
            })()
            with patch("core.anilist.urllib.request.urlopen", return_value=fake):
                path = client.cache_cover(url)
            self.assertTrue(Path(path).is_file())
            self.assertEqual(Path(path).read_bytes(), b"cover-bytes")

    def test_rate_headers_adapt_request_pacing_to_current_limit(self):
        client = AniListClient('/tmp/cache')
        response = {'data': {'Media': {'id': 123}}}
        fake = type('Response', (), {
            '__enter__': lambda self: self,
            '__exit__': lambda self, *args: None,
            'read': lambda self: json.dumps(response).encode(),
            'headers': {'X-RateLimit-Limit': '30', 'X-RateLimit-Remaining': '29', 'X-RateLimit-Reset': str(int(__import__('time').time()) + 60)},
        })()
        with patch('core.anilist.urllib.request.urlopen', return_value=fake):
            self.assertEqual(client._request('query', {}), response['data'])
        self.assertGreaterEqual(client._min_interval, 2.0)


    def test_description_html_is_normalized_without_losing_paragraphs_or_utf8(self):
        source = "<p>Ação &amp; coração.</p><br><br><p>São Paulo — 日本語</p>"
        self.assertEqual(
            AniListClient.normalize_description(source),
            "Ação & coração.\n\nSão Paulo — 日本語",
        )

    def test_description_language_detection_is_conservative_and_not_two_word_heuristic(self):
        english = "The story follows a young hero who protects their people."
        portuguese = "A história acompanha uma jovem garota que protege seus amigos."
        japanese = "これは日本語の説明です。主人公の物語が始まります。"
        self.assertEqual(AniListClient.detect_description_language(english)[0], "en")
        self.assertEqual(AniListClient.detect_description_language(portuguese)[0], "pt")
        self.assertEqual(AniListClient.detect_description_language(japanese)[0], "ja")

    def test_portuguese_description_does_not_call_translator(self):
        with tempfile.TemporaryDirectory() as directory:
            client = AniListClient(directory)
            source = "A história acompanha uma jovem garota em uma cidade pequena."
            with patch.object(client, "_translate_chunk_to_pt_br") as translate:
                self.assertEqual(source, client.localize_description_to_pt_br(source))
            translate.assert_not_called()

    def test_empty_description_does_not_call_translator(self):
        with tempfile.TemporaryDirectory() as directory:
            client = AniListClient(directory)
            with patch.object(client, "_translate_chunk_to_pt_br") as translate:
                self.assertEqual("", client.localize_description_to_pt_br(""))
            translate.assert_not_called()

    def test_invalid_translation_response_does_not_fallback_to_original(self):
        with tempfile.TemporaryDirectory() as directory:
            client = AniListClient(directory)
            source = "This is the story of a young hero who protects their town."
            with patch.object(client, "_translate_chunk_to_pt_br", return_value='{"error":"rate limit"}') as translate:
                self.assertIsNone(client.localize_description_to_pt_br(source))
            translate.assert_called_once()

    def test_foreign_translation_is_rejected_when_output_is_not_portuguese(self):
        with tempfile.TemporaryDirectory() as directory:
            client = AniListClient(directory)
            source = "The story follows a young hero who protects their town."
            with patch.object(
                client,
                "_translate_chunk_to_pt_br",
                return_value="The translated result is still English.",
            ) as translate:
                self.assertIsNone(client.localize_description_to_pt_br(source))
            translate.assert_called_once()

    def test_description_change_invalidates_previous_translation_cache(self):
        with tempfile.TemporaryDirectory() as directory:
            client = AniListClient(directory)
            old_source = "The original story follows a young hero."
            new_source = "The updated story follows a young hero."
            with patch.object(
                client,
                "_translate_chunk_to_pt_br",
                side_effect=["A história original acompanha um jovem herói.",
                             "A história atualizada acompanha um jovem herói."],
            ) as translate:
                self.assertEqual(
                    "A história original acompanha um jovem herói.",
                    client.localize_description_to_pt_br(old_source),
                )
                self.assertEqual(
                    "A história atualizada acompanha um jovem herói.",
                    client.localize_description_to_pt_br(new_source),
                )
            self.assertEqual(2, translate.call_count)

    def test_cached_translation_survives_new_client_offline(self):
        with tempfile.TemporaryDirectory() as directory:
            source = "This is the story of a young hero in a new town."
            first = AniListClient(directory)
            with patch.object(first, "_translate_chunk_to_pt_br", return_value="Esta é a história de um jovem herói em uma nova cidade."):
                expected = first.localize_description_to_pt_br(source)

            second = AniListClient(directory)
            with patch.object(second, "_translate_chunk_to_pt_br", side_effect=AssertionError("network must not be used")):
                self.assertEqual(expected, second.localize_description_to_pt_br(source))

    def test_translation_retry_handles_transient_429_before_success(self):
        with tempfile.TemporaryDirectory() as directory:
            client = AniListClient(directory)
            rate_limited = HTTPError(
                "https://api.mymemory.translated.net/get",
                429,
                "rate",
                {"Retry-After": "0"},
                None,
            )
            ok_response = type("Response", (), {
                "__enter__": lambda self: self,
                "__exit__": lambda self, *args: None,
                "read": lambda self: json.dumps({
                    "responseStatus": 200,
                    "responseData": {"translatedText": "Esta é uma tradução válida."},
                }).encode("utf-8"),
                "status": 200,
            })()
            with patch("core.anilist.urllib.request.urlopen", side_effect=[rate_limited, ok_response]) as request, \
                 patch("core.anilist.time.sleep") as sleep:
                self.assertEqual(
                    "Esta é uma tradução válida.",
                    client._translate_chunk_to_pt_br("This is a valid translation.", "en"),
                )
            self.assertEqual(2, request.call_count)
            sleep.assert_called_once()

    def test_paragraph_chunking_preserves_order_and_utf8_byte_limit(self):
        first = "ação coração São Paulo " * 40
        second = "日本語の説明 " * 40
        sections = AniListClient._paragraph_chunks(first + "\n\n" + second, max_bytes=120)
        self.assertEqual(2, len(sections))
        self.assertTrue(all(len(chunk.encode("utf-8")) <= 120 for section in sections for chunk in section))
        self.assertIn("ação", sections[0][0])
        self.assertIn("日本語", sections[1][0])

    def test_concurrent_identical_translation_is_deduplicated(self):
        with tempfile.TemporaryDirectory() as directory:
            client = AniListClient(directory)
            source = "This is the story of a young hero in a new town."
            calls = []

            def translate(_text, _source):
                calls.append(1)
                time.sleep(0.05)
                return "Esta é a história de um jovem herói em uma nova cidade."

            results = []
            with patch.object(client, "_translate_chunk_to_pt_br", side_effect=translate):
                threads = [
                    threading.Thread(target=lambda: results.append(client.localize_description_to_pt_br(source)))
                    for _ in range(2)
                ]
                for thread in threads:
                    thread.start()
                for thread in threads:
                    thread.join()
            self.assertEqual(1, len(calls))
            self.assertEqual(2, results.count("Esta é a história de um jovem herói em uma nova cidade."))

if __name__ == "__main__":
    unittest.main()
