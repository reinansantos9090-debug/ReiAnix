# Arquitetura Kotlin nativa e migração incremental — ReiAnix

## 1. Estado verificado desta referência

- Repositório: `reinansantos9090-debug/ReiAnix`.
- Branch inspecionada: `main`.
- Commit de referência antes desta documentação: `a8ef38f4802d4c803e12652edcd507acd1143bd0`.
- Os documentos formais docs/KOTLIN_MIGRATION_AUDIT.md e docs/KOTLIN_MIGRATION_ROADMAP.md foram produzidos no Prompt 01 e estão incluídos na branch de implementação baseada na head do PR #166; a main usada como baseline antes desse PR ainda não os continha. O PR #166 permanece uma revisão separada e não foi mesclado por esta etapa.
- O projeto Android ainda é um overlay do host gerado pelo Flet. **A presença de muitas classes Kotlin e de telas Compose não significa que o aplicativo já seja independente de Python/Flet/Flutter.**
- A versão de esquema declarada por `core/library_store.py` é `LibraryStore.SCHEMA_VERSION = 29`. Esta etapa não altera esquema, registros ou arquivos de dados.

Este arquivo é a referência arquitetural para os próximos prompts. Quando o código mudar, atualize este documento com evidências da branch e do commit correspondentes; não trate esta descrição como verdade permanente sem nova inspeção.

## 2. Arquitetura observada

O código já tem uma separação física útil por pacotes Kotlin. A tarefa desta etapa é formalizar os limites reais, identificar inversões e dependências de transição e orientar a migração sem introduzir uma segunda implementação.

| Área atual | Componentes reais | Responsabilidade atual e limite |
|---|---|---|
| Entradas Android | `MainActivity.kt`, `NativePlayerActivity.kt`, `PerformanceDiagnostics.kt` | `MainActivity` herda de `FlutterFragmentActivity` e é substituída no host gerado pelo Flet. Ela coordena ciclo de vida e APIs Android, integra scanner, bridge e host Compose; é grande e ainda concentra múltiplas integrações. `NativePlayerActivity` é a única Activity de reprodução Media3. |
| UI Compose | `ui/`, `ui/navigation/ReiAnixNavigation.kt`, `ui/host/ReiAnixComposeLibraryHost.kt` | Telas, componentes, rotas, shell visual e modelos de apresentação já existem. O host monta Compose dentro do host Flutter; não constitui ainda um launcher Android independente. |
| Estado da UI | `viewmodel/ReiAnixLibraryViewModel.kt`, `ReiAnixSettingsViewModel.kt`, factories e `ReiAnixViewModel.kt` | ViewModels e `StateFlow` já existem. O ViewModel de biblioteca é amplo e importa modelos/operadores da camada `ui`; alguns limites entre estado de tela, consulta e coordenação ainda precisam ser separados de forma incremental. |
| Projeção de dados Kotlin | `data/library/ReiAnixLibraryRepository.kt`, `ReiAnixLibrarySnapshotCodec.kt`, `ReiAnixLibraryCommandCodec.kt`; `data/settings/ReiAnixSettingsRepository.kt`, `ReiAnixSettingsSnapshotCodec.kt` | Repositórios observam arquivos JSON privados e coordenam comandos pela `NativeMailbox`. Esses JSON são projeções/IPC; não são um banco canônico e não substituem `LibraryStore`/SQLite. Os repositórios ainda importam modelos de UI e o de biblioteca usa lógica de filtro de `ui/library`, um limite de dependência a corrigir durante a migração, sem trocar a fonte da verdade agora. |
| Bridge | `bridge/NativeMailbox.kt`, `NativeCommandDispatcher.kt`, `NativeRequestState.kt`, `GoogleIdentity.kt` | Transporte de eventos/comandos, deduplicação e estados de request, execução de comandos internos de player/thumbnail e integração Credential Manager. Não deve virar camada de domínio nem fonte de dados persistente. |
| Plataforma/armazenamento | `storage/StorageAuthorization.kt`, `NativeBatch.kt`, `NativeIndex.kt`, `VideoThumbnailExtractor.kt` | Permissões, batching, staging e identidade de itens detectados nativamente e thumbnails. O snapshot/índice nativo não equivale ao catálogo SQLite canônico. |
| Descoberta | `scanner/SafScanner.kt`, `MediaStoreScanner.kt`, `BroadStorageScanner.kt`, `NativeScanController.kt`, `NativeScanRunner.kt`, `NativeScanPublisher.kt`, `MediaStoreRetryScheduler.kt` | Implementação Android de SAF, MediaStore e armazenamento amplo, cancelamento/progresso/publicação. A consolidação do scan e a atualização do catálogo ainda cruzam a mailbox e a camada Python. |
| Player | `player/NativePlayerRequest.kt`, `PlayerLocalMetadataStore.kt`, `PlayerMediaPolicy.kt`, `LocalSubtitleResolver.kt`, `SystemUiController.kt`, `DeviceInteractionProfile.kt`, `PlayerStartupMetrics.kt`, `PlayerTimeFormatter.kt`; `NativePlayerActivity.kt` e UI de player | Player Media3 e políticas Android já estão no Kotlin. O contrato de reprodução está em `NativePlayerRequest`; progressos/eventos de sessão ainda precisam ser acompanhados até a persistência canônica no fluxo Python. |
| Gradle/toolchain | `android/settings.gradle.kts`, `android/build.gradle.kts`, `android/app/build.gradle.kts` | Um módulo `:app`, Kotlin 2.2.10, AGP 8.9.1, Java 17, SDK 36, Compose, Navigation, Lifecycle, corrotinas e Media3. Não foi identificada justificativa para novos módulos Gradle ou dependências nesta etapa. |

