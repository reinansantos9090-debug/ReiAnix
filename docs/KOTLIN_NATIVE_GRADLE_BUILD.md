# Prompt 27/29 — Build Gradle nativo e empacotamento de validação

## O que foi adicionado

A raiz do repositório agora possui um Gradle Wrapper independente. O único projeto Android é `:app`, mapeado diretamente para `android/app`; não há um segundo applicationId, outro módulo de produto ou outra base de dados. O build usa AGP 8.9.1, Kotlin/Compose Compiler 2.2.0, Gradle 8.11.1, JDK 17 e Android SDK 36, alinhados com o módulo nativo e a matriz de compatibilidade: o KGP 2.2.0 aceita AGP 8.9.x e Gradle 8.11.x; a versão 2.0.21 do host Flet permanece isolada no workflow híbrido legado. Consulte a [matriz de compatibilidade Kotlin/Gradle](https://kotlinlang.org/docs/gradle-configure-project.html) e a [compatibilidade do AGP](https://developer.android.com/build/releases/about-agp).

Comandos utilizados pelo workflow dedicado:

```bash
./gradlew --version
./gradlew :app:assembleDebug --no-daemon --stacktrace --max-workers=2
```

O APK nativo é produzido em `android/app/build/outputs/apk/debug/app-debug.apk`. A tarefa Gradle inclui os fontes de `android/app/src/main`, o manifesto nativo, Compose, Media3 e as dependências Android existentes.

## Contrato SQLite v29

O runtime Kotlin compara a estrutura do banco canônico com o recurso `assets/native-sqlite-contract-v29.json`. Para que o build nativo não precise executar Python para produzir esse arquivo, foi incluída uma cópia compactada do contrato extraído do APK validado no commit `af8c5dd594e1b5e754354dfbd2345967e0dad8da` (o asset original tem 64.305 bytes). O Gradle decodifica e descompacta esse contrato em `build/` apenas quando o asset não está presente. O contrato é metadado de schema, não contém banco de usuário nem registros pessoais. A suíte de regressão Python compara a cópia compactada ao contrato gerado pelo `LibraryStore`.

O schema continua em v29. Nenhuma base de usuário, migração destrutiva ou segundo repositório foi criado.

## Workflow nativo de validação

`.github/workflows/native_gradle_apk.yml` configura somente JDK 17 e Android SDK 36 e invoca diretamente `./gradlew :app:assembleDebug`. Não instala Python, Flet ou Flutter, não chama tarefas de testes Kotlin/JVM, não executa testes instrumentados e não exige emulador. Limpa a saída do módulo antes da compilação, confere applicationId, SDKs, manifesto e classes DEX nativas, cria um ZIP real e compara o APK do ZIP byte a byte com o APK distribuído separadamente. Gera SHA-256 e `distribution-manifest.json` a partir da configuração Android e do SHA exato do commit. Os artefatos são publicados como `ReiAnix-native-gradle-apk-<run>` e `ReiAnix-native-gradle-ZIP-<run>`.

Esse ZIP e APK são **artefatos de validação do build nativo**, não substitutos declarados da distribuição completa atual. O workflow publica somente após todas as verificações passarem.

## Por que a distribuição oficial ainda não foi cortada para Gradle nativo

A auditoria do código real mostra que a compilação nativa pode ser configurada, mas remover imediatamente o empacotamento Flet/Flutter alteraria também o runtime da aplicação. O fluxo oficial atual ainda inclui `assets/app.zip` com o app Python. Os bloqueios que permanecem são funcionais, não apenas YAML:

- Em instalação nova, o `LibraryStore` Python ainda inicializa o banco SQLite v29. `ReiAnixSqliteDatabase` Kotlin valida o banco e o contrato existente, mas não cria o schema canônico.
- `NativeMailbox` ainda transporta operações consumidas por `ScanCoordinator` e serviços Python; não é apenas uma ferramenta de compilação.
- As projeções/ações de configurações e parte dos contratos de biblioteca ainda usam `ComposeSettingsBridge`, `ComposeLibraryBridge` e `main.py`.
- O produtor legado de artwork/capas e parte da integração AniList, backup/restauração e lógica de domínio continua no Python; Pillow também permanece necessário ao código atual.

Por isso, o workflow de distribuição já existente `.github/workflows/build_apk.yml` foi mantido como caminho de APK completo em vez de publicar silenciosamente um APK nativo incompleto ou selecionar um APK antigo se a compilação falhar. Ele ainda usa Flet/Flutter/Python para embalar as funções de runtime remanescentes. A independência **do build Kotlin de validação** agora existe em um workflow separado; a independência **do runtime e da distribuição oficial** não está certificada e ainda depende de migrações reais de domínio e inicialização. Essa distinção precisa continuar explícita até que a inicialização SQLite, as operações da mailbox, as projeções, artwork e os demais consumidores Python sejam substituídos e validados.

## Distribuição atual e verificações mantidas

O workflow oficial anterior continua executando a suíte de regressão Python, os gates de empacotamento nativo dentro do host atual e gerando os artefatos convencionais `ReiAnix-apk-instalacao-direta-<run>` e `ReiAnix-ZIP-extraia-primeiro-<run>`. O workflow Kotlin/JVM segue manual por `workflow_dispatch`; esta etapa não o executa, não adiciona gatilho de PR e não adiciona testes de emulador.

## Limites da evidência

Um `assembleDebug` bem-sucedido comprova que o módulo nativo compila e empacota suas classes e recursos no APK. Isso não comprova a execução completa do aplicativo em dispositivo físico, nem autoriza afirmar a remoção integral de runtime Python/Flutter, nem substitui os artefatos do workflow oficial. O resultado final do Prompt 27 depende do estado final dos workflows associados ao commit e os bloqueios listados acima permanecem pendentes.
