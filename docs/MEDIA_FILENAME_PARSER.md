# Kotlin media filename parser

## Ownership and boundaries

`MediaFilenameParser` is the single pure-JVM parser for native filename candidates; the Python parser remains separately active in the legacy runtime. It consumes only a supplied path string; it does not open files, rename or move them, access storage, perform network calls, identify an AniList record, or create a persisted identity. `LocalMediaIdentity` and `MediaTitleNormalizer` remain separate APIs. The active legacy Python `core/library_service.py` still calls `core/library_parser.py`; Python remains necessary until all runtime consumers have switched and behavioral parity has been validated.

## Legacy-supported patterns retained

The parser keeps the rules currently present in `core/library_parser.py` and the Kotlin implementation: explicit `SxxExx`, `Temp/Temporada xx Eyy`, `NxM`, episode/EP/Episode/Episódio markers, OVA/OAD/ONA/SP/Special/Extra, S00 special markers, Movie/Film, ABS/Absolute/Anime-EP absolute numbering, conservative numeric suffixes, folder season context, season conflicts, recording timestamps, and multi-episode uncertainty. Explicit markers take precedence over numeric suffix guesses. A number-only filename is not promoted to an episode.

Technical tokens are extracted using the normalizer's shared patterns (resolution, codec, HDR, source/container labels, audio and language/dub/sub tokens); they are reported separately from the candidate title. The source title, original leaf filename, base name, and extracted extension remain separate fields. Unknown extensions are reported as unsupported but do not prevent offline parsing of the text.

Episode markers and conservative suffix parsing accept decimal numbers using either a dot or comma, with 1–4 integer digits and 1–2 fractional digits. A decimal with excess precision must not be truncated to an integer or have its fractional digits parsed as a separate number. Absolute markers remain integer-based: `ABS1122.5` and `ANIME-EP1122.5` are unresolved invalid absolute numbers, not episode 1122 or 1122.5. Number-only stems such as `12.5.mkv` and `12,5.mkv` are never promoted to episode identity. A number-only decimal with more than two fractional digits (for example, `12.125.mkv`) additionally exposes `unsupported_numeric_suffix_decimal_precision` in `unresolved` and `ambiguities`; other numeric-only stems outside the supported recognition bounds also remain unpromoted. The allowlist remains identical to Python: `.mp4`, `.mkv`, `.webm`, `.avi`, `.mov`, `.m4v`, `.ts`, `.m2ts`, `.flv`, and `.wmv`.

## Result and ambiguity contract

`ParsedMediaName` exposes candidate title, nullable season/episode/absolute number, kind, source, overall and per-field confidence, evidence, unknown fields, supported-extension status, technical tokens, unresolved details, and ambiguity markers. Decimal episode syntax is bounded to 1–4 integer digits and 1–2 fractional digits, accepts dot or comma, and converts commas before numeric parsing; invalid, non-finite, absolute-decimal, and over-bound values are rejected conservatively instead of being truncated to a shorter episode number. Over-precision numeric-only decimal stems remain unpromoted and carry explicit unresolved/ambiguity evidence. No artificial season or episode zero is added when evidence is absent. A filename/title candidate is not a media identity and is not an external catalogue match.

## Validation and remaining boundary

`MediaFilenameParserTest.kt` contains the pre-existing parser coverage and was extended in the current PR's Prompt 02.2 increment with dedicated assertions for dot/comma decimals, special markers, invalid absolute decimals, repeated/chained separators, unsupported precision, numeric-only filename fallback, and related ambiguity fields. Those test sources have not been confirmed compiled or executed as a JVM test suite. The existing Python parser and tests are unchanged. The automatic `build_apk.yml` run `39154979582` was successful for the earlier SHA `69f35f0aa6ac16946b3ff690f0b29e1c7d8eb69c`; it supplies production build/compile and packaging evidence, not JVM behavioral-test evidence, and it predates the documentation reconciliation. No emulator tests or workflow triggers were added in this correction.

The parser is prepared for later scanner/library integration but is not yet the active parser for the Python-driven `LibraryService`. That runtime dependency remains explicit until a later migration prompt switches and validates the consuming flow.