### Composição nativa introduzida pelo Prompt 02

O código agora registra ReiAnixApplication, que herda de FlutterApplication e preserva a inicialização híbrida, e expõe um objeto ReiAnixAppDependencies por processo. A composição é manual e lazy: ReiAnixSqliteDatabase, o repositório canônico, os leitores de Biblioteca/Configurações, o serviço AniList e o serviço de consumo do player são reutilizados pelos consumidores conectados. NativeScanRunner usa o repositório compartilhado para a indexação de snapshots SAF. Nenhum scan, download ou acesso ao banco é iniciado pelo Application.onCreate.

As projeções JSON/IPC da Biblioteca e das Configurações continuam no escopo dos ViewModels para que os observers sejam fechados com o lifecycle correto. Seus comandos legados ainda são publicados por NativeMailbox e permanecem sob o writer Python até uma etapa futura demonstrar paridade. O launcher MainActivity : FlutterFragmentActivity, a navegação Compose, o shell único, o fluxo de permissões e o player Media3 não foram substituídos por uma arquitetura paralela. O grafo compartilha o lock por instância SQLite entre os consumidores nativos integrados; a coordenação global entre o writer Kotlin e o writer Python continua aberta e é um requisito para a migração de cada operação.

### Fluxos reais, não o desenho-alvo

**Biblioteca e configurações hoje**

```text
Compose
  -> ViewModel
  -> repositório Kotlin de projeção
  -> JSON privado / NativeMailbox (comandos)
  -> main.py / bridges Python
  -> LibraryService ou SettingsStore
  -> LibraryStore
  -> SQLite (fonte canônica)
```

O repositório Kotlin consegue expor estado observável e emitir comandos, mas não deve ser descrito como repositório nativo definitivo enquanto depender de projeções produzidas por Python.

**Descoberta Android hoje**

```text
MainActivity / APIs Android
  -> NativeScanController / NativeScanRunner
  -> SAF, MediaStore ou BroadStorageScanner
  -> NativeIndex (lotes e snapshot de staging)
  -> eventos e resultados pela bridge/mailbox
  -> coordenação/ingestão Python
  -> LibraryService / LibraryStore
  -> SQLite
```

O scanner nativo e o staging por geração já existem. O snapshot anterior deve permanecer intacto em scans parciais, cancelados, indisponíveis ou falhos; somente resultados terminais completos autorizam a reconciliação correspondente.

**Reprodução hoje**

```text
comando de reprodução
  -> NativeCommandDispatcher / NativePlayerRequest
  -> NativePlayerActivity + Media3
  -> eventos de progresso/finalização/saída
  -> NativeMailbox
  -> processamento Python e persistência canônica
```

`NativePlayerActivity` continua sendo o único player Android. Não criar outro player ou outra máquina de estado de reprodução para contornar a integração atual.

**Contrato de arquitetura-alvo**

```text
Compose screen
  -> ViewModel (StateFlow e eventos)
  -> operação/caso de uso de domínio quando trouxer valor
  -> contrato de repositório
  -> implementação de dados
  -> SQLite/API/cache ou adapter de plataforma
```

