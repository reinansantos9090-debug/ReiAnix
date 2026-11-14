# Kotlin Main Entrypoint Migration — Prompt 1.1/9

## Estado desta etapa

**Status: PARCIAL até a validação oficial do build e do artefato no commit final.** O ponto de entrada Android foi migrado para uma Activity nativa e o workflow oficial passa a construir o módulo Android diretamente com Gradle, sem renderizar o template Flet. O arquivo `Main.kt` não é necessário: o Android inicia a classe `Activity` declarada no Manifest, e essa classe existente agora é `MainActivity : ComponentActivity()`.

A migração de todas as responsabilidades históricas de `main.py` não está sendo afirmada. `main.py`, `core/` e `views/` permanecem preservados para o desktop e para consumidores legados que serão migrados nas próximas etapas.

## Alterações técnicas

- `MainActivity.kt` usa `androidx.activity.ComponentActivity`; foram removidos `FlutterFragmentActivity` e o acesso a `flutterEngine`. O Back físico continua usando o callback existente e o shell Compose nativo; se o host estiver oculto, o código restaura a rota Home em vez de chamar `navigationChannel.popRoute()`.
- `ReiAnixApplication.kt` agora estende `android.app.Application` e segue criando o único `ReiAnixAppDependencies` e iniciando `ReiAnixAppBootstrap`.
- O Manifest já declarava `MainActivity` como launcher e mantém essa identidade, além de preservar a única `NativePlayerActivity` e seus deep links.
- `android/app/build.gradle.kts` suporta versionCode fornecido pelo CI e assinatura de release estável opcional; sem secrets de assinatura configurados, o release usa a chave debug e não é compatível para update de uma instalação assinada com outra chave.
- `build_apk.yml` compila o app diretamente com Gradle, verifica o Manifest/DEX e o payload nativo e não renderiza o template Flet.
- `verify_native_apk.py` inspeciona o ZIP e os DEX reais do APK, exigindo o launcher/Application/Compose/player nativos e rejeitando payloads Python, `assets/app.zip`, SeriousPython, assets e bibliotecas Flutter.
- Os testes de contrato existentes foram ajustados para verificar o launcher nativo. Nenhum teste foi removido, e nenhum teste de emulador/workflow foi adicionado.
- A documentação do Android e o README agora descrevem o caminho de build nativo.

## Dependências restantes e limites

O workflow ainda instala Python para executar a suíte de regressão da fonte e gerar o contrato SQLite v29 a partir do schema canônico ainda implementado em `core/library_store.py`. Essa dependência é de ferramentas/build, não de runtime dentro do APK; a fonte Python não foi eliminada.

O arquivo `main.py` continua sendo necessário às jornadas desktop/legadas e ainda possui coordenação, consumidores e eventos que não tiveram paridade Kotlin integral comprovada nesta etapa. Por isso, a remoção de `main.py` seria prematura e não foi feita. O pacote nativo será classificado como independente de Python apenas se o verificador pós-build confirmar a ausência do runtime no APK; o comportamento em dispositivo físico continua não validado sem execução real.

## Validações esperadas

- Suíte Python existente e auditoria de fonte executadas pelo workflow oficial.
- Compilação Kotlin release e geração de APK via `:app:assembleRelease`.
- Verificação pós-build do conteúdo DEX/ZIP, do Manifest efetivo, do contrato SQLite, das permissões Android e do hash do artefato.
- Confirmação dos estados finais dos workflows associados ao SHA exato do commit.

Mesmo com CI verde, o status global desta etapa é **PARCIAL** enquanto a migração das responsabilidades de domínio Python restantes e a validação física de cold start não estiverem completas.


## Prompt 1.2 — Build audit

The release workflow on `e057a10b160366d3c02ccb86b728ab21bcfabbb0` passed the
existing source regression suite (1,971 tests and 58 subtests) and source audit,
but `:app:assembleRelease` stopped at `:app:mergeReleaseNativeLibs` because
`android.useAndroidX` was not enabled. The fix is an Android-project
`gradle.properties` file with `android.useAndroidX=true`, and the source audit
now enforces this property. The APK native-host verifier also had adjacent
byte-string literals concatenated accidentally for ComposeHost and SettingsViewModel;
that requirement is now represented by two separate descriptors.

These changes have not yet been certified until the new SHA's build workflow
finishes and the produced APK passes all post-build checks.


The first retry after enabling AndroidX got past `mergeReleaseNativeLibs` but
then exposed an invalid Maven coordinate, `io.coil-kt:coil-okhttp:2.7.0`,
which does not exist in Coil 2.7.0. The native artwork source directly imports
`coil.ImageLoader`; this build now depends on the published `coil` 2.7.0
artifact alongside the existing `coil-compose` 2.7.0. The version stays pinned
to Coil 2; no Coil 3 API/version migration was made.


