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