O fluxo-alvo é uma direção de dependência, não uma exigência de criar uma classe para cada chamada. Até a implementação de dados nativa assumir e validar determinada responsabilidade, deve permanecer explícito o adapter para a implementação Python; não se deve ocultá-lo atrás de um contrato falsamente nativo.

## 3. Limites e decisões arquiteturais

### Apresentação

- `ui/` contém Compose, rotas e modelos exclusivamente de apresentação.
- Composables não devem executar SQL, varreduras ou regras de biblioteca.
- O shell usa Navigation Compose. Não acrescentar outro grafo de navegação.
- O host atual usa `collectAsStateWithLifecycle` onde já integrado e o módulo possui Lifecycle/Coroutines. Novos estados devem continuar observáveis e respeitar o lifecycle.
- Não alterar identidade visual neste prompt.

### ViewModels e coordenação

- Reutilizar `ReiAnixLibraryViewModel` e `ReiAnixSettingsViewModel`; não criar equivalentes paralelos.
- ViewModels coordenam eventos e estado de tela, não definem uma segunda regra de domínio nem passam a executar APIs de armazenamento diretamente.
- Antes de dividir o ViewModel de biblioteca, mapear consumidores e manter filtros, ordenação, paginação, busca, detalhes, Home, My List e Organize funcionando.
- A dependência atual de alguns ViewModels em tipos de `ui/model` é um contrato de apresentação, não um modelo de domínio. Na migração, os modelos de domínio devem ficar independentes de Compose e ser mapeados para UI na borda.

### Domínio

- O repositório ainda não tem uma camada `domain/` Kotlin com modelo canônico de biblioteca. Não criar pacote vazio nem interfaces sem consumidor.
- Regras hoje implementadas em Python devem ser migradas por fatia, com entrada, saída, invariantes e efeitos colaterais conhecidos e testes de paridade.
- Durante o período de transição, classes Kotlin como `NativeIndex` e `PlayerMediaPolicy` representam responsabilidades nativas específicas; elas não substituem automaticamente as regras de identidade, ingestão ou persistência do domínio Python.
- Somente introduzir caso de uso/serviço de domínio quando desacoplar uma regra real ou permitir teste independente; não criar camadas intermediárias mecânicas.

### Dados e contratos

- `LibraryStore` em Python e o SQLite existente continuam sendo a autoridade do catálogo/configurações enquanto os fluxos nativos dependerem deles.
- `ReiAnixLibraryRepository` e `ReiAnixSettingsRepository` são adapters de projeção/IPC na arquitetura atual. Os JSON e codecs são contratos de transporte; não são um contrato de repositório de domínio definitivo.
- Não introduzir Room, DataStore ou um segundo SQLite sem decisão explícita de migração, comparação de compatibilidade e plano de preservação do banco existente.
- Não alterar o esquema neste prompt. A versão observada permanece 29.
- Em cada cutover futuro, identificar a fonte canônica, a direção de escrita, a leitura de fallback e a condição objetiva para remover a bridge. Nunca manter dois escritores canônicos concorrentes.

### Plataforma Android

- SAF, URIs `content://`, permissões persistentes, MediaStore, broad storage, intents, Credential Manager, ciclo de vida e PiP pertencem aos componentes de integração Android existentes, não aos Composables nem ao domínio puro.
- Não converter URI SAF em caminho POSIX fictício. Preservar a autorização persistente concedida pelo usuário.
- Scanners usam staging/gerações e resultados terminais. Varreduras incompletas não podem causar exclusão ou reconciliação destrutiva de catálogo.
- O player e o lifecycle do Android devem continuar centralizados nos componentes já existentes.

### Diagnósticos

- `PerformanceDiagnostics`, `DiagnosticTimeline` e `DiagnosticsService` têm papéis distintos: observabilidade/runtime, timeline de hand-off e relatório técnico. Não criar pipeline de telemetria ou estado de domínio duplicado.
- `NativeRequestState` correlaciona operações e lifecycle de requests; não deve ser usado como armazenamento de estado persistente da UI.

## 4. Mapeamento das responsabilidades Python

A coluna “candidato Kotlin” identifica componentes existentes que podem participar da futura migração, não afirma equivalência funcional completa. Onde não há substituto Kotlin de domínio verificado, isso fica explícito.

