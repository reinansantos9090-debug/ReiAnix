# Kotlin media filename parser

## Ownership and boundaries

`MediaFilenameParser` is the single pure-JVM parser for local filename candidates. It consumes only a supplied path string; it does not open files, rename or move them, access storage, perform network calls, identify an AniList record, or create a persisted identity. `LocalMediaIdentity` and `MediaTitleNormalizer` remain separate APIs. The existing Python `core/library_parser.py` is preserved because `core/library_service.py` still calls it; scanner/library cutover is intentionally outside Prompt 5.

## Legacy-supported patterns retained

The parser keeps the rules currently present in `core/library_parser.py` and the Kotlin implementation: explicit `SxxExx`, `Temp/Temporada xx Eyy`, `NxM`, episode/EP/Episode/Episódio markers, OVA/OAD/ONA/SP/Special/Extra, S00 special markers, Movie/Film, ABS/Absolute/Anime-EP absolute numbering, conservative numeric suffixes, folder season context, season conflicts, recording timestamps, and multi-episode uncertainty. Explicit markers take precedence over numeric suffix guesses. A number-only filename is not promoted to an episode.

Technical tokens are extracted using the normalizer's shared patterns (resolution, codec, HDR, source/container labels, audio and language/dub/sub tokens); they are reported separately from the candidate title. The source title, original leaf filename, base name, and extracted extension remain separate fields. Unknown extensions are reported as unsupported but do not prevent offline parsing of the text.

The allowlist remains identical to Python: `.mp4`, `.mkv`, `.webm`, `.avi`, `.mov`, `.m4v`, `.ts`, `.m2ts`, `.flv`, and `.wmv`.

## Result and ambiguity contract

`ParsedMediaName` exposes candidate title, nullable season/episode/absolute number, kind, source, overall and per-field confidence, evidence, unknown fields, supported-extension status, technical tokens, unresolved details, and ambiguity markers. No artificial season or episode zero is added when evidence is absent. A filename/title candidate is not a media identity and is not an external catalogue match.

## Validation and remaining boundary

`MediaFilenameParserTest` contains pure Kotlin/JVM cases for simple titles, episode lengths and marker variants, season context/conflict, release groups and technical tokens, Unicode, supported and unsupported extensions, numeric false positives, specials, movies, absolute numbering, and ambiguous/malformed inputs. The existing Python parser and its tests are unchanged. Kotlin JVM test execution/build must be confirmed by the existing repository workflow or a configured Gradle environment; this change does not add emulator tests or alter workflow triggers.

The parser is prepared for later scanner/library integration but is not yet the active parser for the Python-driven `LibraryService`. That runtime dependency remains explicit until a later migration prompt switches and validates the consuming flow.
