# ReiAnix — Jetpack Compose Foundation

## Scope

Prompt 01 established the Kotlin + Jetpack Compose foundation. Prompt 02 extends that foundation into a
shared Material 3 visual design system without migrating any complete application screen.

## Current entry boundary

MainActivity remains the existing FlutterFragmentActivity entry used by the Flet-generated Android
host. Compose is enabled in the same Android module but is not attached to that launcher in these
foundation steps. The current Flet UI therefore remains reversible and unchanged.

NativePlayerActivity remains a View-based ComponentActivity using Media3. No player UI migration is
performed here.

## Design-system boundary

Prompt 02 introduces presentation-only Compose infrastructure:

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
scanner/SAF/MediaStore pipeline remains authoritative until a later prompt explicitly moves a
specific screen or capability.

Compose UI must consume state through ViewModels/StateFlow and call application-facing boundaries
rather than embedding scan, persistence, playback, or storage business rules inside Composables.

## Navigation boundary

Navigation Compose 2.9.8 is available in the module, but these foundation prompts create no route graph
and no artificial destinations. Destination IDs and arguments will be defined only when the corresponding
screen migration is implemented.

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

## Prompt 29 — organização arquitetural

A reorganização do Prompt 29 é deliberadamente incremental. O código existente foi agrupado por responsabilidade em `ui`, `viewmodel`, `data`, `player`, `storage`, `bridge` e `scanner`, sem introduzir uma nova camada de domínio sem necessidade e sem criar outro banco de dados.

`MainActivity` e `NativePlayerActivity` permanecem no pacote raiz porque são entrypoints Android referenciados diretamente pelo manifesto e por contratos de integração. O player continua sendo Media3; o pipeline local continua usando SQLite + SAF/MediaStore/Broad Storage e o mailbox existente. Os hosts Compose foram colocados em `ui/host`, enquanto as telas e a navegação continuam em `ui/`.

A verificação arquitetural também exige que caminhos antigos não voltem a ser usados por testes/empacotadores e que o pacote declarado de cada classe movida corresponda ao diretório. Nenhuma dependência Gradle nova foi necessária para a organização.