| Módulo Python existente | Entradas, saídas e efeitos atuais | Kotlin reutilizável / estado da migração | Lacuna e risco de compatibilidade |
|---|---|---|---|
| `main.py` | Composition root Flet; inicializa `LibraryStore`, recuperação, serviços, bridges, rotas e views; processa eventos e conecta subsistemas. Efeitos: lifecycle UI, banco, jobs, mailbox e rede por serviços. | `MainActivity`, `ReiAnixComposeLibraryHost`, Navigation Compose e ViewModels já hospedam áreas nativas. | O runtime e vários fluxos ainda dependem do processo Python/Flet. A substituição só pode ocorrer quando todas as operações e fluxos equivalentes estiverem integrados e verificados. |
| `core/library_service.py` | Orquestra ingestão de documentos, identidade, parsing, metadados, artwork, pesquisa/projeções e interação com persistência. Entrada: documentos/itens de mídia e comandos; saída: catálogo e estados/projeções; efeitos: SQLite, tarefas assíncronas e serviços externos. | Scanners Android, `NativeIndex`, ViewModel e repositório Kotlin de projeção cobrem partes do fluxo, não toda a regra de serviço. | Falta um serviço Kotlin canônico que ingira e reconcilie com as mesmas invariantes. Risco alto para duplicatas, metadados, identidade, progresso e remoções indevidas. |
| `core/library_store.py` | Persistência SQLite, transações, tabelas, migrações, consultas e escrita do catálogo/configurações; inclui `SCHEMA_VERSION = 29`. | Repositórios Kotlin atuais leem projeções e enviam comandos; `NativeIndex` é staging/índice auxiliar, não substituto SQLite. | Não há evidência de repositório SQLite nativo canônico equivalente. Qualquer transição deve preservar IDs, relacionamentos, caminhos/URIs, preferências, histórico, progresso e backups. |
| `core/library_parser.py` | Parsing determinístico de nomes/extensões de mídia; produz metadados candidatos sem persistir por si só. | `NativeIndex.stableIdentity` resolve identidade de indexação, não equivale ao parser de episódios. | Parser Kotlin equivalente ainda precisa de paridade de casos/saídas; diferenças podem agrupar episódios errados. |
| `core/media_identity.py` | Deriva identidade estável entre file URI, MediaStore e SAF usando volume/relative path/tree URI. | `NativeIndex.stableIdentity` trata identidade no pipeline nativo, sob outro contrato. | Unificar regras e casos de borda antes de declarar equivalência: volumes, provedores genéricos, URIs SAF e caminhos ambíguos podem gerar duplicatas se as regras divergirem. |
| `core/consumption.py` | Regras puras de progresso, estado de consumo e conclusão a partir de progresso/duração e flags existentes. | O player nativo emite progresso e tem store local de metadata; a biblioteca apresenta estados derivados. | Regra canônica de conclusão deve ter paridade antes de migrar persistência, evitando divergência entre “assistido”, progresso e retomada. |
| `core/search_engine.py` | Normalização, busca local, filtro/ordenação e critérios sobre a projeção do catálogo; sem necessidade de abrir mídia ou chamar AniList. | `ui/search`, `ui/library`, `ui/mylist` e lógica de filtro Kotlin já alimentam telas Compose. | A lógica de UI/filter não equivale necessariamente ao contrato Python. Validar semântica, acentos, aliases, especiais, filtros pessoais e ordenação. |
| `core/settings.py` | Defaults, tipos, validação e persistência lógica de configurações pela autoridade `LibraryStore`. | `ReiAnixSettingsViewModel`, `ReiAnixSettingsRepository` e codecs são camada de estado/projeção. | Validação e gravação ainda precisam ser feitas pela autoridade Python. Não substituir por DataStore nem permitir dois lugares de escrita. |
| `core/anilist.py` | Cliente GraphQL, identificação/enriquecimento de anime, limite/retry de rede e cache de tradução/metadados para rotas Flet/legadas. | `ReiAnixAniListClient`, `ReiAnixAniListIntegrationService` e `ReiAnixAniListDescriptionLocalizer` atendem os consumidores nativos Compose; associação/metadata persistem no repositório SQLite canônico. | O cliente e o fluxo Python permanecem em caminhos híbridos legados; ArtworkEngine também segue Python. Manter paridade de IDs, rate limit, cache e sinopses, sem declarar runtime 100% Kotlin antes do corte desses consumidores. |
| `core/artwork.py` | Resolução e cache persistente de capas/imagens, downloads limitados, retries e deduplicação. | `ui/artwork/ReiAnixLocalArtwork.kt` renderiza/resolve arte local na apresentação; `VideoThumbnailExtractor` é outro caminho para thumbnails locais. | Renderização e extração nativa de thumbnails não substituem o motor de artwork/cache. Evitar caches divergentes, requests obsoletos e perda de capas offline. |
| `core/backup.py` | Exporta/valida/restaura backup versionado do estado lógico; exclui vídeo, cache temporário e credenciais. Efeitos: arquivos e operações de recuperação coordenadas. | Há integração Android e telas; não foi identificado serviço de backup Kotlin equivalente em autoridade. | Manter formato, validação, conteúdo excluído e segurança de restore compatíveis. Não trocar a base de persistência como atalho. |
| `core/diagnostics.py` | Timeline limitada de hand-offs Python/Android com IDs de request/scan, origem, resultado, contagens e erro. | `NativeMailbox`, `NativeRequestState` e `PerformanceDiagnostics`. | Preservar correlação de IDs e assinatura real dos eventos; timeline não deve se tornar fonte de verdade. |
| `core/diagnostic_service.py` | Gera relatório técnico sanitizado a partir de store/timeline; redige caminhos e limita dados sensíveis. | `PerformanceDiagnostics` registra métricas Android; não equivale ao relatório agregado Python. | Exportação nativa equivalente ainda não verificada; preservar redação e limites de privacidade. |
| `core/scan_coordinator.py` | Possui agenda lógica, prioridades, deduplicação, transições de estado, cancelamento e conclusão de scans. | `NativeScanController`, `NativeScanRunner`, `NativeScanPublisher` coordenam execução Android. | Execução de scanner não substitui a coordenação do catálogo. Evitar corridas entre fontes, scans concorrentes e commits incompletos. |
| `core/storage_access.py` | Normaliza snapshot de capacidades/permissões, identidade e estado de SAF, MediaStore e broad storage para onboarding/configurações. | `StorageAuthorization`, scanners e telas Compose de storage refletem estado Android. | Android é autoridade sobre grants; snapshots Python são representação/orquestração. Preservar grant persistente e diferenças de acesso parcial por versão do Android. |
| `core/library_discovery.py` | Funções locais derivadas: duração, anomalias, agrupamentos de timeline e planejamento; sem persistência/rede própria. | Componentes UI e ViewModel de Home/estatísticas consomem projeções. | Algoritmos Kotlin equivalentes devem ser adicionados somente quando um fluxo nativo passar a precisar deles e acompanhados de testes de paridade. |
| `core/recovery.py` | Diagnostica inconsistência, cria snapshot de segurança e restaura backup validado sob invariantes de integridade SQLite. | A Activity e as telas podem iniciar ações, mas não há substituto de recuperação SQLite canônica verificado no Kotlin. | Não restaurar/destruir banco por uma abstração provisória. Manter validação SQLite/foreign keys, snapshot e critérios de recusa. |
| `core/google_account.py` | Valida evento e limita perfil persistido a ID, nome, e-mail e foto; rejeita payload inválido e tokens. | `bridge/GoogleIdentity.kt` usa Credential Manager; `NativeMailbox` transporta o evento. | Há validação nos dois lados com funções distintas. Preservar campos permitidos; nunca transportar tokens/segredos pela mailbox. |
| `core/navigation.py` | Política legada independente de UI para stack, Back, telas superiores e navegação interna de Settings. | `ui/navigation/ReiAnixNavigation.kt` e host Compose mantêm as rotas atuais nativas. | Durante a transição podem coexistir políticas para fluxos diferentes; mapear consumidores antes de remover a navegação Python ou duplicar estado de back stack. |
| `core/android_bridge.py` | Adapta pedidos Python para intents/deep-links e APIs do host Android. | `MainActivity`, `NativeCommandDispatcher` e scanners executam as operações Android. | Bridge continua necessária para callers legados. Remover comandos só após procurar chamadas, callbacks, fallbacks e testes. |
| `core/compose_library_bridge.py` | Publica projeção do catálogo e recebe comandos Compose estreitos; não possui banco. | `ReiAnixLibraryRepository`, codecs e mailbox espelham estado/comandos. | `library.json` segue sendo projeção derivada; não declarar escrita Kotlin como persistência canônica até o backend substituir o consumidor Python. |
| `core/compose_settings_bridge.py` | Publica projeções de configurações e encaminha atualizações permitidas. | `ReiAnixSettingsRepository` e codecs. | Preservar validação e persistência pela autoridade existente até o cutover verificado. |
| `core/google_account.py` / `app_config.py` | Configuração pública de OAuth e sanitização do perfil recebido; segredos não devem ir para o APK. | `GoogleIdentity`/Credential Manager e `BuildConfig`/config atual. | Preservar IDs OAuth públicos e não empacotar client secret ou tokens. |
| `core/performance.py`, `core/build_identity.py` | Métricas, contadores e identificação do build usados transversalmente. | `PerformanceDiagnostics.kt` e informações do host Android. | Evitar dois coletores globais de telemetria; manter a distinção entre métricas de cada runtime. |
| `core/organizer_ai.py`, `core/genre_classifier.py`, `core/genre_registry.py`, `core/collector_journey.py` | Associação/organização de títulos, classificação/registro de gêneros e cálculo da jornada/coleção. | Telas de detalhes, organização, Home e My List exibem esses dados; não foi identificado substituto Kotlin completo para a lógica. | Migrar só com regras de identidade e metadados estabelecidas e testes de paridade; preservar etiquetas, pins, notas e favoritos pessoais. |

