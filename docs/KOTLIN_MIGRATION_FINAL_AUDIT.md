# ReiAnix — auditoria final de migração para Kotlin

Data da atualização: 2026-10-18 (UTC)  
Pull request de trabalho: #194, branch `migration/prompt-28-native-scan-bridge-cutover-20261017`

Escopo: auditoria estática e histórico de validação do Prompt 29. As seções do baseline abaixo preservam evidências históricas dos SHAs nelas citados; os resultados dos workflows associados ao HEAD de implementação anterior à correção documental estão registrados na seção histórica específica. A evidência de CI comprova compilação/empacotamento e regressão dentro do contexto efetivamente executado, não paridade funcional completa.


## Registro de conclusão dos workflows do HEAD documental `ffe5406a7286798cebbdfd6c172d7053497d6fa8` — registro histórico após conclusão

Esta seção complementa a reconciliação anterior: ela foi verificada novamente após os workflows terminarem. O SHA `ffe5406a7286798cebbdfd6c172d7053497d6fa8` é o HEAD da branch da PR para o qual os dois runs abaixo reportaram conclusão `success`. O commit usado no checkout do runner é separado do SHA da branch.

- **HEAD da branch/PR:** `ffe5406a7286798cebbdfd6c172d7053497d6fa8`.
- **Checkout de merge nos dois workflows:** `ea160c7`, mensagem `Merge ffe5406a7286798cebbdfd6c172d7053497d6fa8 into e9e3fcc67bb778f0c7fe861f16dd1ff18d0c976d`. O SHA completo do merge pode ser consultado no run/commit associado; não confundir esse checkout com o SHA da branch.
- **Build oficial Android:** [run 39091982668](https://github.com/reinansantos9090-debug/ReiAnix/actions/runs/39091982668) — `completed / success`, 52/52 etapas concluídas, nenhuma etapa com falha. A suíte Python registrou **1.993 testes aprovados e 58 subtests aprovados**. A compilação do APK, a compilação Kotlin explícita de release, as verificações de pacote e a geração do checksum passaram. APK oficial [`ReiAnix-apk-instalacao-direta-6048`](https://github.com/reinansantos9090-debug/ReiAnix/actions/runs/39091982668/artifacts/12122589151) — **33.071.238 bytes**; ZIP oficial [`ReiAnix-ZIP-extraia-primeiro-6048`](https://github.com/reinansantos9090-debug/ReiAnix/actions/runs/39091982668/artifacts/12122704013) — **33.021.329 bytes**; artefato auxiliar [`ReiAnix-native-sqlite-bootstrap-source-6048`](https://github.com/reinansantos9090-debug/ReiAnix/actions/runs/39091982668/artifacts/12123580065) — **2.270 bytes**. SHA-256 do APK oficial, emitido no log como `APK_SHA256` e `apk_sha256`: `028c259b2eed7f94599ce08ac808212f6e0fa31c3522855c9d51fb846e7206fc`.
- **Validação Gradle nativa:** [run 39091982659](https://github.com/reinansantos9090-debug/ReiAnix/actions/runs/39091982659) — `completed / success`, 14/14 etapas concluídas, incluindo `:app:assembleDebug`, verificações de Manifest/DEX/payload, criação de ZIP, `sha256sum -c` e `git diff --check`. APK nativo de validação [`ReiAnix-native-gradle-apk-84`](https://github.com/reinansantos9090-debug/ReiAnix/actions/runs/39091982659/artifacts/12122878994) — **26.948.747 bytes**; ZIP [`ReiAnix-native-gradle-ZIP-84`](https://github.com/reinansantos9090-debug/ReiAnix/actions/runs/39091982659/artifacts/12122784382) — **26.892.985 bytes**. O log confirmou `ReiAnix-native-debug.apk: OK`, mas não exibiu o valor literal do SHA-256 interno do APK nativo. Os digests dos uploads são hashes dos ZIPs de artefato do Actions, não o hash do APK.
- **Estado da PR no momento da verificação:** aberta, em rascunho, sem merge; branch `migration/prompt-28-native-scan-bridge-cutover-20261017`; base `main` (`e9e3fcc67bb778f0c7fe861f16dd1ff18d0c976d)).
- **Limites técnicos:** o build oficial continua usando Python/Flet/Flutter; a compilação Gradle nativa não é uma substituição funcional certificada. Os resultados não provam paridade funcional completa, independência do runtime híbrido, funcionamento em dispositivo físico ou migração comportamental de uma base pessoal. SQLite permanece no schema v29. A migração integral para Kotlin continua pendente.

Os resultados desta seção pertencem especificamente ao HEAD `ffe5406...`. Se a documentação for alterada novamente, o novo commit terá SHA próprio e não herda automaticamente a validação destes runs; consulte a seção de estado da PR e os workflows associados ao HEAD corrente.

## Reconciliação documental — HEAD da branch na abertura do Prompt 29.2 (registro do Prompt 29.1; histórico, não é o HEAD posterior desta tarefa)

**Estado confirmado na abertura do Prompt 29.2:** a branch `migration/prompt-28-native-scan-bridge-cutover-20261017` e a PR [#194](https://github.com/reinansantos9090-debug/ReiAnix/pull/194) apontavam para o HEAD documental `dc233125efdb3ac80715c4a778874ab1f2fe788c`. A PR estava aberta, em rascunho, sem merge, com base `main` (`e9e3fcc67bb778f0c7fe861f16dd1ff18d0c976d`). O commit de implementação coberto pela correção documental era `07e28c2fb6bfeb800b85e95d122ff94f3c07f466`.

**Commits documentais já presentes nesse HEAD:**
- [`a3d81c42a61248d2f7deef5a851f6418f1925ff0`](https://github.com/reinansantos9090-debug/ReiAnix/commit/a3d81c42a61248d2f7deef5a851f6418f1925ff0) — registrou evidências de CI consultadas para o HEAD de implementação `07e28c2...` e corrigiu a referência ao SHA.
- [`dc233125efdb3ac80715c4a778874ab1f2fe788c`](https://github.com/reinansantos9090-debug/ReiAnix/commit/dc233125efdb3ac80715c4a778874ab1f2fe788c) — qualificou o conteúdo anterior explicitamente como histórico e corrigiu whitespace na mudança documental.
- Ambos alteraram somente `docs/KOTLIN_MIGRATION_FINAL_AUDIT.md`; nenhum código Kotlin ou Python foi alterado.

**Importante — SHA da branch versus SHA de merge usado nos runs:** os dois runs a seguir possuem `head_sha` da PR igual a `dc233125efdb3ac80715c4a778874ab1f2fe788c`, mas o checkout registrado pelos logs é o merge commit `d4c36f1fd27ca7e3ed0160dcb8c696472a7cafdc`, integrando esse HEAD à base `e9e3fcc67bb778f0c7fe861f16dd1ff18d0c976d`. Os resultados abaixo são da combinação de PR validada no merge commit; não devem ser descritos como se o runner tivesse compilado um checkout isolado só do SHA da branch.

- **Build oficial Android** — [run 39090458106](https://github.com/reinansantos9090-debug/ReiAnix/actions/runs/39090458106): `completed / success`; `head_sha` da PR `dc233125efdb3ac80715c4a778874ab1f2fe788c`; checkout/build `d4c36f1fd27ca7e3ed0160dcb8c696472a7cafdc`. A suíte Python registrou **1.993 testes aprovados e 58 subtests aprovados**. A geração do APK, a compilação explícita Kotlin release e as verificações de pacote terminaram sem falhas. Artefatos do Actions (tamanho do ZIP do artefato publicado): [`ReiAnix-apk-instalacao-direta-6047`](https://github.com/reinansantos9090-debug/ReiAnix/actions/runs/39090458106/artifacts/12122977222) — **33.071.032 bytes**; [`ReiAnix-ZIP-extraia-primeiro-6047`](https://github.com/reinansantos9090-debug/ReiAnix/actions/runs/39090458106/artifacts/12123325115) — **33.021.538 bytes**; artefato auxiliar [`ReiAnix-native-sqlite-bootstrap-source-6047`](https://github.com/reinansantos9090-debug/ReiAnix/actions/runs/39090458106/artifacts/12122437582) — **2.270 bytes**. O SHA-256 do APK oficial `ReiAnix.apk` foi explicitamente emitido pelo log: `2a2c417017911c01ce4501653b862dbfb9891ad8fb4f2bf934c14f916baf3660`. O mesmo log confirma Python 3.12 e Flet 0.86.5.
- **Validação Gradle nativa** — [run 39090458125](https://github.com/reinansantos9090-debug/ReiAnix/actions/runs/39090458125): `completed / success`; `head_sha` da PR `dc233125efdb3ac80715c4a778874ab1f2fe788c`; checkout/build `d4c36f1fd27ca7e3ed0160dcb8c696472a7cafdc`. Passaram `:app:assembleDebug`, as verificações de Manifest/DEX/payload nativo, geração do ZIP, verificação interna do checksum (`ReiAnix-native-debug.apk: OK`) e `git diff --check`. Artefatos do Actions: [`ReiAnix-native-gradle-apk-83`](https://github.com/reinansantos9090-debug/ReiAnix/actions/runs/39090458125/artifacts/12122646178) — **26.948.741 bytes**; [`ReiAnix-native-gradle-ZIP-83`](https://github.com/reinansantos9090-debug/ReiAnix/actions/runs/39090458125/artifacts/12122716083) — **26.892.982 bytes**. Os logs recuperados não exibem o valor literal do SHA-256 do APK nativo; esse hash não foi verificado e não é inferido dos digests dos arquivos ZIP do Actions.

**Limites preservados:** os artefatos e builds acima comprovam apenas as verificações executadas, compilação e empacotamento. O workflow oficial ainda usa a cadeia Python/Flet/Flutter; a validação Gradle é um artefato nativo de validação, não uma substituição funcional certificada. Os resultados não demonstram paridade funcional, independência do runtime híbrido, funcionamento em dispositivo físico ou migração comportamental de uma base pessoal existente. O schema SQLite permanece v29. A migração integral permanece pendente enquanto houver dependências ou consumidores ativos de Python, Flet ou Flutter.

## Registro de CI do HEAD de implementação anterior à correção documental — SHA `07e28c2fb6bfeb800b85e95d122ff94f3c07f466`

**Estado do Git verificado antes desta correção documental:** PR [#194](https://github.com/reinansantos9090-debug/ReiAnix/pull/194) aberta, em rascunho e não integrada; base `main`; branch `migration/prompt-28-native-scan-bridge-cutover-20261017`; HEAD da branch/PR `07e28c2fb6bfeb800b85e95d122ff94f3c07f466`; pai direto `d5bec88401ec2c8fc36415bf8580cc39443ca8ef`. O HEAD confirmado é `07e28c2...`, e não `d5bec884...`.

**Distinção de SHA validado pelos workflows:** ambos os runs abaixo estão associados ao evento de PR cujo `head_sha` é `07e28c2fb6bfeb800b85e95d122ff94f3c07f466`. Os logs de checkout mostram, porém, que o runner compilou/testou a referência de merge `refs/remotes/pull/194/merge`, SHA `1aad8aeaec1b2db52c9cbc78c7813ca8206ec4d7`, mensagem “Merge 07e28c2... into e9e3fcc67bb778f0c7fe861f16dd1ff18d0c976d”. Portanto, o código do HEAD da PR foi incluído na validação, mas a fonte exata do workspace desses runs é o commit de merge `1aad8ae...`, não um checkout isolado somente de `07e28c2...`.

- **Build oficial do Android** — [run 39088711613](https://github.com/reinansantos9090-debug/ReiAnix/actions/runs/39088711613): estado `completed`, conclusão `success`; SHA da PR associado ao run `07e28c2fb6bfeb800b85e95d122ff94f3c07f466`; SHA efetivo do checkout/build `1aad8aeaec1b2db52c9cbc78c7813ca8206ec4d7`. A suíte Python concluiu com **1.993 testes aprovados e 58 subtests aprovados**. O build do APK e a compilação explícita Kotlin de release passaram; também passaram as verificações de pacote/manifest/DEX e a publicação dos artefatos. Artefatos não expirados: [`ReiAnix-apk-instalacao-direta-6045`](https://github.com/reinansantos9090-debug/ReiAnix/actions/runs/39088711613/artifacts/12121699180), **33.071.050 bytes** (arquivo de artefato do Actions); [`ReiAnix-ZIP-extraia-primeiro-6045`](https://github.com/reinansantos9090-debug/ReiAnix/actions/runs/39088711613/artifacts/12121928091), **33.021.685 bytes**; e artefato auxiliar [`ReiAnix-native-sqlite-bootstrap-source-6045`](https://github.com/reinansantos9090-debug/ReiAnix/actions/runs/39088711613/artifacts/12121892066), **2.270 bytes**. SHA-256 do APK `ReiAnix.apk`, emitido e validado no log: `faed07b4e75197270f2e74cb1317ab111f3cbf427d3b66f5b9cb6e351912bf54`. A identidade do build confirma runtime Python 3.12 e Flet 0.86.5.
- **Validação Gradle nativa** — [run 39088711605](https://github.com/reinansantos9090-debug/ReiAnix/actions/runs/39088711605): estado `completed`, conclusão `success`; SHA da PR associado ao run `07e28c2...` e checkout efetivo `1aad8aeaec1b2db52c9cbc78c7813ca8206ec4d7`. A compilação `:app:assembleDebug`, inspeção de metadados, Manifest, classes/DEX e assets nativos, criação do ZIP e verificação `sha256sum -c` do APK passaram. Artefatos não expirados: [`ReiAnix-native-gradle-apk-81`](https://github.com/reinansantos9090-debug/ReiAnix/actions/runs/39088711605/artifacts/12122285314), **26.948.746 bytes**; [`ReiAnix-native-gradle-ZIP-81`](https://github.com/reinansantos9090-debug/ReiAnix/actions/runs/39088711605/artifacts/12122135819), **26.892.988 bytes**. O log acessível confirma que o arquivo de checksum do APK foi incluído no pacote e validado, mas não expõe o valor literal do SHA-256 do APK nativo; esse valor fica **não verificado nesta auditoria**. Os digests emitidos pelo upload do Actions são dos arquivos ZIP dos artefatos, não devem ser apresentados como hash do APK.

**Limites que continuam ativos:** o workflow oficial continua montando o APK pelo caminho híbrido Python/Flet/Flutter. A validação Gradle separada prova compilação e empacotamento do conjunto nativo, não paridade funcional integral, execução em dispositivo nem independência do runtime híbrido. O schema SQLite permanece v29; nenhuma base pessoal foi acessada. A migração integral continua pendente enquanto existirem dependências reais e fluxos ativos Python/Flet/Flutter. Esta seção registra os runs anteriores à correção documental; ela não certifica automaticamente qualquer novo commit que venha a ser criado.

## Conclusão executiva

**A migração integral ainda não pode ser certificada como concluída.** O repositório tem uma implementação Android nativa extensa: Activity e Application Android, UI Jetpack Compose, navegação, ViewModels, serviços Kotlin, acesso ao SQLite canônico, SAF/MediaStore, cliente AniList, artwork, backup/recuperação, diagnóstico e Media3/ExoPlayer. Porém, esses componentes não eliminam todos os consumidores híbridos.

A distribuição oficial continua a instalar Python 3.12, Flet 0.86.5 e Flutter 3.44.8 e executar a construção Flet do APK. O ponto de entrada Python main.py, NativeMailbox e consumidores de comandos/snapshots, telas Flet remanescentes e caminhos de compatibilidade continuam parte de fluxos reais. O APK nativo Gradle é uma verificação útil de compilação/empacotamento, mas não foi declarado substituto funcional do APK de distribuição. Não foi validado runtime físico neste trabalho.

## Estado de arquitetura encontrado

- O Manifest Android declara ReiAnixApplication, MainActivity e NativePlayerActivity. MainActivity é uma ComponentActivity nativa e ReiAnixApplication herda de Application; os testes de contrato verificam essas bases de ciclo de vida.
- ReiAnixComposeLibraryHost e ReiAnixNavigation montam a interface Compose principal; o projeto contém telas Compose para Home, Biblioteca, Detalhes, Minha Lista, Pesquisa, Configurações, Armazenamento, Organização e telas auxiliares.
- ReiAnixAppDependencies mantém uma composição de serviços por processo e ReiAnixAppBootstrap valida o banco antes de disponibilizar serviços nativos. O caminho existente preserva Recovery Mode quando a base não pode ser validada.
- O SQLite operacional canônico continua em filesDir/data/library.sqlite3, com contrato de esquema 29. Este trabalho não aumenta a versão, não cria banco paralelo e não redefine o arquivo.
- SAF, MediaStore, NativeScanController/Runner/Publisher e as operações nativas de biblioteca estão presentes. NativeMailbox/NativeCommandDispatcher ainda têm consumidores legados; por isso, varredura e projeções não são certificadas como independentes do runtime híbrido.
- AniList tem ReiAnixAniListClient e ReiAnixAniListIntegrationService Kotlin. Artwork possui ReiAnixArtworkService e extração nativa de miniaturas. Ainda há core/anilist.py, core/artwork.py, Pillow e consumidores Flet nos caminhos legados.
- Media3/ExoPlayer e NativePlayerActivity são implementações nativas reais. A presença dessas classes, isoladamente, não comprova que todos os fluxos de histórico, progresso, transições e comandos estão desconectados da mailbox.

## Correção desta etapa: evolução aditiva do SQLite existente

Foi adicionada a classe ReiAnixSqliteSchemaUpgrader para portar para Kotlin as mudanças de esquema aditivas feitas pelo LibraryStore durante a inicialização. Para um banco existente e reconhecível, a implementação:

1. abre apenas o arquivo canônico existente, sem modo de criação;
2. exige core tables e um registro de versão de schema conhecido, e verifica quick_check antes de escrever;
3. carrega o payload gerado do esquema canônico v29, cria tabelas auxiliares ausentes, adiciona apenas colunas cujas definições são seguras para ALTER TABLE e cria os índices/triggers canônicos;
4. refaz o índice derivado idx_artwork_key conforme a regra legada, normaliza o status de associação AniList e finaliza registros de scan que ficaram em estado running sem término, tudo na transação;
5. verifica o contrato da base, integridade SQLite e chaves estrangeiras antes de confirmar a transação;
6. recusa schemas desconhecidos, mais novos, corrompidos ou que precisem de uma transformação não aditiva. O chamador mantém os serviços bloqueados e encaminha para Recovery Mode.

A classe é integrada ao bootstrap nativo antes da decisão final de compatibilidade de um banco existente. A criação de um banco em instalação nova continua usando ReiAnixSqliteBootstrapper. Ambos utilizam o mesmo arquivo/caminho e a versão 29. Nenhum dado é apagado nem um banco existente é substituído.

**Limite de evidência:** o contrato Python adicionado fiscaliza a presença e o encadeamento do código-fonte. Ele não equivale a executar uma atualização de banco legado com dados reais, nem substitui um teste manual Kotlin/JVM ou a validação em dispositivo. A suíte Kotlin/JVM não deve ser executada automaticamente nem manualmente nesta etapa, conforme as restrições do projeto.

## Matriz de aceitação da auditoria-base (SHA histórico `596d5a7e1bf391a02909f4ebcd8a352b15f98348`)

| Área | Evidência disponível nesta auditoria | Estado |
|---|---|---|
| Runtime exclusivamente Kotlin | O APK oficial ainda empacota Serious Python/Flet/Flutter e o entrypoint main.py continua ativo | **Pendente — requisito obrigatório não atendido** |
| Activity e bootstrap Android | Manifest, MainActivity nativa, ReiAnixApplication e coordinator Kotlin presentes; contratos de fonte existentes; Gradle nativo e APK oficial passaram no SHA de implementação auditado | **Compilação validada; dispositivo não verificado** |
| Jetpack Compose e navegação | Shell, NavHost, rotas, telas e ViewModels Compose existem | **Parcial — existem consumidores e telas legadas** |
| Persistência canônica | Caminho library.sqlite3 e esquema v29 são compartilhados; bootstrap de instalação nova já existia; upgrader aditivo foi conectado nesta etapa | **Parcial — falta execução comportamental com banco legado** |
| SAF e permissões | Seletor, permissões persistentes, onboarding e scanners Android existem | **Parcial — coordenação/mailbox híbrida ainda presente** |
| Scanner e indexação | NativeScanController/Runner/Publisher e proteção de cobertura SAF/MediaStore já existem | **Parcial — consumidores residuais do ScanCoordinator não foram retirados** |
| AniList e metadados | Cliente e integração Kotlin existem; contratos de associação e patches SQL existem | **Parcial — compatibilidade Python permanece** |
| Capas e miniaturas | Serviço nativo e publicação incremental existem | **Parcial — ArtworkEngine/core/artwork.py ainda têm consumidores Flet** |
| Configurações | Tela, ViewModel e repositórios Kotlin existem | **Parcial — mailbox/projeções e settings Flet ainda permanecem** |
| Minha Lista, pesquisa e detalhes | Telas Compose e serviços/consultas nativos existem | **Parcial — todos os caminhos de compatibilidade não foram eliminados** |
| Organização | UI e serviços Kotlin de classificação/registro existem | **Parcial — ferramentas e consumidores Flet permanecem** |
| Backup, recuperação e diagnóstico | Serviços Kotlin com schema v29 e rota Recovery existem | **Parcial — contratos auxiliares legados continuam ativos** |
| Player, histórico e retomada | Media3/ExoPlayer, NativePlayerActivity e serviço de consumo existem | **Parcial — independência de todos os comandos/eventos não foi demonstrada** |
| Build Gradle nativo | Run [39084060927](https://github.com/reinansantos9090-debug/ReiAnix/actions/runs/39084060927) terminou com sucesso; APK e ZIP de validação gerados e manifesto/DEX/assets verificados | **Sucesso estrutural; não validação de runtime** |
| Distribuição APK/ZIP | Run [39084060944](https://github.com/reinansantos9090-debug/ReiAnix/actions/runs/39084060944) terminou com sucesso e publicou APK/ZIP; a cadeia oficial continua usando Python/Flet/Flutter | **Build verde, mas distribuição ainda híbrida** |
| Regressão Python | Run 39084060944: **1.992 testes passaram e 58 subtests passaram** em 64,22 s, incluindo os contratos de fonte do Prompt 29 | **Sucesso** |
| Testes Kotlin/JVM | Workflow workflow_dispatch existente; execução manual não permitida nesta tarefa | **Não executado conforme a restrição** |
| Testes instrumentados/dispositivo | Nenhum teste de emulador foi adicionado/executado | **Não verificado em dispositivo** |
| Código residual | 219 arquivos Python estavam na árvore examinada antes desta alteração; muitos são testes/tooling, outros são runtime e caminhos Flet reais | **Classificado por função, ainda não eliminado globalmente** |
| CI da implementação auditada | SHA `596d5a7e1bf391a02909f4ebcd8a352b15f98348`; runs 39084060927 (Gradle nativo) e 39084060944 (APK oficial) concluídos | **Ambos com conclusão `success`** |

## Runtime, build e fontes — três resultados diferentes

**Runtime:** não independente. O caminho oficial continua carregando a infraestrutura híbrida e consumidores Python/Flet essenciais para paridade funcional.

**Build:** existe um workflow Gradle nativo separado que produz um APK de validação sem instalar Python/Flet/Flutter, e verifica a estrutura/ZIP do artefato. Isso prova que o conjunto de fontes nativas pode ser compilado por esse caminho, não que o APK tenha paridade funcional completa com o APK oficial.

**Código-fonte:** Python está presente tanto em testes/tooling quanto em entrypoint e módulos com consumidores de runtime. Não foi removido em massa porque isso quebraria fluxos ainda ativos e não atenderia ao requisito de preservar funcionalidades.

## Corte adicional de emissores híbridos da UI Compose

A alteração anterior retirou do adapter Kotlin os emissores de `set_watched`, `select_saf`, `dismiss_storage_onboarding` e `remove_saf`. Este corte também elimina do adapter Kotlin o produtor `load_library_page`, a página IPC de estado e sua aplicação na UI. O ViewModel ativo consulta as páginas via `ReiAnixLibraryQueryService` no SQLite canônico. Os handlers/projetores Python para todas essas ações antigas foram preservados para drenar mensagens duráveis de versões anteriores e superfícies legadas; não foram eliminados globalmente.

O contrato em `tests/test_prompt29_native_schema_upgrade_contract.py` verifica a ausência dos produtores Kotlin e a permanência dos receivers de compatibilidade. Nenhum teste Kotlin/JVM foi executado manualmente, e nenhum teste instrumentado/emulador foi criado ou executado.


## Verificações históricas do baseline `596d5a7e1bf391a02909f4ebcd8a352b15f98348`

No SHA de implementação auditado `596d5a7e1bf391a02909f4ebcd8a352b15f98348`, os dois workflows obrigatórios terminaram oficialmente com sucesso: Gradle nativo [run 39084060927](https://github.com/reinansantos9090-debug/ReiAnix/actions/runs/39084060927) e build oficial [run 39084060944](https://github.com/reinansantos9090-debug/ReiAnix/actions/runs/39084060944). A suíte Python registrou **1.992 testes e 58 subtests aprovados**. O APK oficial publicado foi `ReiAnix-apk-instalacao-direta-6039` (33.073.120 bytes), o ZIP oficial `ReiAnix-ZIP-extraia-primeiro-6039` (33.023.539 bytes), o APK Gradle `ReiAnix-native-gradle-apk-75` (26.960.971 bytes) e o ZIP Gradle `ReiAnix-native-gradle-ZIP-75` (26.904.439 bytes). SHA-256 do APK oficial: `d5e80bd4ef53fee0a658b27052ebf56af377dfaa4cfa6867b85568b9d04507c9`. Isso valida compilação, empacotamento e regressão para o código auditado; não prova runtime exclusivamente Kotlin, execução em dispositivo nem migração comportamental de um banco pessoal existente.

Nenhuma base pessoal real foi acessada ou alterada. Nenhum APK foi instalado/executado em dispositivo nesta auditoria. Não foi acionado o workflow Kotlin/JVM, nenhum teste instrumentado foi introduzido e a suíte Kotlin/JVM não foi executada manualmente. O build Gradle é uma validação de compilação/empacotamento; não deve ser descrito como teste de comportamento de usuário.

## Critério de conclusão global

O Prompt 29 só pode declarar a migração concluída depois de retirar ou substituir com paridade os consumidores reais de main.py/NativeMailbox/Flet/Flutter, estabelecer o caminho oficial de build/distribuição nativo, validar operações com fixtures apropriadas e confirmar os workflows obrigatórios no SHA final. Essas lacunas não podem ser encerradas honestamente apenas com a presença dos serviços Kotlin.

**Conclusão desta auditoria:** há progresso nativo concreto e foi corrigida a lacuna de atualização aditiva do schema SQLite existente, mas a migração total do runtime e da distribuição ainda não foi comprovada. A PR #194 deve permanecer em rascunho e sem merge até que essas lacunas sejam efetivamente resolvidas ou aceitas explicitamente como limite do produto.


## Registro histórico de validação do corte de paginação IPC — parent SHA `d5bec88401ec2c8fc36415bf8580cc39443ca8ef` (não é o HEAD atual)

A sequência mais recente removeu de `ReiAnixLibraryRepository` a produção de `load_library_page`, o `StateFlow` paginado duplicado e a aplicação de respostas de páginas na UI do adapter. A paginação Compose permanece no ViewModel Kotlin, que consulta `ReiAnixLibraryQueryService.searchAnime()` diretamente no SQLite canônico. O adapter ainda decodifica resultados antigos de paginação e os descarta, e os handlers/projetores Python continuam preservados para comandos duráveis de versões anteriores. Não se declara a retirada global da ponte híbrida.

A suíte tinha uma asserção antiga que exigia o limite de page size dentro do adapter removido. Ela foi corrigida para validar `val normalizedPageSize = pageSize.coerceIn(12, 72)` no `ReiAnixLibraryViewModel`, onde a regra continua ativa. Para o SHA `d5bec88401ec2c8fc36415bf8580cc39443ca8ef`, os dois workflows terminaram oficialmente com sucesso:

- Build oficial APK [run 39087964545](https://github.com/reinansantos9090-debug/ReiAnix/actions/runs/39087964545): **1.993 testes e 58 subtests aprovados**; geração do APK, compilação explícita Kotlin de release e verificações de pacote aprovadas. APK publicado: [`ReiAnix-apk-instalacao-direta-6044`](https://github.com/reinansantos9090-debug/ReiAnix/actions/runs/39087964545/artifacts/12120794923), 33.070.979 bytes. ZIP: [`ReiAnix-ZIP-extraia-primeiro-6044`](https://github.com/reinansantos9090-debug/ReiAnix/actions/runs/39087964545/artifacts/12121173768), 33.021.262 bytes. SHA-256 do APK: `71e7a7828117c72eecf5931f5d6edf21b9b758fd8daa8767ede5eb8899ed6797`.
- Validação Gradle nativa [run 39087964542](https://github.com/reinansantos9090-debug/ReiAnix/actions/runs/39087964542): **sucesso, 14/14 etapas concluídas**. APK de validação [`ReiAnix-native-gradle-apk-80`](https://github.com/reinansantos9090-debug/ReiAnix/actions/runs/39087964542/artifacts/12121337578), 26.948.746 bytes. ZIP [`ReiAnix-native-gradle-ZIP-80`](https://github.com/reinansantos9090-debug/ReiAnix/actions/runs/39087964542/artifacts/12120794299), 26.892.981 bytes.

Esses resultados provam regressão Python, compilação/empacotamento oficial e compilação/estrutura do APK Gradle para o SHA citado. Não provam que o APK oficial seja independente de Python/Flet/Flutter nem que um banco pessoal existente tenha passado por uma migração comportamental em dispositivo. Não foram executados testes Kotlin/JVM manuais, nem testes instrumentados/emulador.

**Estado global:** a migração integral permanece pendente. O entrypoint `main.py`, a distribuição oficial híbrida, consumidores reais de Flet e várias operações de mailbox/projeção permanecem; a PR deve continuar em rascunho e sem merge até que a paridade nativa completa seja demonstrada.
