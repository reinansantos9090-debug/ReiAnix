"""Cliente AniList somente para metadados, com falha segura e cache de capas."""
from __future__ import annotations
import hashlib, json, logging, os, re, tempfile, threading, time, urllib.error, urllib.request
from html import unescape
from urllib.parse import urlencode

logger = logging.getLogger(__name__)

class AniListClient:
    endpoint='https://graphql.anilist.co'
    media_fields='''id title{romaji english native} synonyms description(asHtml:false) coverImage{extraLarge large} bannerImage genres seasonYear season status episodes duration averageScore format studios(isMain:true){nodes{name}}'''
    query=f'''query($search:String){{Page(perPage:10){{media(search:$search,type:ANIME){{{media_fields}}}}}}}'''
    by_id_query=f'''query($id:Int){{Media(id:$id,type:ANIME){{{media_fields}}}}}'''
    def __init__(self, cache_dir):
        self.cache_dir = cache_dir
        self._rate_lock = threading.RLock()
        self._next_request_at = 0.0
        self._min_interval = 0.7
        self._rate_limit = None
        self._rate_remaining = None
        self._rate_reset = None
        self._transport_backoff_until = 0.0
        self._transport_failures = 0
        self._last_request_status = "idle"
        self._translation_lock = threading.RLock()
        self._translation_cache_path = os.path.join(self.cache_dir, "anilist_description_ptbr.json")
        self._translation_cache: dict[str, dict] | None = None
        self._translation_inflight: dict[str, threading.Event] = {}
        self._diagnostic_recorder = None

    def _trim_translation_cache(self) -> dict:
        cache = self._translation_cache or {}
        if len(cache) <= self.MAX_TRANSLATION_CACHE_ENTRIES:
            return cache
        ranked = sorted(
            cache.items(),
            key=lambda item: float((item[1] or {}).get("updated_at") or 0) if isinstance(item[1], dict) else 0,
            reverse=True,
        )
        self._translation_cache = dict(ranked[:self.MAX_TRANSLATION_CACHE_ENTRIES])
        return self._translation_cache

    def _load_translation_cache(self) -> dict:
        if self._translation_cache is not None:
            return self._translation_cache
        try:
            with open(self._translation_cache_path, "r", encoding="utf-8") as handle:
                value = json.load(handle)
            self._translation_cache = value if isinstance(value, dict) else {}
            self._trim_translation_cache()
        except (OSError, ValueError, TypeError):
            self._translation_cache = {}
        return self._translation_cache

    def _save_translation_cache(self) -> None:
        cache = self._trim_translation_cache()
        os.makedirs(self.cache_dir, exist_ok=True)
        fd, temporary = tempfile.mkstemp(prefix=".anilist-ptbr-", suffix=".tmp", dir=self.cache_dir)
        try:
            with os.fdopen(fd, "w", encoding="utf-8") as handle:
                json.dump(cache, handle, ensure_ascii=False, sort_keys=True)
            os.replace(temporary, self._translation_cache_path)
        except OSError:
            try:
                os.unlink(temporary)
            except OSError:
                pass

    TRANSLATION_TARGET_LANGUAGE = "pt-BR"
    MAX_TRANSLATION_CACHE_ENTRIES = 4096
    TRANSLATION_MAX_BYTES = 480
    TRANSLATION_RETRIES = 3

    _LANGUAGE_PROFILES = {
        "en": {
            "the", "this", "that", "with", "from", "when", "where", "after",
            "before", "into", "about", "story", "follows", "young", "girl",
            "boy", "people", "their", "they", "is", "are", "has", "have",
            "for", "and", "but", "not", "who", "his", "her", "their",
        },
        "pt": {
            "que", "uma", "um", "não", "com", "quando", "através", "está",
            "são", "dos", "das", "para", "por", "como", "história", "segue",
            "jovem", "garota", "garoto", "pessoas", "seus", "suas", "eles",
            "elas", "tem", "têm", "isso", "esta", "este", "depois", "antes",
            "entre", "sobre", "sem", "mais", "também", "porque", "onde",
        },
        "es": {
            "que", "una", "uno", "con", "cuando", "desde", "después",
            "antes", "sobre", "para", "por", "como", "historia", "sigue",
            "joven", "chica", "chico", "personas", "sus", "ellos", "ellas",
            "tiene", "está", "son", "esta", "este", "entre", "pero",
        },
        "fr": {
            "que", "une", "un", "avec", "quand", "dans", "après", "avant",
            "pour", "par", "comme", "histoire", "suit", "jeune", "fille",
            "garçon", "personnes", "leurs", "ils", "elles", "est", "sont",
            "avoir", "cette", "ce", "mais", "entre", "sans",
        },
        "it": {
            "che", "una", "uno", "con", "quando", "dopo", "prima", "per",
            "come", "storia", "segue", "giovane", "ragazza", "ragazzo",
            "persone", "loro", "sono", "questa", "questo", "avere", "ma",
            "tra", "senza", "dove", "anche",
        },
        "de": {
            "der", "die", "das", "ein", "eine", "mit", "wenn", "nach",
            "vor", "für", "von", "wie", "geschichte", "folgt", "junge",
            "mädchen", "junge", "menschen", "ihre", "sie", "ist", "sind",
            "haben", "dies", "diese", "aber", "ohne",
        },
        "nl": {
            "de", "het", "een", "met", "wanneer", "na", "voor", "voor", "van",
            "zoals", "verhaal", "volgt", "jonge", "meisje", "jongen", "mensen",
            "hun", "zij", "is", "zijn", "heeft", "maar", "zonder",
        },
        "pl": {
            "że", "jest", "są", "z", "do", "dla", "kiedy", "po", "przed",
            "jak", "historia", "śledzi", "młody", "dziewczyna", "chłopak",
            "ludzie", "ich", "ma", "ten", "ta", "ale", "bez",
        },
        "tr": {
            "bir", "ve", "ile", "ne", "zaman", "sonra", "önce", "için",
            "gibi", "hikaye", "takip", "genç", "kız", "erkek", "insanlar",
            "onların", "onlar", "olan", "ama", "bu",
        },
    }

    _LANGUAGE_ALIASES = {
        "pt": "pt", "pt-br": "pt", "pt_pt": "pt",
        "en": "en", "en-us": "en", "en-gb": "en",
        "es": "es", "fr": "fr", "it": "it", "de": "de", "nl": "nl",
        "pl": "pl", "tr": "tr", "ru": "ru", "ja": "ja", "zh": "zh-CN",
        "zh-cn": "zh-CN", "ko": "ko", "ar": "ar", "el": "el",
    }

    def set_diagnostic_recorder(self, recorder) -> None:
        self._diagnostic_recorder = recorder if callable(recorder) else None

    def _record_translation_event(
        self,
        name: str,
        *,
        request_id=None,
        source_language=None,
        result=None,
        error=None,
    ) -> None:
        recorder = getattr(self, "_diagnostic_recorder", None)
        if not callable(recorder):
            return
        try:
            recorder(
                name,
                request_id=request_id,
                source=source_language,
                result=result,
                error=error,
            )
            alias = {
                "TRANSLATION_STARTED": "DESCRIPTION_TRANSLATION_START",
                "TRANSLATION_SUCCEEDED": "DESCRIPTION_TRANSLATION_SUCCESS",
                "TRANSLATION_UNAVAILABLE": "TRANSLATION_FALLBACK_ORIGINAL",
            }.get(name)
            if alias:
                recorder(
                    alias,
                    request_id=request_id,
                    source=source_language,
                    result=result,
                    error=error,
                )
        except TypeError:
            logger.debug("Diagnostic recorder rejected translation event %s", name, exc_info=True)

    @staticmethod
    def normalize_description(description: str) -> str:
        """Normalize AniList description HTML while preserving paragraph structure."""
        value = str(description or "").replace("\r\n", "\n").replace("\r", "\n")
        value = unescape(value)
        value = re.sub(r"(?is)<br\s*/?>", "\n\n", value)
        value = re.sub(r"(?is)</p>\s*<p\b[^>]*>", "\n\n", value)
        value = re.sub(
            r"(?is)</?(?:p|div|li|blockquote|h[1-6])\b[^>]*>",
            "\n\n",
            value,
        )
        value = re.sub(r"(?is)<[^>]+>", "", value)
        value = unescape(value)

        paragraphs = []
        for part in re.split(r"\n\s*\n+", value):
            cleaned = " ".join(part.split())
            if cleaned:
                paragraphs.append(cleaned)
        return "\n\n".join(paragraphs).strip()

    @staticmethod
    def _script_language(text: str) -> str | None:
        for char in str(text or ""):
            code = ord(char)
            if 0x3040 <= code <= 0x30FF:
                return "ja"
            if 0xAC00 <= code <= 0xD7AF:
                return "ko"
            if 0x0400 <= code <= 0x04FF:
                return "ru"
            if 0x0600 <= code <= 0x06FF:
                return "ar"
            if 0x0370 <= code <= 0x03FF:
                return "el"
        if any(0x4E00 <= ord(char) <= 0x9FFF for char in str(text or "")):
            return "zh-CN"
        return None

    @classmethod
    def detect_description_language(cls, text: str) -> tuple[str, float]:
        """Return a conservative source-language guess and confidence.

        Script-based languages are deterministic. Latin-script languages require
        several independent lexical signals and a score margin to avoid the
        fragile two-word heuristics used by the previous implementation.
        """
        normalized = cls.normalize_description(text)
        if not normalized:
            return "unknown", 0.0

        script_language = cls._script_language(normalized)
        if script_language:
            return script_language, 0.99

        tokens = re.findall(r"[^\W\d_]+", normalized.casefold(), flags=re.UNICODE)
        if not tokens:
            return "unknown", 0.0

        scores = {}
        unique_tokens = set(tokens)
        for language, profile in cls._LANGUAGE_PROFILES.items():
            hits = len(unique_tokens & profile)
            weighted = sum(1 for token in tokens if token in profile)
            accent_bonus = 0
            if language == "pt":
                accent_bonus = sum(1 for token in tokens if any(ch in token for ch in "ãõçáéíóúâêô"))
            scores[language] = (hits, weighted + accent_bonus)

        ranked = sorted(scores.items(), key=lambda item: (item[1][0], item[1][1]), reverse=True)
        best_language, (best_hits, best_weighted) = ranked[0]
        second_hits, second_weighted = ranked[1][1]

        token_count = len(tokens)
        strong_enough = best_hits >= 3 or (best_hits >= 2 and token_count <= 6 and best_weighted >= 3)
        margin = best_hits - second_hits
        if not strong_enough or (margin < 1 and best_weighted - second_weighted < 2):
            return "unknown", 0.0

        confidence = min(0.99, 0.45 + (best_hits / max(4, token_count)) * 0.5 + min(0.15, max(0, margin) * 0.05))
        return best_language, round(confidence, 3)

    @classmethod
    def _normalize_source_language(cls, value: str | None) -> str:
        raw = str(value or "").strip().casefold().replace("_", "-")
        return cls._LANGUAGE_ALIASES.get(raw, raw if raw in {"ru", "ja", "zh-CN", "ko", "ar", "el"} else "unknown")

    @classmethod
    def translation_cache_key(cls, description: str, source_language: str | None = None) -> str:
        original = cls.normalize_description(description)
        source = cls._normalize_source_language(source_language)
        if source == "unknown":
            source, _ = cls.detect_description_language(original)
        material = f"{source}|{cls.TRANSLATION_TARGET_LANGUAGE}|{original}"
        return hashlib.sha256(material.encode("utf-8")).hexdigest()

    @staticmethod
    def _looks_like_error_translation(text: str) -> bool:
        upper = str(text or "").strip().upper()
        if not upper:
            return True
        error_markers = (
            "MYMEMORY WARNING",
            "MYMEMORY ERROR",
            "RATE LIMIT",
            "TOO MANY REQUESTS",
            "SERVICE UNAVAILABLE",
            "INTERNAL SERVER ERROR",
            "BAD REQUEST",
            "TRANSLATION ERROR",
            "ERROR:",
        )
        return any(marker in upper for marker in error_markers)

    @classmethod
    def _is_valid_translation(cls, source_text: str, translated_text: str) -> bool:
        source = cls.normalize_description(source_text)
        translated = cls.normalize_description(translated_text)
        if not source or not translated:
            return False
        if translated.casefold() == source.casefold():
            return False
        if re.search(r"<(?:html|body|script|json)\b", str(translated_text or ""), flags=re.IGNORECASE):
            return False
        raw = str(translated_text or "").strip()
        if (raw.startswith("{") and raw.endswith("}")) or (raw.startswith("[") and raw.endswith("]")):
            try:
                json.loads(raw)
                return False
            except (TypeError, ValueError):
                pass
        if cls._looks_like_error_translation(translated):
            return False
        source_compact = len(re.sub(r"\s+", "", source))
        translated_compact = len(re.sub(r"\s+", "", translated))
        if source_compact >= 80 and translated_compact < max(8, int(source_compact * 0.08)):
            return False
        if translated_compact > max(120, source_compact * 4):
            return False
        return True

    @classmethod
    def _is_pt_br_description(cls, text: str) -> bool:
        normalized = cls.normalize_description(text)
        if not normalized:
            return False
        language, _ = cls.detect_description_language(normalized)
        if language == "pt":
            return True

        tokens = re.findall(r"[^W\d_]+", normalized.casefold(), flags=re.UNICODE)
        pt_profile = cls._LANGUAGE_PROFILES["pt"]
        pt_hits = len(set(tokens) & pt_profile)
        if pt_hits >= 2:
            return True

        # Very short Portuguese descriptions may be classified as unknown by
        # the conservative detector. "ç", "ã" and "õ" are strong PT signals
        # that do not appear in the common Spanish/English profiles.
        return any(char in normalized.casefold() for char in "çãõ")

    @staticmethod
    def _paragraph_chunks(text: str, max_bytes: int = 480) -> list[list[str]]:
        sections = []
        for paragraph in str(text or "").split("\n\n"):
            words = paragraph.split()
            if not words:
                continue
            chunks = []
            current = []
            current_bytes = 0
            for word in words:
                encoded_size = len(word.encode("utf-8"))
                extra = encoded_size + (1 if current else 0)
                if current and current_bytes + extra > max_bytes:
                    chunks.append(" ".join(current))
                    current = [word]
                    current_bytes = encoded_size
                else:
                    current.append(word)
                    current_bytes += extra
            if current:
                chunks.append(" ".join(current))
            if chunks:
                sections.append(chunks)
        return sections

    @classmethod
    def _translation_chunks(cls, text: str, max_bytes: int = 480) -> list[str]:
        """Compatibility helper retaining the legacy flat chunk contract."""
        return [chunk for section in cls._paragraph_chunks(text, max_bytes) for chunk in section]

    def get_cached_description_pt_br(
        self,
        description: str,
        source_language: str | None = None,
    ) -> str | None:
        original = self.normalize_description(description)
        if not original:
            return None
        source = self._normalize_source_language(source_language)
        if source == "unknown":
            source, _ = self.detect_description_language(original)
        if source == "pt":
            return original
        key = self.translation_cache_key(original, source)
        with self._translation_lock:
            entry = self._load_translation_cache().get(key)
        if isinstance(entry, dict) and entry.get("status") == "ok":
            translated = self.normalize_description(entry.get("translated") or "")
            if self._is_valid_translation(original, translated) and self._is_pt_br_description(translated):
                return translated
        return None

    def _translate_chunk_to_pt_br(self, text: str, source_language: str = "en") -> str | None:
        source = self._normalize_source_language(source_language)
        if source == "unknown" or source == "pt":
            return self.normalize_description(text) if source == "pt" else None
        for attempt in range(self.TRANSLATION_RETRIES):
            query = urlencode({
                "q": text,
                "langpair": f"{source}|{self.TRANSLATION_TARGET_LANGUAGE}",
                "mt": "1",
            })
            request = urllib.request.Request(
                "https://api.mymemory.translated.net/get?" + query,
                headers={"User-Agent": "ReiAnix/1.0 (personal-use)"},
            )
            try:
                with urllib.request.urlopen(request, timeout=12) as response:
                    status = getattr(response, "status", None)
                    payload = json.loads(response.read().decode("utf-8"))
                if status is not None and int(status) >= 400:
                    if int(status) == 429 or int(status) >= 500:
                        raise urllib.error.HTTPError(request.full_url, int(status), "translation service", None, None)
                    return None
                if not isinstance(payload, dict):
                    return None
                response_status = payload.get("responseStatus")
                try:
                    response_status_code = int(response_status) if response_status is not None else 200
                except (TypeError, ValueError):
                    response_status_code = 0
                if response_status_code != 200:
                    if response_status_code == 429 or response_status_code >= 500:
                        raise urllib.error.HTTPError(
                            request.full_url,
                            response_status_code,
                            str(payload.get("responseDetails") or "translation service error"),
                            None,
                            None,
                        )
                    return None
                raw_translated = (payload.get("responseData") or {}).get("translatedText")
                if not isinstance(raw_translated, str):
                    return None
                translated = self.normalize_description(raw_translated)
                if self._is_valid_translation(text, translated):
                    return translated
                return None
            except urllib.error.HTTPError as exc:
                transient = exc.code == 429 or exc.code >= 500
                if not transient or attempt >= self.TRANSLATION_RETRIES - 1:
                    logger.warning("AniList PT-BR translation request failed: HTTP %s", exc.code)
                    return None
                retry_after = None
                try:
                    retry_after = float(exc.headers.get("Retry-After")) if exc.headers else None
                except (TypeError, ValueError, AttributeError):
                    retry_after = None
                delay = min(8.0, retry_after if retry_after is not None else 0.5 * (2 ** attempt))
                time.sleep(max(0.0, delay))
            except (urllib.error.URLError, TimeoutError, OSError, ValueError, TypeError, json.JSONDecodeError) as exc:
                if attempt >= self.TRANSLATION_RETRIES - 1:
                    logger.warning("AniList PT-BR translation request failed: %s", exc)
                    return None
                time.sleep(min(8.0, 0.5 * (2 ** attempt)))
        return None

    def localize_description_to_pt_br(
        self,
        description: str,
        source_language: str | None = None,
        *,
        request_id=None,
    ) -> str | None:
        original = self.normalize_description(description)
        if not original:
            self._record_translation_event(
                "TRANSLATION_FALLBACK_ORIGINAL",
                request_id=request_id,
                result="empty",
            )
            return original

        source = self._normalize_source_language(source_language)
        if source == "unknown":
            source, confidence = self.detect_description_language(original)
            if source == "unknown":
                self._record_translation_event(
                    "TRANSLATION_UNAVAILABLE",
                    request_id=request_id,
                    result="source_language_unknown",
                )
                return None
        else:
            confidence = 1.0

        if source == "pt":
            key = self.translation_cache_key(original, source)
            with self._translation_lock:
                cache = self._load_translation_cache()
                cache[key] = {
                    "status": "not_needed",
                    "translated": original,
                    "source_language": "pt",
                    "target_language": self.TRANSLATION_TARGET_LANGUAGE,
                    "updated_at": time.time(),
                }
                self._save_translation_cache()
            self._record_translation_event(
                "TRANSLATION_SUCCEEDED",
                request_id=request_id,
                source_language=source,
                result="not_needed",
            )
            return original

        cached = self.get_cached_description_pt_br(original, source)
        if cached:
            self._record_translation_event(
                "TRANSLATION_CACHE_HIT",
                request_id=request_id,
                source_language=source,
                result="ok",
            )
            return cached

        key = self.translation_cache_key(original, source)
        with self._translation_lock:
            cache = self._load_translation_cache()
            entry = cache.get(key)
            if isinstance(entry, dict) and entry.get("status") == "failed":
                try:
                    retry_after = float(entry.get("retry_after") or 0)
                except (TypeError, ValueError):
                    retry_after = 0
                if time.time() < retry_after:
                    self._record_translation_event(
                        "TRANSLATION_UNAVAILABLE",
                        request_id=request_id,
                        source_language=source,
                        result="cached_failure",
                    )
                    return None
            inflight = getattr(self, "_translation_inflight", {})
            if not hasattr(self, "_translation_inflight"):
                self._translation_inflight = inflight
            event = inflight.get(key)
            if event is None:
                event = threading.Event()
                inflight[key] = event
                owner = True
            else:
                owner = False

        if not owner:
            event.wait(timeout=90.0)
            cached = self.get_cached_description_pt_br(original, source)
            if cached:
                self._record_translation_event(
                    "TRANSLATION_CACHE_HIT",
                    request_id=request_id,
                    source_language=source,
                    result="inflight_result",
                )
                return cached
            self._record_translation_event(
                "TRANSLATION_UNAVAILABLE",
                request_id=request_id,
                source_language=source,
                result="inflight_failed",
            )
            return None

        started = time.monotonic()
        self._record_translation_event(
            "TRANSLATION_REQUESTED",
            request_id=request_id,
            source_language=source,
            result="requested",
        )
        self._record_translation_event(
            "TRANSLATION_STARTED",
            request_id=request_id,
            source_language=source,
            result=f"confidence={confidence:.3f}",
        )
        try:
            translated_sections = []
            for section in self._paragraph_chunks(original, self.TRANSLATION_MAX_BYTES):
                section_translations = []
                for chunk in section:
                    translated = self._translate_chunk_to_pt_br(chunk, source)
                    if not translated or not self._is_valid_translation(chunk, translated):
                        with self._translation_lock:
                            cache = self._load_translation_cache()
                            cache[key] = {
                                "status": "failed",
                                "retry_after": time.time() + 60 * 60,
                                "source_language": source,
                                "target_language": self.TRANSLATION_TARGET_LANGUAGE,
                                "updated_at": time.time(),
                            }
                            self._save_translation_cache()
                        self._record_translation_event(
                            "TRANSLATION_FAILED",
                            request_id=request_id,
                            source_language=source,
                            result="invalid_response",
                        )
                        self._record_translation_event(
                            "TRANSLATION_UNAVAILABLE",
                            request_id=request_id,
                            source_language=source,
                            result="translation_failed",
                        )
                        return None
                    section_translations.append(translated)
                translated_sections.append(" ".join(section_translations))
            localized = "\n\n".join(translated_sections).strip()
            if not self._is_valid_translation(original, localized) or not self._is_pt_br_description(localized):
                with self._translation_lock:
                    cache = self._load_translation_cache()
                    cache[key] = {
                        "status": "failed",
                        "retry_after": time.time() + 60 * 60,
                        "source_language": source,
                        "target_language": self.TRANSLATION_TARGET_LANGUAGE,
                        "updated_at": time.time(),
                    }
                    self._save_translation_cache()
                self._record_translation_event(
                    "TRANSLATION_FAILED",
                    request_id=request_id,
                    source_language=source,
                    result="validation_failed",
                )
                self._record_translation_event(
                    "TRANSLATION_UNAVAILABLE",
                    request_id=request_id,
                    source_language=source,
                    result="translation_invalid",
                )
                return None

            with self._translation_lock:
                cache = self._load_translation_cache()
                cache[key] = {
                    "status": "ok",
                    "translated": localized,
                    "source_language": source,
                    "target_language": self.TRANSLATION_TARGET_LANGUAGE,
                    "updated_at": time.time(),
                    "duration_ms": int((time.monotonic() - started) * 1000),
                }
                self._save_translation_cache()
            self._record_translation_event(
                "TRANSLATION_SUCCEEDED",
                request_id=request_id,
                source_language=source,
                result="ok",
            )
            return localized
        finally:
            with self._translation_lock:
                inflight = getattr(self, "_translation_inflight", {})
                active_event = inflight.pop(key, None)
                if active_event is not None:
                    active_event.set()
    @staticmethod
    def _header(headers, name):
        if headers is None: return None
        try: return headers.get(name)
        except AttributeError:
            try: return headers.get(name.lower())
            except AttributeError: return None

    def _pace_request(self):
        with self._rate_lock:
            now = time.monotonic()
            backoff = max(0.0, self._transport_backoff_until - now)
            delay = max(backoff, self._next_request_at - now)
        if delay:
            time.sleep(delay)
        with self._rate_lock:
            self._next_request_at = time.monotonic() + self._min_interval

    def _transport_failure(self):
        with self._rate_lock:
            self._transport_failures += 1
            delay = min(300.0, 30.0 * (2 ** (self._transport_failures - 1)))
            self._transport_backoff_until = time.monotonic() + delay
        logger.warning("AniList transporte indisponível; backoff de %.1fs aplicado.", delay)

    def _transport_success(self):
        with self._rate_lock:
            self._transport_failures = 0
            self._transport_backoff_until = 0.0

    def _observe_rate_headers(self, headers):
        limit_raw = self._header(headers, 'X-RateLimit-Limit')
        remaining_raw = self._header(headers, 'X-RateLimit-Remaining')
        reset_raw = self._header(headers, 'X-RateLimit-Reset')
        try: limit = int(limit_raw) if limit_raw is not None else None
        except (TypeError, ValueError): limit = None
        try: remaining = int(remaining_raw) if remaining_raw is not None else None
        except (TypeError, ValueError): remaining = None
        try: reset = float(reset_raw) if reset_raw is not None else None
        except (TypeError, ValueError): reset = None
        with self._rate_lock:
            if limit and limit > 0:
                self._rate_limit = limit
                self._min_interval = max(0.7, min(3.0, 60.0 / float(limit)))
            if remaining is not None: self._rate_remaining = remaining
            if reset is not None: self._rate_reset = reset
            if remaining is not None and remaining <= 2 and reset and reset > time.time():
                window = reset - time.time()
                self._min_interval = max(self._min_interval, min(5.0, window / max(1, remaining + 1)))

    def _retry_delay_from_headers(self, headers):
        retry_after = self._header(headers, 'Retry-After')
        try:
            if retry_after is not None: return max(0.0, min(float(retry_after), 120.0))
        except (TypeError, ValueError): pass
        reset_raw = self._header(headers, 'X-RateLimit-Reset')
        try:
            if reset_raw is not None: return max(0.0, min(float(reset_raw) - time.time(), 120.0))
        except (TypeError, ValueError): pass
        return 0.0
    def _request(self, query, variables):
        self._last_request_status = "pending"
        data = json.dumps({'query': query, 'variables': variables}).encode()
        req = urllib.request.Request(self.endpoint, data=data, headers={'Content-Type':'application/json','Accept':'application/json','User-Agent':'ReiAnix/1.0'})
        with self._rate_lock:
            if time.monotonic() < self._transport_backoff_until:
                logger.info("AniList request skipped during transport backoff.")
                self._last_request_status = "network_error"
                return None
        self._pace_request()
        try:
            with urllib.request.urlopen(req, timeout=10) as response:
                raw = response.read()
                self._observe_rate_headers(getattr(response, 'headers', None))
            self._transport_success()
            payload = json.loads(raw)
        except urllib.error.HTTPError as exc:
            self._observe_rate_headers(getattr(exc, 'headers', None))
            if exc.code == 429:
                delay = self._retry_delay_from_headers(getattr(exc, 'headers', None))
                if delay > 0:
                    logger.warning('AniList atingiu rate limit; aguardando %.1fs antes de uma nova tentativa.', delay)
                    time.sleep(delay)
                    with self._rate_lock:
                        self._next_request_at = time.monotonic() + self._min_interval
                    try:
                        with urllib.request.urlopen(req, timeout=10) as retry_response:
                            raw = retry_response.read()
                            self._observe_rate_headers(getattr(retry_response, 'headers', None))
                        payload = json.loads(raw)
                    except (urllib.error.URLError, TimeoutError, json.JSONDecodeError, OSError) as retry_exc:
                        logger.warning('AniList indisponível após rate limit: %s', retry_exc)
                        self._last_request_status = "rate_limited"
                        return None
                    except Exception as retry_exc:
                        logger.warning('Falha inesperada após rate limit do AniList: %s', retry_exc)
                        self._last_request_status = "network_error"
                        return None
                else:
                    logger.warning('AniList retornou HTTP 429 sem um atraso utilizável.')
                    self._last_request_status = "rate_limited"
                    return None
            else:
                self._last_request_status = "rate_limited" if exc.code == 429 else ("network_error" if exc.code >= 500 else "http_error")
                logger.warning('AniList indisponível: HTTP %s', exc.code)
                if exc.code >= 500:
                    self._transport_failure()
                return None
        except (urllib.error.URLError, TimeoutError, OSError) as exc:
            self._transport_failure()
            self._last_request_status = "network_error"
            logger.warning('AniList indisponível: %s', exc)
            return None
        except json.JSONDecodeError as exc:
            self._last_request_status = "invalid_response"
            logger.warning('AniList retornou JSON inválido: %s', exc)
            return None
        except Exception as exc:
            self._last_request_status = "network_error"
            logger.warning('Falha inesperada na comunicação com AniList: %s', exc)
            return None
        if not isinstance(payload, dict):
            self._last_request_status = "invalid_response"
            logger.warning("AniList retornou uma resposta inválida.")
            return None
        if payload.get('errors'):
            self._last_request_status = "invalid_response"
            logger.warning("AniList retornou erro GraphQL: %s", payload.get('errors'))
            return None
        data = payload.get('data')
        if not isinstance(data, dict):
            self._last_request_status = "invalid_response"
            logger.warning("AniList não retornou dados GraphQL válidos.")
            return None
        self._last_request_status = "ok"
        return data
    @property
    def last_request_status(self):
        return self._last_request_status
    def search_detailed(self, title):
        data = self._request(self.query, {'search': title})
        results = ((data or {}).get('Page') or {}).get('media') or []
        if self._last_request_status == 'pending':
            self._last_request_status = 'ok'
        return {"status": "ok" if self._last_request_status == "ok" else self._last_request_status,
                "results": [item for item in results if isinstance(item, dict)]}
    def search(self,title):
        result = self.search_detailed(title)
        if isinstance(result, dict):
            self._last_request_status = str(result.get("status") or self._last_request_status or "invalid_response")
            return result.get("results") or []
        self._last_request_status = "invalid_response"
        return []
    def by_id(self, anilist_id):
        data=self._request(self.by_id_query, {'id':anilist_id})
        return (data or {}).get('Media')
    def metadata(self,title,chosen_id=None):
        media=self.by_id(chosen_id) if chosen_id else None
        if not media:
            results=self.search(title)
            if chosen_id is not None:
                media = next((m for m in results if m.get('id') == chosen_id), None)
            if media is None and chosen_id is None:
                media = results[0] if results else None
        return self.metadata_from_media(title, media)
    def metadata_from_media(self, title, media, *, localize_description=False):
        if not media: return {'title':title,'genres':'[]'}
        cover=(media.get('coverImage') or {}).get('extraLarge') or (media.get('coverImage') or {}).get('large') or ''
        # ArtworkEngine owns persistent cover downloads. Keep cache_cover() as a
        # compatibility API for older callers/tests, but do not download here.
        cache=''
        t=media.get('title') or {}; studios=((media.get('studios') or {}).get('nodes') or [])
        studios = [studio for studio in studios if isinstance(studio, dict)]
        original_description = self.normalize_description(media.get('description') or '')
        source_language, _ = self.detect_description_language(original_description)
        description = None
        if original_description:
            if source_language == "pt":
                description = original_description
            else:
                cached_localized = self.get_cached_description_pt_br(
                    original_description,
                    source_language if source_language != "unknown" else None,
                )
                if cached_localized:
                    description = cached_localized
                elif localize_description:
                    description = self.localize_description_to_pt_br(
                        original_description,
                        source_language=source_language if source_language != "unknown" else None,
                    )
        metadata = {'title':t.get('english') or t.get('romaji') or title,'romaji':t.get('romaji'),'english':t.get('english'),'native':t.get('native'),'aliases':json.dumps(media.get('synonyms') or [],ensure_ascii=False),'description':description,'cover_url':cover,'cover_cache':cache,'banner_url':media.get('bannerImage') or '','genres':json.dumps(media.get('genres') or [],ensure_ascii=False),'year':media.get('seasonYear'),'season':media.get('season'),'status':media.get('status'),'episodes_count':media.get('episodes'),'duration':media.get('duration'),'score':media.get('averageScore'),'format':media.get('format'),'studio':', '.join(x.get('name','') for x in studios)}
        if original_description:
            metadata['description_original'] = original_description
        if media.get('id') is not None:
            metadata['anilist_id'] = media['id']
        return metadata
    def cache_cover(self,url):
        name=hashlib.sha256(url.encode()).hexdigest()+os.path.splitext(url.split('?')[0])[1][:5]
        target=os.path.join(self.cache_dir,name)
        if os.path.isfile(target) and os.path.getsize(target) > 0: return target
        descriptor, temporary = tempfile.mkstemp(prefix=f".{name}.", suffix=".tmp", dir=self.cache_dir)
        try:
            with os.fdopen(descriptor, 'wb') as f, urllib.request.urlopen(url,timeout=15) as r:
                payload = r.read()
                if not payload:
                    raise ValueError("A capa AniList está vazia.")
                f.write(payload)
                f.flush()
                os.fsync(f.fileno())
            os.replace(temporary, target)
            return target
        except Exception as exc:
            try: os.unlink(temporary)
            except OSError as cleanup_error: logger.warning("Não foi possível remover capa temporária: %s", cleanup_error)
            logger.warning("Não foi possível baixar capa AniList: %s", exc)
            return ''