### Contratos já existentes que devem ser mantidos

- Transporte Python/Android: `NativeMailbox`, `NativeCommandDispatcher`, `NativeRequestState` e o contrato descrito em [NATIVE_BRIDGE_CONTRACT.md](NATIVE_BRIDGE_CONTRACT.md).
- Projeção e comandos da biblioteca: `ReiAnixLibrarySnapshotCodec` e `ReiAnixLibraryCommandCodec`.
- Projeção de configurações: `ReiAnixSettingsSnapshotCodec`.
- Entrada do player: `NativePlayerRequest`.
- Identidade, lotes e scans: contratos reais de `NativeIndex`, `NativeBatch`, scanners e eventos correspondentes.
- O formato dos eventos JSON é um contrato de integração transitório, não uma API de domínio. Renomear campos ou IDs exige revisão de todos os consumidores Python e Kotlin e dos fluxos já persistidos/em fila.

## 5. Caminho incremental recomendado

A ordem precisa ser revalidada a cada prompt. O objetivo é migrar uma responsabilidade por vez e não avançar para uma etapa posterior sem validação da anterior.

1. **Contratos e paridade:** manter uma matriz por fluxo com dono atual, contrato, IDs, leituras/escritas, consumidor legado e critérios de conclusão. Começar por regras puras que não abrem arquivos nem fazem rede: identidade, parsing, consumo e normalização/pesquisa.
2. **Modelos de domínio Kotlin verdadeiros:** introduzir modelos independentes da UI apenas para a primeira fatia escolhida e testada. Mapear separadamente para modelos de UI; não copiar catálogos inteiros nem criar repositórios fictícios.
3. **Persistência compatível:** documentar tabelas, chaves, índices, restrições, preferências, histórico/progresso e migrações do schema 29; criar testes de leitura/escrita contra cópias descartáveis do banco. Não resetar nem recriar a base do usuário.
4. **Repositório nativo por fatia:** só depois da paridade, adicionar uma implementação real que execute leituras e transações necessárias. Definir explicitamente a fonte canônica e a estratégia de cutover; nunca permitir duas fontes de escrita concorrentes.
5. **Serviços e enriquecimento:** migrar a sequência apropriada de identidade/ingestão, busca/consumo, configurações, metadados AniList, artwork/cache, backup e diagnósticos, respeitando dependências e sem perder comportamento offline.
6. **Integração Android:** integrar SAF/MediaStore/broad storage, permissões, URI grants, geração de scans, cancelamento, retry e conclusão com o repositório já escolhido. Reutilizar os scanners existentes e manter a regra de não reconciliar scans incompletos.
7. **Player e estado de consumo:** preservar o único player Media3, o contrato de request/session/media e a retomada; mover persistência de progresso apenas quando o backend substituto for validado.
8. **ViewModels e UI:** retirar dos ViewModels lógica de domínio já migrada, manter estado observável, mapear domínio → UI e integrar loading/empty/error sem alterar a experiência estabelecida.
9. **Host independente:** só substituir o host Flet/Flutter depois de comprovar que todas as telas e operações necessárias têm implementações Kotlin integradas, persistência compatível e nenhum fluxo essencial dependente do runtime Python.
10. **Eliminação de bridges/runtime legado:** procurar referências, entradas de mailbox, eventos, scripts/workflows e fallbacks; remover uma bridge apenas após provar que todos os consumidores foram migrados.
11. **Certificação final:** build APK verificado, testes relevantes e validação física dos fluxos que dependem das APIs Android. Uma compilação isolada não prova independência de runtime nem comportamento SAF/Media3.

