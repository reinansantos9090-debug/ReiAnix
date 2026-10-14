# ReiAnix — Jetpack Compose Foundation

## Scope

earlier validation stage 01 established the Kotlin + Jetpack Compose foundation. earlier validation stage 02 extends that foundation into a
shared Material 3 visual design system without migrating any complete application screen.

## Current entry boundary

MainActivity is still a FlutterFragmentActivity supplied by the Flet-generated Android host. The
Compose shell is the visible application surface for the migrated routes, but the app is not yet an
independent Android runtime: library/settings projections and several domain operations still use
NativeMailbox and Python services. Prompt 24 must not remove the generated host until those contracts
have native equivalents. The Compose host now owns Back at its Home root and closes the Activity
instead of forwarding the root Back action to the hidden Flet navigator.

NativePlayerActivity remains a View-based ComponentActivity using Media3. No player UI migration is
performed here.

## Design-system boundary

earlier validation stage 02 introduces presentation-only Compose infrastructure:

- `ReiAnixTokens` is the single source of truth for colors, spacing, dimensions, shapes, elevations
  and typography.
- `ReiAnixComposeTheme` supplies those tokens through Material 3's ColorScheme, Typography and Shapes.
- `ReiAnixComponents` contains reusable card, chip, primary/secondary button, section-title, artwork,
  progress and screen-layout primitives.
- `ReiAnixScreen` exposes safe horizontal sizing conventions without embedding domain behavior.

The design language is dark-first: near-black backgrounds, deep navy surfaces, electric blue primary
states, light text, rounded containers and large touch targets. No remote/streaming/catalog behavior was
added.

## System UI and insets

The existing Android `SystemUiController` and transparent application/player system-bar resources remain
the authoritative system-bar policy. Compose primitives use `WindowInsets`/safe-drawing compatibility
through the screen-level layout boundary rather than introducing a second system-bar controller.

## Data and domain boundary

There is no second Kotlin database or duplicate catalog. The existing Python/Flet + SQLite +
scanner/SAF/MediaStore pipeline remains authoritative until a later stage explicitly moves a
specific screen or capability.

Compose UI must consume state through ViewModels/StateFlow and call application-facing boundaries
rather than embedding scan, persistence, playback, or storage business rules inside Composables.

## Navigation boundary

earlier validation stage 03 establishes the first shared Navigation Compose shell. The canonical bottom-navigation
destinations are Home, Biblioteca, Buscar and Ajustes, matching the visual reference screens. Minha Lista
remains a secondary route and is not a bottom-navigation item. Details keeps the bottom navigation visible
and selects the originating primary tab when applicable; Player is a full-screen secondary surface without
the bottom navigation. Details/Player use stable encoded IDs plus origin
metadata, while Navigation Compose owns tab save/restore semantics.

The existing Flet/Android bridges remain authoritative for capabilities not yet migrated. The native Compose
shell uses the existing host boundary rather than a second Activity. Back behavior is shared by contract: Compose owns nested native destinations, while the canonical
Python NavigationController still owns Home-root exit policy until the host migration is complete.
This does not certify Python/Flet independence.

## Coroutine/lifecycle boundary

Future screen ViewModels should expose immutable StateFlow and use viewModelScope for cancellable work. Flow
collection in Composables must be lifecycle-aware. Existing Flet callbacks, NativeMailbox events, scanner
tasks, and Media3 lifecycle remain unchanged.

## Toolchain contract

Kotlin: 2.0.21
Compose Compiler Gradle plugin: 2.0.21
Compose BOM: 2026.06.00
Material 3: managed by the BOM
Navigation Compose: 2.9.8
Lifecycle ViewModel Compose: 2.10.0
Activity Compose: 1.13.0
compileSdk/targetSdk: 36
AGP: 8.9.1

Compose 1.12.x requires compileSdk 37 and AGP 9.1.2+, so the project remains on the existing
Compose BOM/toolchain instead of upgrading unrelated Android build tooling.

## Official packaging gate

The official APK workflow runs `scripts/verify_compose_packaging.py` after the real Flet/Gradle APK build.
It verifies the Compose foundation classes in the rendered Android project and final APK DEX. The existing
`scripts/verify_android_host.py` then verifies the complete native host contract.

## earlier validation stage 29 — organização arquitetural

A reorganização do earlier validation stage 29 é deliberadamente incremental. O código existente foi agrupado por responsabilidade em `ui`, `viewmodel`, `data`, `player`, `storage`, `bridge` e `scanner`, sem introduzir uma nova camada de domínio sem necessidade e sem criar outro banco de dados.

`MainActivity` e `NativePlayerActivity` permanecem no pacote raiz porque são entrypoints Android referenciados diretamente pelo manifesto e por contratos de integração. O player continua sendo Media3; o pipeline local continua usando SQLite + SAF/MediaStore/Broad Storage e o mailbox existente. Os hosts Compose foram colocados em `ui/host`, enquanto as telas e a navegação continuam em `ui/`.

A verificação arquitetural também exige que caminhos antigos não voltem a ser usados por testes/empacotadores e que o pacote declarado de cada classe movida corresponda ao diretório. Nenhuma dependência Gradle nova foi necessária para a organização.