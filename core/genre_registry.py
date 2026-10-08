"""Persistent local-first genre identity registry."""
from __future__ import annotations
import json
import re
import time
import unicodedata
import uuid
from dataclasses import dataclass

_SYSTEM_ALIASES = {
    "Action": ("Ação",),
    "Adventure": ("Aventura",),
    "Fantasy": ("Fantasia",),
    "Romance": (),
    "Comedy": ("Comédia",),
    "Horror": ("Terror",),
    "Sports": ("Esporte",),
}

def normalize_genre(value) -> str:
    text = unicodedata.normalize("NFKD", str(value or ""))
    text = "".join(ch for ch in text if not unicodedata.combining(ch))
    text = text.casefold().strip()
    text = re.sub(r"[-_]+", " ", text)
    text = re.sub(r"[^\w\s/]+", " ", text, flags=re.UNICODE)
    return " ".join(text.split())

@dataclass(frozen=True)
class Genre:
    id: str
    canonical_name: str
    normalized_name: str
    source: str
    aliases: tuple[str, ...] = ()
    is_system: bool = False
    is_custom: bool = False

class GenreRegistry:
    """One genre identity/association layer backed by the existing SQLite store."""

    def __init__(self, store):
        self.store = store
        self._ensure_schema()
        self._seed_system_aliases()
        self._migrate_legacy()

    def _ensure_schema(self):
        with self.store._conn() as c:
            c.executescript("""
            CREATE TABLE IF NOT EXISTS genres (
              id TEXT PRIMARY KEY, canonical_name TEXT NOT NULL,
              normalized_name TEXT NOT NULL UNIQUE, source TEXT NOT NULL DEFAULT 'local',
              is_system INTEGER NOT NULL DEFAULT 0, is_custom INTEGER NOT NULL DEFAULT 0,
              created_at REAL NOT NULL, updated_at REAL NOT NULL
            );
            CREATE TABLE IF NOT EXISTS genre_aliases (
              id INTEGER PRIMARY KEY, genre_id TEXT NOT NULL REFERENCES genres(id) ON DELETE CASCADE,
              alias TEXT NOT NULL, normalized_alias TEXT NOT NULL UNIQUE,
              source TEXT NOT NULL DEFAULT 'system', created_at REAL NOT NULL, updated_at REAL NOT NULL
            );
            CREATE TABLE IF NOT EXISTS anime_genres (
              anime_id INTEGER NOT NULL REFERENCES anime(id) ON DELETE CASCADE,
              genre_id TEXT NOT NULL REFERENCES genres(id) ON DELETE CASCADE,
              source TEXT NOT NULL DEFAULT 'local', created_at REAL NOT NULL, updated_at REAL NOT NULL,
              PRIMARY KEY(anime_id, genre_id, source)
            );
            CREATE INDEX IF NOT EXISTS idx_genre_aliases_genre ON genre_aliases(genre_id);
            CREATE INDEX IF NOT EXISTS idx_anime_genres_genre ON anime_genres(genre_id, anime_id);
            CREATE INDEX IF NOT EXISTS idx_anime_genres_anime ON anime_genres(anime_id, genre_id);
            """)

    @staticmethod
    def _names(value):
        if isinstance(value, (list, tuple, set)):
            return [str(v).strip() for v in value if str(v).strip()]
        if isinstance(value, str):
            try:
                value = json.loads(value)
                return [str(v).strip() for v in value if str(v).strip()] if isinstance(value, list) else []
            except (TypeError, ValueError):
                return [value.strip()] if value.strip() else []
        return []

    def _migrate_legacy(self):
        with self.store._conn() as c:
            rows = c.execute("SELECT id,genres FROM anime WHERE genres IS NOT NULL AND TRIM(genres)!=''").fetchall()
        for row in rows:
            self.sync_anime(row["id"], self._names(row["genres"]), source="local", replace_source=False)

    def _seed_system_aliases(self):
        for canonical, aliases in _SYSTEM_ALIASES.items():
            genre = self.register(canonical, source="system", is_system=True)
            for alias in aliases:
                self.add_alias(genre.id, alias)

    def _row(self, c, row):
        if not row:
            return None
        aliases = tuple(r["alias"] for r in c.execute(
            "SELECT alias FROM genre_aliases WHERE genre_id=? ORDER BY normalized_alias", (row["id"],)))
        return Genre(str(row["id"]), row["canonical_name"], row["normalized_name"], row["source"],
                     aliases, bool(row["is_system"]), bool(row["is_custom"]))

    def register(self, name, *, source="local", is_system=False, is_custom=False) -> Genre:
        name = " ".join(str(name or "").split()).strip()
        normalized = normalize_genre(name)
        if not normalized:
            raise ValueError("Gênero vazio não pode ser registrado.")
        with self.store._conn() as c:
            row = c.execute("""SELECT g.* FROM genres g WHERE g.normalized_name=?
                               UNION SELECT g.* FROM genres g JOIN genre_aliases a ON a.genre_id=g.id
                               WHERE a.normalized_alias=? LIMIT 1""", (normalized, normalized)).fetchone()
            if row:
                return self._row(c, row)
            now = time.time()
            gid = str(uuid.uuid4())
            c.execute("""INSERT INTO genres(id,canonical_name,normalized_name,source,is_system,is_custom,created_at,updated_at)
                         VALUES(?,?,?,?,?,?,?,?)""",
                      (gid, name, normalized, str(source or "local").casefold(),
                       int(bool(is_system)), int(bool(is_custom)), now, now))
            return self._row(c, c.execute("SELECT * FROM genres WHERE id=?", (gid,)).fetchone())

    def resolve(self, value) -> Genre | None:
        normalized = normalize_genre(value)
        if not normalized:
            return None
        with self.store._conn() as c:
            row = c.execute("""SELECT g.* FROM genres g WHERE g.normalized_name=?
                               UNION SELECT g.* FROM genres g JOIN genre_aliases a ON a.genre_id=g.id
                               WHERE a.normalized_alias=? LIMIT 1""", (normalized, normalized)).fetchone()
            return self._row(c, row)

    def get(self, genre_id):
        with self.store._conn() as c:
            return self._row(c, c.execute("SELECT * FROM genres WHERE id=?", (str(genre_id),)).fetchone())

    def add_alias(self, genre_id, alias, *, source="system"):
        alias = " ".join(str(alias or "").split()).strip()
        normalized = normalize_genre(alias)
        if not normalized:
            raise ValueError("Alias de gênero vazio.")
        with self.store._conn() as c:
            owner = c.execute("SELECT genre_id FROM genre_aliases WHERE normalized_alias=?", (normalized,)).fetchone()
            canonical = c.execute("SELECT id FROM genres WHERE normalized_name=?", (normalized,)).fetchone()
            if owner and str(owner["genre_id"]) != str(genre_id):
                return self._row(c, c.execute("SELECT * FROM genres WHERE id=?", (owner["genre_id"],)).fetchone())
            if canonical and str(canonical["id"]) != str(genre_id):
                return self._row(c, c.execute("SELECT * FROM genres WHERE id=?", (canonical["id"],)).fetchone())
            now = time.time()
            c.execute("""INSERT INTO genre_aliases(genre_id,alias,normalized_alias,source,created_at,updated_at)
                         VALUES(?,?,?,?,?,?) ON CONFLICT(normalized_alias) DO UPDATE SET alias=excluded.alias,updated_at=excluded.updated_at""",
                      (str(genre_id), alias, normalized, str(source or "system").casefold(), now, now))
            return self._row(c, c.execute("SELECT * FROM genres WHERE id=?", (genre_id,)).fetchone())

    def sync_anime(self, anime_id, names, *, source="local", replace_source=True):
        source = str(source or "local").casefold()
        names = list(dict.fromkeys(" ".join(str(v or "").split()).strip() for v in (names or []) if str(v or "").strip()))
        if replace_source:
            with self.store._conn() as c:
                c.execute("DELETE FROM anime_genres WHERE anime_id=? AND source=?", (int(anime_id), source))
        result = []
        for name in names:
            genre = self.resolve(name) or self.register(name, source=source)
            now = time.time()
            with self.store._conn() as c:
                c.execute("""INSERT OR IGNORE INTO anime_genres(anime_id,genre_id,source,created_at,updated_at)
                             VALUES(?,?,?,?,?)""", (int(anime_id), genre.id, source, now, now))
                c.execute("UPDATE anime_genres SET updated_at=? WHERE anime_id=? AND genre_id=? AND source=?",
                          (now, int(anime_id), genre.id, source))
            result.append(genre)
        return result

    def attach(self, anime_id, genre_id, *, source="user"):
        with self.store._conn() as c:
            if not c.execute("SELECT 1 FROM genres WHERE id=?", (genre_id,)).fetchone():
                raise ValueError("Gênero não encontrado.")
            now = time.time()
            c.execute("INSERT OR IGNORE INTO anime_genres(anime_id,genre_id,source,created_at,updated_at) VALUES(?,?,?,?,?)",
                      (int(anime_id), str(genre_id), str(source or "user").casefold(), now, now))
        return True

    def detach(self, anime_id, genre_id, *, source=None):
        with self.store._conn() as c:
            if source:
                c.execute("DELETE FROM anime_genres WHERE anime_id=? AND genre_id=? AND source=?",
                          (int(anime_id), str(genre_id), str(source).casefold()))
            else:
                c.execute("DELETE FROM anime_genres WHERE anime_id=? AND genre_id=?", (int(anime_id), str(genre_id)))
        return True

    def get_for_anime(self, anime_id):
        with self.store._conn() as c:
            rows = c.execute("""SELECT DISTINCT g.* FROM genres g JOIN anime_genres ag ON ag.genre_id=g.id
                                WHERE ag.anime_id=? ORDER BY g.normalized_name""", (int(anime_id),)).fetchall()
            return [self._row(c, row) for row in rows]

    def enrich_catalog(self, catalog):
        ids = [int(x["id"]) for x in catalog if x.get("id") is not None]
        mapping = {}
        if ids:
            marks = ",".join("?" for _ in ids)
            with self.store._conn() as c:
                rows = c.execute(f"""SELECT DISTINCT ag.anime_id,g.id,g.canonical_name,a.alias FROM anime_genres ag
                                     JOIN genres g ON g.id=ag.genre_id LEFT JOIN genre_aliases a ON a.genre_id=g.id
                                     WHERE ag.anime_id IN ({marks})
                                     ORDER BY g.normalized_name""", ids).fetchall()
                for row in rows:
                    mapping.setdefault(int(row["anime_id"]), []).append(dict(row))
        for item in catalog:
            genres = mapping.get(int(item["id"]), []) if item.get("id") is not None else []
            item["genre_ids"] = list(dict.fromkeys(g["id"] for g in genres))
            item["genres"] = list(dict.fromkeys(g["canonical_name"] for g in genres))
            item["genre_aliases"] = list(dict.fromkeys(g["alias"] for g in genres if g.get("alias")))
        return catalog

    def list_all(self, *, include_unused=True):
        with self.store._conn() as c:
            rows = c.execute("""SELECT g.id,g.canonical_name,g.normalized_name,g.source,g.is_system,g.is_custom,
                                       COUNT(DISTINCT ag.anime_id) count
                                FROM genres g LEFT JOIN anime_genres ag ON ag.genre_id=g.id
                                GROUP BY g.id ORDER BY g.normalized_name""").fetchall()
            result=[]
            for row in rows:
                if not include_unused and not row["count"]:
                    continue
                aliases=[r[0] for r in c.execute("SELECT alias FROM genre_aliases WHERE genre_id=? ORDER BY normalized_alias",(row["id"],))]
                result.append({"id":row["id"],"name":row["canonical_name"],"normalized_name":row["normalized_name"],
                               "source":row["source"],"is_system":bool(row["is_system"]),"is_custom":bool(row["is_custom"]),
                               "count":int(row["count"] or 0),"aliases":aliases})
            return result

    def search(self, query):
        normalized = normalize_genre(query)
        if not normalized:
            return self.list_all()
        return [x for x in self.list_all() if normalized in x["normalized_name"]
                or normalized in normalize_genre(x["name"])
                or any(normalized in normalize_genre(a) for a in x["aliases"])]

    def count_usage(self, genre_id):
        with self.store._conn() as c:
            return int(c.execute("SELECT COUNT(DISTINCT anime_id) FROM anime_genres WHERE genre_id=?", (str(genre_id),)).fetchone()[0] or 0)