## 6. Invariantes de compatibilidade

- Não alterar o schema 29 nesta etapa e não apagar/recriar dados para simplificar a migração.
- Preservar IDs estáveis de anime, episódio e mídia; não usar título, índice visual ou número do episódio como chave quando houver identidade estável.
- Preservar `content://` URIs e permissões SAF persistentes; não derivar caminhos locais fictícios.
- Não permitir que scan `PARTIAL`, `CANCELLED`, `UNAVAILABLE` ou `FAILED` remova entradas previamente confirmadas.
- Preservar progresso, duração, status assistido, retomada, autoplay e correlação de sessão/player.
- Preservar configurações, favoritos, pins, notas, etiquetas pessoais, metadados e capas/cache.
- O backup/restore deve continuar validando conteúdo e integridade antes de substituir estado.
- Tokens OAuth, client secrets e credenciais não podem ser enviados pelos contratos de eventos.
- Manter `MainActivity`/manifesto e player nos pacotes reais enquanto os workflows e o host Flet exigirem esses nomes.

## 7. Testes, build e observabilidade

- Já existe `tests/test_architecture.py`, que verifica os pacotes dos componentes Android separados e mantém as Activities no pacote de entrada. A suíte Python inclui testes de contratos/serviços.
- Há testes Kotlin/JVM e testes instrumentados existentes no repositório. Este prompt não adiciona testes de emulador, jobs, scripts ou workflows e não altera gatilhos de CI.
- `.github/workflows/build_apk.yml` executa no push em `main`, PR e manualmente; o build depende do overlay Android do host Flet e da verificação do APK em DEX.
- `.github/workflows/kotlin_jvm_tests.yml` está configurado para execução manual e não foi alterado nesta etapa.
- `android/` não é, conforme `android/README.md`, um projeto Android standalone com todos os artefatos do host: `FlutterFragmentActivity` é fornecida pelo host gerado. Portanto, `gradle :app:compileDebugKotlin` isolado não é um teste válido do projeto integrado.
- Build, teste e validação física são evidências diferentes. Para qualquer operação não executada ou run ainda pendente, reportar o estado verdadeiro; não inferir sucesso a partir da existência de código.
- Este prompt não altera dependências Gradle, workflows, scanner, player, permissões nem tabelas.

