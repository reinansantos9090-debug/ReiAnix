import unittest

from core.library_parser import parse_video_path


class ProfessionalIdentificationTests(unittest.TestCase):
    def test_explicit_patterns(self):
        for name in ("Show S01E01.mkv", "Show S1E1.mkv", "Show S001E001.mkv", "Show 1x01.mkv"):
            item = parse_video_path(name)
            self.assertEqual((item.season, item.episode), (1, 1))
            self.assertEqual(item.confidence, "high")

    def test_episode_token_does_not_invent_season(self):
        item = parse_video_path("Frieren Episode 01.mkv")
        self.assertEqual(item.episode, 1)
        self.assertEqual(item.season, 1)

    def test_folder_context_supplies_season_only(self):
        item = parse_video_path("/Anime/One Piece/Season 03/RandomVideo.mkv")
        self.assertEqual(item.anime_title, "One Piece")
        self.assertEqual(item.season, 3)
        self.assertIsNone(item.episode)
        self.assertEqual(item.confidence, "low")

    def test_specials(self):
        expected = {
            "Show OVA 01.mkv": ("ova", 1),
            "Show OAD01.mkv": ("oad", 1),
            "Show ONA01.mkv": ("ona", 1),
            "Show SP01.mkv": ("special", 1),
            "Show Special 01.mkv": ("special", 1),
            "Show Extra 01.mkv": ("extra", 1),
        }
        for name, value in expected.items():
            item = parse_video_path(name)
            self.assertEqual((item.episode_type, item.episode), value)
            self.assertEqual(item.confidence, "high")

    def test_movie_never_becomes_episode(self):
        for name in ("Show Movie.mkv", "Show Film 01.mkv"):
            item = parse_video_path(name)
            self.assertEqual(item.episode_type, "movie")
            self.assertIsNone(item.episode)

    def test_absolute_number_is_separate(self):
        item = parse_video_path("One Piece ABS1122.mkv")
        self.assertEqual(item.absolute_number, 1122)
        self.assertIsNone(item.episode)
        combined = parse_video_path("Show S02E03 ABS29.mkv")
        self.assertEqual((combined.season, combined.episode, combined.absolute_number), (2, 3, 29))

    def test_legacy_numeric_suffix_without_forced_season(self):
        item = parse_video_path("One Piece 1100.mkv")
        self.assertEqual(item.episode, 1100)
        self.assertEqual(item.season, 1)
        self.assertEqual(item.identification_source, "numeric_suffix")

    def test_pure_numeric_and_technical_numbers_are_not_episodes(self):
        for name in ("001.mkv", "01.mkv", "2024.mkv", "Anime.2024.1080p.mkv",
                     "Anime.2160p.mkv", "Anime.10bit.mkv", "Anime.x265.mkv",
                     "Anime.264.mkv", "Anime.Vol.01.mkv", "Anime.Disc.01.mkv"):
            self.assertIsNone(parse_video_path(name).episode)

    def test_release_group_delimiters_and_technical_noise(self):
        item = parse_video_path("[SubsPlease] Frieren.S01E01.1080p.WEB-DL.x265.mkv")
        self.assertEqual(item.anime_title, "Frieren")
        self.assertEqual((item.season, item.episode), (1, 1))
        self.assertTrue(any(token.casefold() == "1080p" for token in item.technical_tokens))

    def test_folder_series_and_file_episode(self):
        item = parse_video_path("/Anime/One Piece/Season 02/Episode 03.mkv")
        self.assertEqual(item.anime_title, "One Piece")
        self.assertEqual((item.season, item.episode), (2, 3))

    def test_conflicting_season_evidence(self):
        item = parse_video_path("/Anime/Show/Season 01/Show S02E03.mkv")
        self.assertEqual((item.season, item.episode), (2, 3))
        self.assertEqual(item.confidence, "medium")
        self.assertIn("CONFLICT_FILE_SEASON_VS_FOLDER", item.evidence)

    def test_episode_title_separated_from_identity(self):
        item = parse_video_path("Show S01E03 - The Final Battle 1080p.mkv")
        self.assertEqual(item.anime_title, "Show")
        self.assertEqual(item.display_title, "The Final Battle")

    def test_unicode_path_context(self):
        item = parse_video_path("/Anime/進撃の巨人/Season 01/第01話.mkv")
        self.assertEqual(item.anime_title, "進撃の巨人")
        self.assertEqual(item.season, 1)
        self.assertIsNone(item.episode)

    def test_multi_episode_is_not_duplicated(self):
        item = parse_video_path("Show S01E01E02.mkv")
        self.assertEqual(item.episode, 1)
        self.assertIn("multi_episode_range", item.unresolved_parts)

    def test_deterministic(self):
        name = "[Group] One.Piece.S01E1122.1080p.WEB-DL.x265.mkv"
        self.assertEqual(parse_video_path(name), parse_video_path(name))


if __name__ == "__main__":
    unittest.main()
