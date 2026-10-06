import copy
import inspect
import unittest
from pathlib import Path

import flet as ft

from views.details_view import DetailView


ROOT = Path(__file__).resolve().parents[1]
DETAILS_SOURCE = ROOT / "views" / "details_view.py"
MAIN_SOURCE = ROOT / "main.py"


class _FakePage:
    """Minimal page surface needed to construct DetailView without mounting it."""

    width = 480
    theme_mode = ft.ThemeMode.DARK
    platform_brightness = ft.Brightness.DARK
    snack_bar = None

    def update(self):
        return None

    def show_dialog(self, _dialog):
        return None

    def run_task(self, coroutine):
        if inspect.iscoroutine(coroutine):
            coroutine.close()
        return None


def _walk_controls(root):
    seen = set()
    stack = [root]
    while stack:
        control = stack.pop()
        if control is None or id(control) in seen:
            continue
        seen.add(id(control))
        yield control

        controls = getattr(control, "controls", None)
        if isinstance(controls, (list, tuple)):
            stack.extend(reversed(controls))

        content = getattr(control, "content", None)
        if content is not None:
            stack.append(content)


def _key_text(control):
    key = getattr(control, "key", None)
    if key is None:
        return None
    value = getattr(key, "value", None)
    return str(value if value is not None else key)


def _episode_keys(root):
    return [
        _key_text(control)
        for control in _walk_controls(root)
        if (_key_text(control) or "").startswith(("episode:", "special:", "movie:"))
    ]


def _text_values(root):
    return [
        getattr(control, "value", None)
        for control in _walk_controls(root)
        if isinstance(control, ft.Text)
    ]


def _progress_bars(root):
    return [control for control in _walk_controls(root) if isinstance(control, ft.ProgressBar)]


def _episode(number, progress=0, *, missing=False):
    return {
        "id": number,
        "path": f"/library/episode-{number:02d}.mp4",
        "file_name": f"episode-{number:02d}.mp4",
        "number": number,
        "season": 1,
        "episode_title": f"EP{number:02d}",
        "duration": 1000,
        "progress": progress,
        "watched": False,
        "missing": missing,
        "episode_type": "regular",
    }


def _anime(episodes, *, current_episode=None, specials=None, media_files=None):
    return {
        "id": 9001,
        "main_title": "ReiAnix regression fixture",
        "meta": {"title_official": "ReiAnix regression fixture"},
        "seasons": [{"season": 1, "season_name": "Temporada 1", "episodes": episodes}],
        "specials": specials or [],
        "media_files": media_files or [],
        "current_episode": current_episode or {},
    }


def _build(group):
    page = _FakePage()
    return DetailView.build(
        page=page,
        anime_group=copy.deepcopy(group),
        on_play_episode=lambda *args, **kwargs: None,
        on_back=lambda: None,
        on_toggle_favorite=lambda _anime_id: False,
    )