## Prompt 1.2 — DEX merge timeout investigation

The AndroidX fix and the Coil coordinate repair allowed the native release task
to advance through manifest/resource processing and reach
`:app:mergeExtDexRelease`. The run `39401204819` ended with conclusion
`cancelled` at 10:06:07 UTC. Its job log says `The operation was canceled`
after that DEX merge task had been running since 09:33:44 UTC; there is no Gradle
exception, Kotlin compile error, or out-of-memory message in the captured log.
The run used a 35-minute job timeout and produced no APK artifact, so the failure
is an execution-time ceiling rather than a proven DEX defect.

The build configuration now gives Gradle a bounded 3 GiB heap and 1 GiB
metaspace, bounds the Kotlin daemon at 2 GiB, enables the Gradle build cache
and parallel workers (maximum 4), and raises the Android release job ceiling to
90 minutes. This does not weaken tests, artifact checks, or security checks.
The changed existing workflow contract test was updated in place; no test case
was added or removed. A new SHA's complete run remains required to confirm the
effect on DEX merge and to validate the resulting APK.


## Prompt 1.2 — Kotlin metadata compatibility

The next release build passed `:app:mergeExtDexRelease` and Android resource
processing, then failed at `:app:compileReleaseKotlin`. The compiler reported
that the resolved `kotlin-stdlib-2.2.10.jar` contains metadata version
`2.2.0`, while the repository's Kotlin 2.0.21 compiler expects `2.0.0`.
This is a compiler/dependency incompatibility, not a source error and not a
reason to disable metadata checks.

