# ReiAnix native Android application

The `android/` directory is now a standalone Android Gradle application. The
Manifest launches `com.reiflix.reiflix_local.MainActivity`, a native
`ComponentActivity` hosting the existing Jetpack Compose navigation shell.
`ReiAnixApplication` extends Android's `Application` and owns the existing
process-scoped Kotlin dependency graph/bootstrap. The official release APK is
built directly by Gradle and does not start a Flutter, Flet, or Python runtime.

The Kotlin application reuses the existing SQLite repository, bootstrap and
recovery coordinator, SAF/MediaStore scanners, NativePlayerActivity/Media3,
Google Credential Manager integration, and Compose screens. No second
Activity, navigation controller, database, scanner, or player was introduced.

## Native toolchain

| Component | Version/configuration |
| --- | --- |
| Android Gradle Plugin | `8.9.1` |
| Kotlin / Compose Compiler plugin | `2.2.10` |
| Standalone Gradle | `8.11.1` |
| Java toolchain | 17 |
| compile / target SDK | 36 / 36 |
| minimum SDK | 24 |
| Media3 | `1.11.1` for ExoPlayer and UI |

The official `.github/workflows/build_apk.yml` assembles
`:app:assembleRelease`, explicitly compiles `:app:compileReleaseKotlin`,
checks the packaged native classes and Manifest, and rejects APKs containing
Python bytecode, `assets/app.zip`, SeriousPython, Flutter assets, or Flutter
embedding libraries. It publishes the direct APK, checksum and installation ZIP
only after those checks succeed.

A native release needs the canonical SQLite schema contract asset. CI generates
it from the legacy Python `LibraryStore` schema with
`scripts/generate_native_sqlite_test_fixture.py` before the Gradle build and
removes the generated fixture/asset afterward. This is a build-time schema
generation dependency only; it does not embed Python into the APK.

The repository still retains `main.py`, Flet dependencies, and Python services
for desktop/legacy flows and subsequent domain migrations. Their source presence
does not mean the official Android APK launches them. Conversely, a successful
native build does not prove feature parity for every legacy Python-only route;
those remaining responsibilities are tracked in
`docs/KOTLIN_MIGRATION_AUDIT.md` and the migration reports.

## Validação física e escalabilidade de bibliotecas

O caminho Android de descoberta usa lotes de **250 documentos** por padrão. Esse
valor limita a memória temporária da ponte e do scanner sem transformar cada
arquivo em um evento individual. O limite configurável é restringido a 25..1000.

Broad Storage, SAF e MediaStore não retornam mais um `JSONArray` com toda a
biblioteca. Cada lote é preparado pelo `NativeIndex` em NDJSON temporário,
identificado por `scanId`, `generationId`, `batchId` e número do lote. O
snapshot anterior só é substituído quando a geração termina em
`COMPLETED` ou `EMPTY_COMPLETE`. PARTIAL, CANCELLED, UNAVAILABLE e FAILED
preservam o snapshot anterior e não executam reconciliação destrutiva.

A instrumentação Android está em
`app/src/androidTest/kotlin/com/reiflix/reiflix_local/DeviceFlowInstrumentedTest.kt`.
O executor físico está em `scripts/validate_android_device.py` e exige `adb`;
ele nunca registra um teste físico como concluído quando não existe dispositivo
autorizado.

No ambiente de desenvolvimento usado para esta alteração não há `adb`
nem um dispositivo/emulador Android conectado. Portanto, os fluxos que
dependem do seletor SAF, Settings, volumes removíveis/USB e reprodução física
dos arquivos `66619.mp4`, `66621.mp4` e `66625.mp4` continuam como
**AINDA NÃO VALIDADO** até execução no dispositivo.

Os testes de carga Python de escalabilidade simulam 10.000, 50.000 e 100.000
documentos alimentando `LibraryService.ingest_documents_batch()` em blocos
de 250, verificando que nenhum lote ultrapassa esse limite e que cada lote
atualiza o progresso persistido.


## Jetpack Compose foundation

The existing Kotlin Compose/Material 3/Navigation dependencies back the native launcher
and screens already migrated into Compose. MainActivity and the process Application
are native Android classes; NativePlayerActivity and Media3 remain the single player path.