class DetailsEpisodeReconciliationTests(unittest.TestCase):
    def test_real_detail_tree_keeps_all_episode_ids_through_progress_rebuild(self):
        episodes = [_episode(number) for number in range(1, 11)]
        before = _build(_anime(episodes))

        progressed = [_episode(number, 180 if number == 7 else 0) for number in range(1, 11)]
        after = _build(
            _anime(
                progressed,
                current_episode=copy.deepcopy(progressed[6]),
            )
        )

        before_ids = _episode_keys(before)
        after_ids = _episode_keys(after)

        self.assertEqual(before_ids, [f"episode:{number}" for number in range(1, 11)])
        self.assertEqual(after_ids, before_ids)
        self.assertEqual(len(after_ids), 10)
        self.assertIn("episode:7", after_ids)

        texts = _text_values(after)
        self.assertIn("Temporada 1 • Episódio 7", texts)
        self.assertIn("18% assistido", texts)

        # One progress bar exists in every episode card; the Continue projection
        # may add one more when an episode is in progress.
        self.assertGreaterEqual(len(_progress_bars(after)), 10)

    def test_zero_to_progress_and_repeated_rebuilds_preserve_order_and_uniqueness(self):
        expected = [f"episode:{number}" for number in range(1, 11)]
        for progress in (0, 10, 180, 500, 990, 1000):
            episodes = [
                _episode(number, progress if number == 7 else 0)
                for number in range(1, 11)
            ]
            root = _build(
                _anime(
                    episodes,
                    current_episode=copy.deepcopy(episodes[6]) if 0 < progress < 1000 else {},
                )
            )
            ids = _episode_keys(root)
            self.assertEqual(ids, expected)
            self.assertEqual(len(ids), len(set(ids)))

        # Rebuild the screen repeatedly with different active episodes.
        states = (7, 7, 8, 7)
        for active_number in states:
            episodes = [
                _episode(number, 180 if number == active_number else 0)
                for number in range(1, 11)
            ]
            root = _build(
                _anime(
                    episodes,
                    current_episode=copy.deepcopy(episodes[active_number - 1]),
                )
            )
            self.assertEqual(_episode_keys(root), expected)

    def test_missing_episode_keeps_a_stable_card_and_existing_missing_semantics(self):
        episodes = [_episode(number, missing=(number == 7)) for number in range(1, 11)]
        root = _build(_anime(episodes))

        ids = _episode_keys(root)
        self.assertIn("episode:7", ids)
        self.assertEqual(ids, [f"episode:{number}" for number in range(1, 11)])
        self.assertIn("Arquivo indisponível", _text_values(root))

    def test_special_and_movie_keys_cannot_collide_with_regular_episode_ids(self):
        regular = _episode(1)
        special = _episode(1)
        special["episode_type"] = "special"
        special["episode_title"] = "SPECIAL 01"

        root = _build(
            _anime(
                [regular],
                specials=[{"season": 1, "episodes": [special]}],
            )
        )
        keys = _episode_keys(root)
        self.assertIn("episode:1", keys)
        self.assertIn("special:1", keys)
        self.assertNotEqual("episode:1", "special:1")

        movie = _episode(1)
        movie["episode_type"] = "movie"
        movie_root = _build(
            _anime(
                [],
                media_files=[movie],
            )
        )
        self.assertIn("movie:1", _episode_keys(movie_root))

    def test_details_build_contract_is_declarative_and_progress_bar_is_structurally_stable(self):
        source = DETAILS_SOURCE.read_text(encoding="utf-8")

        self.assertIn('ft.ValueKey(f"{scope}:{identity}")', source)
        self.assertIn("controls=build_episode_controls()", source)
        self.assertIn("episode_column.controls = build_episode_controls()", source)
        self.assertNotIn("episode_column.controls.clear()", source)
        self.assertNotIn("episode_column.controls.extend", source)

        start = source.index("def episode_item(episode, *, scope=")
        end = source.index("        def update_thumbnail_in_place", start)
        episode_item = source[start:end]

        self.assertIn("progress_bar = ft.ProgressBar(", episode_item)
        self.assertIn("details.controls.append(progress_bar)", episode_item)
        self.assertIn("visible=show_progress", episode_item)

        # There must not be a second tree shape that appends the progress bar only
        # on an in-progress episode.
        self.assertNotIn(
            'if episode_ratio is not None and episode_ratio > 0 and not episode.get("missing") and state.value == "in_progress":',
            episode_item,
        )

        render_start = source.index("def render_episodes():")
        render_end = source.index("        episode_column = ft.Column(", render_start)
        render_block = source[render_start:render_end]
        self.assertNotIn("page.update()", render_block)
        self.assertNotIn("episode_column.update()", render_block)
        self.assertNotIn("controls.clear()", render_block)
        self.assertNotIn("controls.extend", render_block)
        self.assertIn("episode_column.controls = build_episode_controls()", render_block)

    def test_details_catalog_changes_use_canonical_refresh_path(self):
        source = MAIN_SOURCE.read_text(encoding="utf-8")

        self.assertIn(
            'anime_id = current[0].get("id") if current[0] else None',
            source,
        )
        self.assertIn("catalog = await asyncio.to_thread(library.catalog)", source)
        self.assertIn("page.run_task(refresh_current_details)", source)

        start = source.index("def on_catalog_changed(*, refresh_details=True, refresh_request_id=None):")
        end = source.index("    def apply_settings_runtime", start)
        block = source[start:end]

        details_start = block.index('if navigation.current == "details":')
        details_block = block[details_start:block.index(
            '        _drop_screen_cache(navigation.current)',
            details_start,
        )]
        self.assertIn("if not refresh_details:", details_block)
        self.assertIn("page.run_task(refresh_current_details)", details_block)
        self.assertNotIn('render_current(reason="catalog_changed")', details_block)


if __name__ == "__main__":
    unittest.main()