## 8. Critérios para o Prompt 3

Antes de implementar a primeira fatia funcional da migração, selecionar a partir do próximo prompt (sem assumir antecipadamente qual será a tarefa) o domínio afetado e registrar: contratos de entrada/saída, estado persistido, invariantes, fontes canônicas, chamadas/consumidores Python/Kotlin, testes de paridade e estratégia para fallback/cutover. Não implementar a migração completa da biblioteca, banco ou player de uma só vez.

A arquitetura desta etapa está documentada e ligada ao código existente. A migração integral não está concluída: Python/Flet/Flutter continuam sendo dependências operacionais e a persistência canônica continua em `LibraryStore`/SQLite no fluxo Python.

## Prompt 10.1 — leitura SQLite consumida pela tela de Armazenamento

A rota `ReiAnixStorageRoute` agora observa a consulta nativa de `folders` por meio de `ReiAnixLibraryViewModel.nativeFoldersState`. Essa seção é uma leitura de diagnóstico dos registros persistidos no SQLite canônico; ela não controla grants SAF nem substitui o estado operacional publicado pela ponte atual. Resultados vazios e falhas são estados distintos, e a ação de nova consulta cancela a consulta anterior. O catálogo e as telas Home/Library/Details/My List/Search continuam usando a projeção atual enquanto a conversão de todos os campos e contratos de domínio não for demonstrada. Não houve mudança no esquema nem no escritor Python.
