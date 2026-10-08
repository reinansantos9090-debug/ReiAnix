import copy
import os
import tempfile
import unittest

from core.library_service import LibraryService
from core.library_store import LibraryStore
from core.search_engine import LibrarySearchEngine, normalize_text


def episode(name, season, number, *, absolute=None, episode_type="regular", watched=False,
            progress=0, duration=100, missing=False, last_played_at=None,
            modified_at=0, file_size=0, source_kind="filesystem", title=None):
    return {
        "id": f"{name}-{season}-{number}-{episode_type}",
        "file_name": f"{name} S{season:02d}E{number:02d}.mkv",
        "path": f"/{name}/{season}/{number}.mkv",
        "episode_title": title,
        "season": season,
        "number": number,
        "absolute_number": absolute,
        "episode_type": episode_type,
        "progress": progress,
        "duration": duration,
        "watched": watched,
        "missing": missing,
        "last_played_at": last_played_at,
        "modified_at": modified_at,
        "file_size": file_size,
        "source_kind": source_kind,
    }


def anime(title, *, media_kind="series", aliases=None, genres=None, tags=None,
          favorite=False, pinned=False, note=None, metadata=True, artwork=False,
          added_at=0, seasons=None, specials=None, movies=None):
    meta = {
        "title": title,
        "romaji": title,
        "english": title,
        "native": title,
        "aliases": aliases or [],
        "genres": genres or [],
        "studio": "Studio Local",
        "added_at": added_at,
        "anilist_id": 1 if metadata else None,
        "metadata_source": "anilist" if metadata else "local",
        "cover_cache": "/covers/poster.jpg" if artwork else "",
    }
    seasons = seasons or []
    specials = specials or []
    movies = movies or []
    regulars = [e for e in seasons]
    grouped = []
    by_season = {}
    for e in regulars:
        by_season.setdefault(e["season"], []).append(e)
    for season, values in sorted(by_season.items()):
        grouped.append({"season": season, "season_name": f"Temporada {season}", "episodes": values})
    special_group = [{"season": None, "season_name": "Especiais", "episodes": specials}] if specials else []
    return {
        "id": title,
        "main_title": title,
        "meta": meta,
        "favorite": favorite,
        "is_pinned": pinned,
        "user_tags": tags or [],
        "personal_note": note,
        "genres": genres or [],
        "seasons": grouped,
        "specials": special_group,
        "media_files": movies,
        "media_kind": media_kind,
        "artwork_available": artwork,
    }