The project targets compileSdk 36 with AGP 8.9.1. Compose 1.12.x requires API 37 and AGP 9.1.2+,
so the foundation intentionally uses Compose BOM 2026.06.00 instead of forcing a toolchain upgrade
outside earlier validation stage 01.

Kotlin 2.0+ uses the Compose Compiler Gradle plugin. ReiAnix pins the Kotlin Android and Compose compiler plugins to 2.2.10 to match Kotlin metadata in the current AndroidX dependency graph.

Navigation Compose 2.9.8 and Lifecycle ViewModel Compose 2.10.0 are prepared as dependencies;
earlier validation stage 01 creates no navigation graph or migrated screen. Existing SQLite, scanner, SAF, MediaStore,
artwork, progress, mailbox and Media3 remain the source of truth.

## Arquitetura Android após o earlier validation stage 29

O módulo Android tem entrada nativa Kotlin. As atividades continuam no pacote raiz: `MainActivity` é a launcher Activity e `NativePlayerActivity` é a única Activity de reprodução Media3. `PerformanceDiagnostics` permanece no raiz como infraestrutura transversal do host Android.

A árvore de responsabilidades é:

- `ui/`: apresentação Compose, navegação e modelos de UI; `ui/host/` contém as três pontes que conectam Compose ao host existente.
- `viewmodel/`: estado e operações de tela usando as fontes reais da aplicação.
- `data/`: repositórios/codecs da projeção Kotlin, sem criar um segundo banco.
- `player/`: serviços e políticas de reprodução local (preferências/metadata, legendas, perfil de interação e system UI), além de `NativePlayerRequest` como contrato de entrada do player.
- `scanner/`: descoberta MediaStore/SAF/broad-storage e publicação/ciclo de scan.
- `storage/`: indexação nativa, autorização de armazenamento, batching e extração de thumbnails.
- `bridge/`: transporte Python/native, estado de requests, dispatcher de comandos e identidade Google.

Nenhum pacote `domain/` artificial foi criado: não havia um modelo de domínio independente justificando outra camada. SQLite, scanner, SAF, MediaStore, permissões, artwork/cache, progresso e Media3 continuam sendo as fontes existentes. Também não foram adicionadas dependências Gradle para concluir a reorganização; o earlier validation stage 29 usa as dependências Compose/Navigation/Lifecycle/Media3 já presentes no módulo.

As dependências seguem o sentido operacional Application/bootstrap → Activity/Compose → repositórios/serviços → scanner/player/storage quando aplicável. As Activities continuam no raiz para preservar o Manifest e a integração Media3. Adapters Compose foram preservados para compatibilidade com os serviços existentes.


## Referência arquitetural canônica

O mapa atualizado das camadas existentes, do fluxo Python ↔ Kotlin, dos contratos transitórios, da responsabilidade de cada módulo Python e da sequência segura de migração está em [`docs/ARCHITECTURE_NATIVE_KOTLIN.md`](../docs/ARCHITECTURE_NATIVE_KOTLIN.md).

Distinção essencial: `data/library` e `data/settings` ainda incluem adapters de projeção/IPC; `library.json` e projeções de configurações não substituem SQLite. `NativeIndex` é staging/indexação do scan, não um segundo catálogo canônico. O APK oficial usa a Activity nativa e a verificação pós-build reprova payload Python/Flet/Flutter; o restante da lógica Python continua preservado para migração posterior.


## Native entrypoint status

The official release build no longer renders a Flet template or launches a
Flutter engine. The launcher is `MainActivity : ComponentActivity()`, and the
application class is `ReiAnixApplication : Application()`. Physical Back is
owned by the existing Compose shell; if that shell is hidden, it is restored to
Home instead of forwarding Back to a Flutter navigation channel.

This is a native-entrypoint cutover, not proof that every domain feature has
reached full Kotlin parity. `main.py`, Flet/Python source modules, and some
event/projection compatibility handlers remain in the source tree for desktop
and later migration prompts. No physical-device or emulator launch is claimed
by static APK verification.
