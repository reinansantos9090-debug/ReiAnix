# ReiAnix — Regression Baseline

Baseline commit: c97e9f0cd4d103e117eb51299b1bd721de756786
Diagnostic branch: diagnostic/regression-baseline

## Scope
Runtime regression baseline and validation ledger. Structural fixes recorded below are already present in the current main branch; emulator/device execution remains a separate runtime evidence source.

## Architecture found
Python/Flet -> AndroidBridge -> MainActivity -> Android APIs -> NativeMailbox -> Python polling -> LibraryService/LibraryStore -> SQLite/catalog -> Home/Organize/Details/Settings.

The inspected HEAD contains NativeMailbox, NativeIndex, LibraryStore, LibraryService, MainActivity, NativePlayerActivity, MediaStoreScanner, SafScanner, BroadStorageScanner, Media3 and an existing ArtworkEngine.

## Regression matrix

| ID | Problem | Current evidence | State | Area owner |
|---|---|---|---|---|
| RF-001 | Android Back | MainActivity uses one OnBackPressedCallback and forwards one physical Back to Flet's navigationChannel.popRoute(); Python consumes page.on_view_pop with navigate_back(). NativeMailbox is not the Back transport. | CORRIGIDO / ANÁLISE ESTÁTICA | navigation / lifecycle |
| RF-002 | Library reload | MainActivity.onResume now publishes a lifecycle signal and only requests discovery on first startup or an actual permission transition; normal player/background returns do not issue a scan command. | CORRIGIDO / ANÁLISE ESTÁTICA | lifecycle / scanning |
| RF-003 | Organize click | Organize category/genre cards now wrap async callbacks through a task on the running event loop instead of returning coroutine objects to Flet. | CORRIGIDO / ANÁLISE ESTÁTICA | organize navigation |
| RF-004 | Genre limitation | Local GenreClassifier has 7 heuristic rules; AniList stores provider genres separately. The reported 16-item display limit is not proven to be a global hardcoded limit. | PARCIAL / INCONCLUSIVO | genre / catalog |
| RF-005 | AniList/artwork | LibraryService already owns AniListClient and ArtworkEngine; cover_url/cover_cache and SQLite artwork persistence exist. | EXISTE / PARCIAL | earlier artwork and metadata work |
| RF-006 | Navigation lag | Home/Organize already use asyncio.to_thread/page.run_task in relevant paths; full attribution requires runtime profiling. | PARCIAL / INCONCLUSIVO | performance |
| RF-007 | Player | Media3 NativePlayerActivity, player_error/player_exited contracts and Python event handling already exist. | EXISTE / PARCIAL | player hardening work |
| RF-008 | Horizontal seek | the baseline2 removed horizontal swipe-to-seek; the current GestureLayer classifies horizontal movement and explicitly ignores it. | CORRIGIDO POR ANÁLISE ESTÁTICA | the baseline2 |
| RF-009 | Player/lifecycle coupling | MainActivity and NativePlayerActivity have lifecycle handling; MainActivity also has resume discovery and storage/media observer scan paths. | REGRESSÃO POTENCIAL / INCONCLUSIVO | lifecycle / scanning |

## Key findings

### Android Back
The current MainActivity intercepts Android Back with one AndroidX OnBackPressedCallback and forwards the physical event directly to Flutter/Flet through navigationChannel.popRoute(). Flet's page.on_view_pop is the single Python navigation entry point and calls navigate_back() exactly once. The player has its own OnBackPressedCallback and finishes itself locally; it does not route Back through the Python mailbox.

This is the existing single navigation contract. Do not reintroduce a Back mailbox or a second native navigation authority; Android dispatches, Flet receives popRoute, and NavigationController decides the logical result.

AndroidX documents OnBackPressedDispatcher/OnBackPressedCallback and integration with OnBackInvokedDispatcher for Android 13+ predictive Back. citeturn0search3turn0search13

### Library reload
Lifecycle-aware scan state and duplicate-scan guards already exist, but MainActivity.onResume participates in authorized discovery. Media/volume broadcasts can schedule additional scans. This justifies a lifecycle/scan regression baseline, but does not prove every reload symptom has one cause.

### Organize
The source contains async open_collection() plus direct Flet click bindings to that coroutine. This is a concrete callback-contract risk worth preserving as a regression target. the baseline does not change the production handler.

### Genres
There are two concepts: local GenreClassifier.RULES and AniList metadata genres. Therefore the reported 16-genre symptom cannot safely be attributed to GenreClassifier alone.

### Artwork
Artwork is not missing. ArtworkEngine exists and is connected to LibraryService; LibraryStore has artwork persistence and a covers cache. the baseline therefore does not create another artwork subsystem.

### Player
Media3 remains the player engine. NativePlayerActivity already has player_error/player_exited contracts, immersive handling, gesture code, aspect-ratio support and track UI. the baseline2 now removes horizontal swipe-to-seek and keeps seek on explicit controls/seekbar; vertical gestures are optional and touch-arbitrated.

## External references

CloudStream repository metadata identifies GPL-3.0; use it as a behavioral reference, not as source to copy. urlCloudStream repositoryhttps://github.com/recloudstream/cloudstream

NOVA aos-AVP is Apache-2.0 and separates Video UI, MediaLib, FileCoreLibrary and native multimedia components. This is a useful reference for later hardening, not a reason to replace ReiAnix architecture. citeturn0search2turn0search6

Animiru is Apache-2.0 and describes itself as a video player and library manager; it is useful as an anime-library/settings reference. urlAnimiru repositoryhttps://github.com/quickdesh/Animiru

GitHub currently reports no license metadata for ReiAnix, so no third-party license should be assumed for the project. urlReiAnix repositoryhttps://github.com/reinansantos9090-debug/ReiAnix

## Validation classification

ANÁLISE ESTÁTICA: architecture, source contracts, callback patterns, lifecycle paths, genre implementation, artwork pipeline, player contracts and gesture path.

TESTE AUTOMATIZADO: tests/test_regression_baseline.py is the new deterministic source-level regression guard.

NÃO VALIDADO EM DISPOSITIVO: Android Back, lifecycle recreation, real Flet clicks, MediaStore observer behavior, artwork/network timing, player opening/playback and physical swipe gestures.

BUILD/CI: not claimed as validated because the available repository connector can inspect/mutate GitHub but cannot execute local Python, pytest, Gradle or APK commands from a checkout.

## Explicit non-goals

No Scan Coordinator, GenreRegistry, Artwork Engine replacement, navigation rewrite, player UI rewrite, Settings Center, backup system or database rewrite was introduced by the baseline.
