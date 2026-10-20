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


## Referência arquitetural canônica

O mapa atualizado das camadas existentes, do fluxo Python ↔ Kotlin, dos contratos transitórios, da responsabilidade de cada módulo Python e da sequência segura de migração está em [`docs/ARCHITECTURE_NATIVE_KOTLIN.md`](../docs/ARCHITECTURE_NATIVE_KOTLIN.md).

Distinção essencial: `data/library` e `data/settings` são adapters de projeção/IPC; `library.json` e as projeções de configurações não substituem `LibraryStore`/SQLite. `NativeIndex` é staging/indexação do scan, não um segundo catálogo canônico. A Activity de entrada continua herdando de `FlutterFragmentActivity` dentro do host gerado pelo Flet; portanto, o estado atual ainda não é um aplicativo independente de Python/Flet/Flutter.


## Prompt 24 — Native shell boundary status

The Compose shell owns the visible migrated routes, arguments and back stack. Back pops native nested
destinations; Home and Recovery use a native double-Back exit policy. MainActivity's single
navigationChannel.popRoute() remains only as a compatibility fallback when the Compose host is hidden.
This is a limited navigation cutover, not proof of a standalone Android runtime. The launcher still
inherits from `FlutterFragmentActivity`, the official APK workflow still renders a Flet template,
and library/settings projection, scanning coordination, AniList/artwork and backup/restore have
remaining Python-side responsibilities. Keep those components until native replacements and data
parity are validated; do not treat this stage as completion of the overall migration.

## Prompt 15/18 — auditoria efetiva do build (2026-10-20)

**Status: PARCIAL — o APK compila pelo pipeline híbrido existente, mas o build não é independente de Python/Flet/Flutter.** Não foi removida nenhuma ferramenta ou bridge: o launcher atual herda de `FlutterFragmentActivity`, `ReiAnixApplication` herda de `FlutterApplication`, e o workflow empacota o runtime `serious_python` e a aplicação Python dentro de `assets/app.zip`.

### Cadeia canônica atualmente usada pelo CI

1. Configura Python 3.12, Java 17 e Android SDK/build-tools 36; instala `requirements.txt` e `flet[cli]==0.86.5`, e instala Flutter 3.44.8.
2. Gera a identidade do build e o contrato SQLite v29 a partir do `LibraryStore` canônico; baixa/usa o template Android oficial do Flet 0.86.5 e aplica `scripts/prepare_flet_template.py` sobre `android/`.
3. Executa `flet build apk` com esse template renderizado, `--python-version 3.12` e ABI `arm64-v8a`. Esta é a etapa que gera o APK e empacota o runtime Python/Flet/Flutter.
4. Usa o wrapper gerado em `build/flutter/android/gradlew` para validar `:app:compileReleaseKotlin`. A tarefa exclui downloads do payload Python/Dart de ABIs que não estão no APK selecionado; ela não converte o host em projeto standalone.
5. Valida o bundle Python, classes Kotlin/Compose no DEX, manifesto, permissões, assinatura, SHA-256 e a integridade do ZIP de distribuição.

O repositório não versiona `gradlew` nem `gradle/wrapper/gradle-wrapper.properties` na raiz ou em `android/`. O wrapper utilizado vem do projeto Android que o template Flet gera. A configuração versionada declara AGP 8.9.1, Kotlin e Compose Compiler plugin 2.0.21, Java 17, compileSdk/targetSdk 36, minSdk 24 e Media3 1.11.1. O workflow mais recente concluído com sucesso antes desta auditoria registrou Gradle Wrapper 8.14 gerado pelo template; esse número não é ainda um wrapper versionado pelo ReiAnix. O workflow atualizado passa a guardar a versão efetiva do wrapper/JDK/AGP/Kotlin em `build/android-build-toolchain.txt` e anexá-la aos metadados/artefatos do build.

### Evidência de dependência legada

- O workflow contém passos explícitos de setup Python, setup Flutter, instalação de Flet, renderização de template e `flet build apk`.
- O caminho de compilação Kotlin exige `build/site-packages/arm64-v8a` e o projeto renderizado contém o plugin/host Flutter e o módulo Serious Python.
- `verify_python_bundle.py` confirma que o APK inclui o bundle Python; `verify_android_host.py` confirma que o DEX contém a Activity host e as bridges necessárias.
- A Activity e Application Android versionadas dependem das classes de embedding Flutter. Compilar `android/app` isoladamente não é possível com a configuração versionada atual, pois ela é um overlay e não contém o host/embedding gerado.

Por isso, remover as ferramentas agora ou adicionar um `gradlew` que chame a configuração incompleta não produziria um build independente: quebraria a inicialização híbrida e/ou omitiria funcionalidades ainda servidas pelo runtime Python. Um caminho Gradle realmente standalone só pode substituir o atual após migrar os consumidores remanescentes, remover com paridade o embedding legado e versionar/validar a configuração Gradle completa — sem segundo projeto nem artefatos fictícios.

### Artefato de referência anterior à auditoria

O build oficial anterior a esta alteração, run [39369783146](https://github.com/reinansantos9090-debug/ReiAnix/actions/runs/39369783146), terminou em `success` no SHA `aa061aad1ddcc9a46d05f6cec21a73770da86778`. Gerou `build/ReiAnix.apk` (34.488.076 bytes) com SHA-256 `c3410e4e9b84aaeaf6a0ae58bf8400084e46cdb1839ac2e21713c1caf9fb9caf`, e o artefato `ReiAnix-apk-instalacao-direta-6252` foi carregado pelo Actions. Esse baseline usou assinatura `ephemeral-debug` e o bundle Python 3.12/Flet 0.86.5; não é o APK do commit produzido por esta etapa. O workflow de certificação correspondente, run [39369783150](https://github.com/reinansantos9090-debug/ReiAnix/actions/runs/39369783150), também terminou em `success`.

**Conclusão da auditoria:** preservar o pipeline híbrido atual é mais seguro do que declarar independência não comprovada. O workflow passa a registrar e publicar a evidência da toolchain que realmente compilou; a independência do build permanece bloqueada pelas dependências host/runtime acima.