The project now aligns both `org.jetbrains.kotlin.android` and
`org.jetbrains.kotlin.plugin.compose` to 2.2.10. The legacy Flet-template
preparation script also normalizes a generated 2.0.21 Kotlin Android plugin
before adding the matching Compose compiler plugin, so it does not produce a
mixed plugin classpath. Existing contract tests and the source audit were
updated in place; no test cases were added or removed. This confirmation was completed by the official Build ReiAnix Android APK run
[39407009008](https://github.com/reinansantos9090-debug/ReiAnix/actions/runs/39407009008),
on source commit `566521ed45f0534bc318cb2fa38adc8d8c6ad12d`. The release Kotlin
compilation and `:app:assembleRelease` succeeded, and the APK passed all
post-build runtime, DEX, manifest, signature, hash, and repository-integrity
checks. The terminal run results and remaining source inventory are recorded
below.


## Prompt 1.2 — Final verified outcome

- Source commit built: `566521ed45f0534bc318cb2fa38adc8d8c6ad12d`.
- APK workflow: [run 39407009008](https://github.com/reinansantos9090-debug/ReiAnix/actions/runs/39407009008), conclusion **success**.
- Source/evidence certification: [run 39407009040](https://github.com/reinansantos9090-debug/ReiAnix/actions/runs/39407009040), conclusion **success**.
- Regression suite: `1,971 passed, 58 subtests passed`.
- Gradle tasks `:app:assembleRelease` and `:app:compileReleaseKotlin`: **BUILD SUCCESSFUL**.
- Packaged APK: `build/ReiAnix.apk`, 19,858,093 bytes, SHA-256
  `579a680e3b1d123834e44fbb0cd0030711c5cbb78801d913eef51dcc762b99a9`.
- APK verifier output: `NATIVE_RUNTIME=PASS`,
  `PYTHON_FLET_FLUTTER_ABSENT=PASS`,
  `ANDROID_ENTRYPOINT=PASS com.reiflix.reiflix_local.MainActivity`,
  `COMPOSE_APK_DEX=PASS`.
- Effective manifest: package `com.reiflix.reiflix_local`, build number
  `6280`, version `0.2.1`, target SDK `36`; required storage permissions
  and native launcher contracts passed.
- Signature: APK Signature Scheme v2 passed; signer is the Android **debug**
  certificate because stable signing secrets were not configured. This build
  is not update-compatible with an installation signed with a different key.
- No device launch test is claimed. CI validated compile/package/manifest/
  signature/runtime contents, not a physical-device cold start.
- Global Python elimination remains **pending**. The APK contains no
  Python/Flet/Flutter runtime, but the repo/build still uses Python for legacy
  source tests, source audits, SQLite contract generation and retained desktop
  functionality.

The exact remaining `.py` inventory is maintained in
`docs/PYTHON_MIGRATION_INVENTORY_PROMPT_1_2.md`. None of those 218 files was
deleted because their full functional replacement has not yet been proven.


## Prompt 1.4 corretivo — Orquestração nativa funcional parcial

**Estado em 20/10/2026: PARCIAL. A migração global de `main.py` não foi concluída e o arquivo foi preservado.** Esta seção complementa os registros históricos acima e descreve o código validado no HEAD `76262d2f3daa673b1799e1fa1423133324da2521`.

### Mapa de responsabilidades auditado

| Responsabilidade / componente | Estado atual | Evidência e limite |
| --- | --- | --- |
| Launcher Android | Já nativo | O Manifest efetivo aponta para `com.reiflix.reiflix_local.MainActivity`; não foi criada outra Activity launcher. |
| Grafo de dependências process-scoped | Já nativo | `ReiAnixApplication` cria a única `ReiAnixAppDependencies`; Application e Activity chamam o mesmo bootstrap idempotente existente. |
| Validação do SQLite canônico e decisão de Recovery Mode | Já nativo | `ReiAnixAppBootstrap` valida a base antes de recuperar receipts SAF e iniciar `NativeCommandDispatcher`; o estado é publicado por `StateFlow`. |
| Política de liberação de operações nativas | Migrada parcialmente para `main.kt` | `MainEntryPoint.allowsNativeOperations` centraliza a decisão que exige decisão da Activity concluída, fase `READY`, persistência `HEALTHY` ou `NOT_CREATED` e ausência de Recovery Mode. `MainActivity` delega essa política. |
| Observação do bootstrap e correlação diagnóstica na vida da Activity | Migrada parcialmente para `main.kt` | Coleta o estado já existente com `lifecycleScope` e `distinctUntilChanged`; a coleta termina com o ciclo de vida da Activity. Não inicia um segundo bootstrap nem registra observadores por recomposição. |
| Seleção da rota inicial, replay de intents e gating do ciclo de vida da Activity | Já nativo, permanece na Activity | A Activity só monta o host Compose após `validateCanonicalDatabase()` e escolhe Home ou Recovery. Essa responsabilidade é dependente do ciclo de vida Android e não foi duplicada em `main.kt`. |
| Consultas/mutações de biblioteca, settings, scanner, recuperação e player no app Android | Já nativo em componentes especializados, com compatibilidade restante | Os serviços Kotlin, repositórios SQLite, ViewModels, scanners, bridges e player existentes continuam sendo a fonte nativa. A presença do componente, por si só, não certifica paridade integral com todos os fluxos Python. |
| `LibraryStore`, `SettingsStore`, `AndroidBridge`, `ComposeLibraryBridge`, `ComposeSettingsBridge`, `ScanCoordinator`, navegação/projeção Flet, consumidores Flet residuais e `ft.run(main)` | Pendente / legado intencional | O código Python ainda tem consumidores de desktop e de compatibilidade; as telas legadas de Home/Organize/Details/Collector e os fallbacks de Settings não foram provados como substituídos integralmente. |
| Remoção global de `main.py` e de contratos Python | Não segura nesta etapa | Os testes contratuais existentes leem `main.py` diretamente e verificam consumidores/bridges legados. Apagar o arquivo agora quebraria contratos válidos e removeria funcionalidade sem substituição demonstrada. |

### Alteração funcional desta etapa

`android/app/src/main/kotlin/com/reiflix/reiflix_local/main.kt` já não é apenas um gancho de log: `MainEntryPoint` observa o bootstrap process-owned, registra transições de estado de forma limitada e contém a política central de liberação das operações nativas. `MainActivity.kt` delega a decisão a esse ponto de entrada. A chamada explícita `appDependencies.bootstrap.start()` permanece intacta, pois a Application é a dona do bootstrap e a operação é idempotente.

Não foram criados serviços de domínio duplicados. Não houve alteração do Manifest, do banco/schema SQLite, de scanners, player, testes ou workflows. Os quatro testes contratuais auditados permaneceram sem alterações:

- `tests/test_main_startup_contract.py` — SHA `c66c872bfca973635025bc88029fd22d35a6009d`.
- `tests/test_flet_ui_removal.py` — SHA `c804dd2a949a1aa213ed02ec1ee3313b49f65f4d`.
- `tests/test_flet_launch_compat.py` — SHA `40568bd853f4290f114500ab6d4dd933c5eb9af8`.
- `tests/test_prompt19_app_bootstrap.py` — SHA `9ff2bdc5493bd537de417409d33631c63e113c01`.

### Validação oficial do código Kotlin

Para o commit de código `76262d2f3daa673b1799e1fa1423133324da2521`:

- [Build ReiAnix Android APK — run 39436193631](https://github.com/reinansantos9090-debug/ReiAnix/actions/runs/39436193631): **success**.
- [ReiAnix Release Evidence Certification — run 39436193473](https://github.com/reinansantos9090-debug/ReiAnix/actions/runs/39436193473): **success**, incluindo a matriz de evidências de 201 linhas.
- Testes de regressão Python: `1,971 passed, 58 subtests passed`.
- `:app:assembleRelease` e verificação explícita de compilação Kotlin: **BUILD SUCCESSFUL**.
- Verificações pós-build: `NATIVE_RUNTIME=PASS`, `PYTHON_FLET_FLUTTER_ABSENT=PASS`, `ANDROID_ENTRYPOINT=PASS com.reiflix.reiflix_local.MainActivity`, `COMPOSE_APK_DEX=PASS`; Manifest efetivo e integridade final do Git verificados.
- APK build 6296: 19,859,229 bytes; SHA-256 `0f63933235912b007fe12b04c7fcedd1ed20298f74c73c23f1114186771a6e14`. Certificado de assinatura SHA-256 `cf1dbfcc5ac35c39d36695ec5f9822ec8fef1a595c8fff962917c2339fdb24d3` (debug signing; chave de release estável não configurada).
- APK e ZIP de instalação foram publicados como artefatos do Actions. A publicação no rolling GitHub Release foi ignorada pela condição existente do workflow.

O verificador confirma ausência de Python/Flet/Flutter **dentro deste APK**, não a remoção dessas dependências do repositório. Nenhum teste em dispositivo físico foi realizado, e nenhum teste de emulador foi adicionado ou executado.

### Commits corretivos de código

- `914dda4a00d60066eeeceed3481ed6f9eabedbe1` — coordenação do estado de inicialização em `main.kt`.
- `aaf472e1e397133424d9a9c1ddddf923adc08328` — delegação da política de liberação de operações.
- `4fcc5cb81f0cc9092c17b72eafa4a92d08e10e5f` — observação ligada ao ciclo de vida da Activity.
- `76262d2f3daa673b1799e1fa1423133324da2521` — correção do tipo de Activity lifecycle-aware.

Comparação de `bfa1eb408d9c8245dce421088034e7b324371cc6` para `76262d2f3daa673b1799e1fa1423133324da2521`: 2 arquivos modificados, 104 linhas adicionadas e 18 removidas. `main.py` e o Manifest foram preservados.

**Próxima fronteira obrigatória:** migrar e provar os consumidores legados restantes, atualizar os contratos apenas quando sua finalidade puder ser preservada (nenhum teste foi alterado nesta etapa), confirmar a paridade funcional dos fluxos e só então avaliar a remoção de `main.py`. A certificação verde do APK não encerra esta migração.

## Prompt 1.5 — Contrato executável da política de entrada nativa

**Estado: melhoria incremental; a migração global de `main.py` continua PARCIAL.** Nesta alteração, a política Kotlin que libera comandos nativos foi tornada explícita e coberta por testes JVM focados, sem mover responsabilidades de UI/desktop de forma prematura.

- `MainEntryPoint.allowsNativeOperations` continua exigindo que a decisão inicial da Activity esteja concluída.
- A decisão do bootstrap exige a etapa `READY`, persistência `HEALTHY` ou `NOT_CREATED` e ausência de Recovery Mode. O estado `DEGRADED` com etapa/persistência prontas ainda pode representar avisos não fatais; a fase nominal sozinha não libera comandos.
- A verificação de persistência usa comparações explícitas, evitando construir um conjunto temporário em cada verificação do gate.
- Foi adicionado `android/app/src/test/kotlin/com/reiflix/reiflix_local/MainEntryPointTest.kt` com oito casos: decisão da Activity pendente, estado saudável, instalação sem banco prévio, avisos não fatais, etapa incompleta, Recovery Mode, persistência em recuperação e persistência não verificada.
- O launcher continua sendo `MainActivity`; `MainEntryPoint` não se torna uma segunda Activity e não cria outro grafo/bootstrap.

**Validação pendente:** o workflow automático de APK compila o código Kotlin de release, mas não executa os testes JVM recém-adicionados. O workflow Kotlin/JVM do repositório é manual e não foi acionado nesta alteração. Portanto, os oito testes estão escritos, mas não são declarados como executados. O build/CI do PR deve ser conferido pelo SHA exato antes de integrar.

Essa alteração não substitui as responsabilidades desktop/legadas de `main.py`, não remove consumidores Python e não prova paridade global. O arquivo fica preservado até que as jornadas que ainda dependem dele tenham uma substituição real.

