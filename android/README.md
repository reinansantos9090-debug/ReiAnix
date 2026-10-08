# ReiAnix native Android host

This directory is the **native host overlay** for the Flet Android client. It
is not a standalone Flutter application and, by itself, it is not a complete
Android project: it intentionally relies on the Flutter embedding supplied by
the Flet-generated host. The overlay replaces that host's `MainActivity` with
`com.reiflix.reiflix_local.MainActivity` and adds the classes and dependencies
that cannot be implemented by Python:

* `SafScanner` uses `ACTION_OPEN_DOCUMENT_TREE`, preserves URI grants and
  enumerates `DocumentFile` objects as `content://` references;
* `NativeMailbox` transfers small JSON events through the app-private files
  directory without copying media files;
* `NativePlayerActivity` plays a persisted document URI through Media3;
* `GoogleIdentity` invokes Credential Manager and sends only profile fields to
  the mailbox.

## Toolchain contract

| Component | Version/configuration |
| --- | --- |
| Flet | `0.86.5` (`pyproject.toml`) |
| Flutter required by installed Flet | `3.44.8` (`flet.version.flutter_version`) |
| Android Gradle Plugin | `8.9.1` |
| Kotlin | `2.0.21` |
| Java toolchain | 17 |
| compile / target SDK | 36 / 36 |
| minimum SDK | 24 |
| Media3 | `1.11.1` for ExoPlayer and UI |

The repository does **not** commit an APK. The workflow builds one and refuses
to publish it unless DEX contains `MainActivity`, `NativeMailbox`,
`SafScanner`, `MediaStoreScanner`, `BroadStorageScanner`,
`NativePlayerActivity`, and `GoogleIdentity`. This prevents accidentally
releasing the stock Flet client, which would not understand `reiflix://native`.

## Required Flet host integration

A build template must merge `android/app` into Flet's generated Android host,
retain the Flet Flutter embedding, and use this module's manifest/activity and
dependencies. The standalone `android/` directory deliberately cannot be built
with `gradle :app:compileDebugKotlin` because `FlutterActivity` is supplied by
that generated host. Do not replace it with a plain Android app or fabricate a
filesystem path for a SAF URI.

`flet build apk --yes` is followed by `scripts/verify_android_host.py` in CI.
If this check reports missing descriptors, the selected Flet template did not
merge this overlay; the build must be fixed before an APK can be published.

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

earlier validation stage 01 adds the Kotlin Compose compiler plugin and native Compose/Material 3/Navigation
dependencies without replacing the Flet launcher or migrating an existing screen. MainActivity
remains the Flet/Flutter entry point, while NativePlayerActivity and Media3 remain untouched.

The project targets compileSdk 36 with AGP 8.9.1. Compose 1.12.x requires API 37 and AGP 9.1.2+,
so the foundation intentionally uses Compose BOM 2026.06.00 instead of forcing a toolchain upgrade
outside earlier validation stage 01.

Kotlin 2.0+ uses the Compose Compiler Gradle plugin. ReiAnix keeps Kotlin 2.0.21 and applies
org.jetbrains.kotlin.plugin.compose at the same version.

Navigation Compose 2.9.8 and Lifecycle ViewModel Compose 2.10.0 are prepared as dependencies;
earlier validation stage 01 creates no navigation graph or migrated screen. Existing SQLite, scanner, SAF, MediaStore,
artwork, progress, mailbox and Media3 remain the source of truth.

## Arquitetura Android após o earlier validation stage 29

O módulo Android mantém o host Flet existente e separa apenas responsabilidades que já estavam presentes no código. As atividades de entrada continuam no pacote raiz: `MainActivity` é o host Android/Flet e `NativePlayerActivity` é a Activity de reprodução Media3. `PerformanceDiagnostics` permanece no raiz por ser infraestrutura transversal do host.

A árvore de responsabilidades é:

- `ui/`: apresentação Compose, navegação e modelos de UI; `ui/host/` contém as três pontes que conectam Compose ao host existente.
- `viewmodel/`: estado e operações de tela usando as fontes reais da aplicação.
- `data/`: repositórios/codecs da projeção Kotlin, sem criar um segundo banco.
- `player/`: serviços e políticas de reprodução local (preferências/metadata, legendas, perfil de interação e system UI), além de `NativePlayerRequest` como contrato de entrada do player.
- `scanner/`: descoberta MediaStore/SAF/broad-storage e publicação/ciclo de scan.
- `storage/`: indexação nativa, autorização de armazenamento, batching e extração de thumbnails.
- `bridge/`: transporte Python/native, estado de requests, dispatcher de comandos e identidade Google.

Nenhum pacote `domain/` artificial foi criado: não havia um modelo de domínio independente justificando outra camada. SQLite, scanner, SAF, MediaStore, permissões, artwork/cache, progresso e Media3 continuam sendo as fontes existentes. Também não foram adicionadas dependências Gradle para concluir a reorganização; o earlier validation stage 29 usa as dependências Compose/Navigation/Lifecycle/Media3 já presentes no módulo.

As dependências seguem o sentido operacional host → bridge/ui → scanner/player → storage quando aplicável. As atividades continuam no raiz para preservar o manifesto, o host Flet e a integração Media3. Adapters Compose existentes não foram removidos porque ainda possuem consumidores reais em `MainActivity`.