class SearchEngineTests(unittest.TestCase):
    def setUp(self):
        self.show = anime(
            "One Piece", aliases=["Wan Pīsu", "ワンピース"], genres=["Action", "Aventura"],
            tags=["Favorito"], favorite=True, pinned=True, note="assistir depois",
            artwork=True, added_at=30,
            seasons=[
                episode("One Piece", 1, 1, absolute=1, progress=10, last_played_at=10, modified_at=30, file_size=100),
                episode("One Piece", 1, 2, absolute=2, watched=True, progress=100, last_played_at=20, modified_at=40, file_size=200),
                episode("One Piece", 1, 10, absolute=100, modified_at=50, file_size=300),
            ],
        )
        self.special = anime(
            "Especial Café", genres=["Drama"], tags=["especial"], added_at=20, metadata=False,
            specials=[episode("Especial Café", 1, 1, episode_type="ova", title="Café após a batalha", missing=True)],
        )
        self.movie = anime(
            "O Filme", media_kind="movie", added_at=40, metadata=False,
            movies=[episode("O Filme", 1, 1, episode_type="movie", modified_at=60, file_size=500)],
        )
        self.library = [self.show, self.special, self.movie]

    def names(self, result):
        return [item["main_title"] for item in result]

    def test_exact_partial_case_accent_and_separators(self):
        self.assertEqual(self.names(LibrarySearchEngine.search(self.library, query="one piece")), ["One Piece"])
        self.assertEqual(self.names(LibrarySearchEngine.search(self.library, query="ONE-PIECE")), ["One Piece"])
        self.assertEqual(self.names(LibrarySearchEngine.search(self.library, query="cafE")), ["Especial Café"])
        self.assertEqual(self.names(LibrarySearchEngine.search(self.library, query="one  piece")), ["One Piece"])

    def test_multiple_terms_and_unicode_alias(self):
        self.assertEqual(self.names(LibrarySearchEngine.search(self.library, query="action one")), ["One Piece"])
        self.assertEqual(self.names(LibrarySearchEngine.search(self.library, query="wan pīsu")), ["One Piece"])
        self.assertEqual(self.names(LibrarySearchEngine.search(self.library, query="ワンピース")), ["One Piece"])

    def test_identifier_search_uses_persisted_fields_only(self):
        self.assertEqual(self.names(LibrarySearchEngine.search(self.library, query="S01E01")), ["One Piece"])
        self.assertEqual(self.names(LibrarySearchEngine.search(self.library, query="S01")), ["One Piece"])
        self.assertEqual(self.names(LibrarySearchEngine.search(self.library, query="E10")), ["One Piece"])
        self.assertEqual(self.names(LibrarySearchEngine.search(self.library, query="EP 2")), ["One Piece"])
        self.assertEqual(self.names(LibrarySearchEngine.search(self.library, query="episódio 2")), ["One Piece"])
        self.assertEqual(self.names(LibrarySearchEngine.search(self.library, query="absolute 100")), ["One Piece"])
        self.assertEqual(self.names(LibrarySearchEngine.search(self.library, query="100")), ["One Piece"])

    def test_media_type_and_content_filters_are_combinable(self):
        self.assertEqual(self.names(LibrarySearchEngine.search(self.library, media_type="Filme")), ["O Filme"])
        self.assertEqual(self.names(LibrarySearchEngine.search(self.library, media_type="Especial")), ["Especial Café"])
        self.assertEqual(self.names(LibrarySearchEngine.search(self.library, media_type="Série/Anime")), ["One Piece"])
        self.assertEqual(self.names(LibrarySearchEngine.search(self.library, season=1, episode_type="regular")), ["One Piece"])
        self.assertEqual(self.names(LibrarySearchEngine.search(self.library, tag="Favorito", state="Favoritos")), ["One Piece"])
        self.assertEqual(self.names(LibrarySearchEngine.search(self.library, genre="Aventura", tag="Favorito")), ["One Piece"])

    def test_state_availability_metadata_and_artwork_filters(self):
        self.assertEqual(self.names(LibrarySearchEngine.search(self.library, state="Favoritos")), ["One Piece"])
        self.assertEqual(self.names(LibrarySearchEngine.search(self.library, state="Fixados")), ["One Piece"])
        self.assertEqual(self.names(LibrarySearchEngine.search(self.library, state="Em andamento")), ["One Piece"])
        self.assertEqual(self.names(LibrarySearchEngine.search(self.library, availability="Com missing")), ["Especial Café"])
        self.assertEqual(self.names(LibrarySearchEngine.search(self.library, metadata="Ausente")), ["Especial Café", "O Filme"])
        self.assertEqual(self.names(LibrarySearchEngine.search(self.library, artwork="Disponível")), ["One Piece"])

    def test_source_kind_filter_and_missing_episode_type(self):
        self.assertEqual(self.names(LibrarySearchEngine.search(self.library, source_kind="filesystem")), ["One Piece", "O Filme"])
        self.assertEqual(self.names(LibrarySearchEngine.search(self.library, episode_type="ova")), ["Especial Café"])

    def test_sorting_is_semantic_and_numeric(self):
        result = LibrarySearchEngine.search([self.show], sort="Episódio", descending=False)
        numbers = [e["number"] for e in result[0]["seasons"][0]["episodes"]]
        self.assertEqual(numbers, [1, 2, 10])
        self.assertEqual(self.names(LibrarySearchEngine.search(self.library, sort="Nome A-Z")), ["Especial Café", "O Filme", "One Piece"])
        self.assertEqual(self.names(LibrarySearchEngine.search(self.library, sort="Nome Z-A")), ["One Piece", "O Filme", "Especial Café"])
        self.assertEqual(self.names(LibrarySearchEngine.search(self.library, sort="Mais recentes")), ["O Filme", "One Piece", "Especial Café"])
        self.assertEqual(self.names(LibrarySearchEngine.search(self.library, sort="Assistidos recentemente")), ["One Piece", "Especial Café", "O Filme"])

    def test_all_sort_modes_are_stable_and_available(self):
        for sort in LibrarySearchEngine.options(self.library)["sorts"]:
            result = LibrarySearchEngine.search(self.library, sort=sort)
            self.assertEqual(len(result), 3)
        self.assertEqual(self.names(LibrarySearchEngine.search(self.library, sort="Modificação")), ["O Filme", "One Piece", "Especial Café"])
        self.assertEqual(self.names(LibrarySearchEngine.search(self.library, sort="Tamanho")), ["One Piece", "O Filme", "Especial Café"])

    def test_hierarchy_and_user_state_are_not_mutated(self):
        before = copy.deepcopy(self.library)
        result = LibrarySearchEngine.search(self.library, query="E10", state="Favoritos", sort="Episódio")
        self.assertEqual(result[0]["main_title"], "One Piece")
        self.assertEqual(result[0]["seasons"][0]["episodes"][2]["number"], 10)
        self.assertEqual(self.library, before)
        self.assertTrue(self.show["favorite"])
        self.assertTrue(self.show["is_pinned"])
        self.assertEqual(self.show["seasons"][0]["episodes"][1]["progress"], 100)

    def test_empty_library_is_safe(self):
        self.assertEqual(LibrarySearchEngine.search([]), [])
        self.assertIn("Série/Anime", LibrarySearchEngine.options([])["media_types"])

    def test_store_catalog_exposes_source_and_local_artwork_for_filters(self):
        with tempfile.TemporaryDirectory() as directory:
            store = LibraryStore(directory)
            source = os.path.join(directory, "videos")
            os.makedirs(source)
            store.add_folder(source, name="Local", kind="filesystem")
            anime_id = store.upsert_anime("local-show", {"title": "Local Show", "genres": "[]"})
            path = os.path.join(source, "Local Show S01E01.mkv")
            store.upsert_episode(anime_id, path, os.path.basename(path), 1, 1, source_folder=source)
            cover = os.path.join(source, "poster.jpg")
            with open(cover, "wb") as handle:
                handle.write(b"not-a-real-image")
            with store._conn() as con:
                con.execute(
                    """INSERT INTO artwork(entity_type,entity_id,artwork_type,source,source_ref,local_path,
                       external_url,manual,priority,status,discovered_at,updated_at,failure_count)
                       VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)""",
                    ("anime", str(anime_id), "poster", "local", cover, cover, None, 0, 1, "ready", 1, 1, 0),
                )
            row = store.catalog()[0]
            episode_row = row["seasons"][0]["episodes"][0]
            self.assertEqual(episode_row["source_kind"], "filesystem")
            self.assertTrue(row["artwork_available"])

    def test_service_facade_remains_compatible(self):
        result = LibraryService.browse_catalog(self.library, "one-piece", "Favoritos", "Aventura", "Nome A-Z", "Favorito",
                                               media_type="Série/Anime", season=1)
        self.assertEqual(self.names(result), ["One Piece"])

    def test_normalization_does_not_change_persisted_value(self):
        self.assertEqual(normalize_text("  São-Paulo  "), "sao paulo")
        self.assertEqual(self.show["main_title"], "One Piece")


if __name__ == "__main__":
    unittest.main()
