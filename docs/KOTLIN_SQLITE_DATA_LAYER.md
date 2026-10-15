# Camada de dados SQLite nativa — Prompt 03/29

## Referência desta implementação

- Repositório: `reinansantos9090-debug/ReiAnix`.
- Branch de trabalho: `implementation/prompt-03-sqlite-data-layer-20261015`.
- SHA de referência da `main` no início: `4b42eea36bdeaeea55c4bba3a2f36d4fedc8f1cb`.
- Fonte canônica do contrato: `core/library_store.py`, com `LibraryStore.SCHEMA_VERSION = 29`.
- Arquivo SQLite nativo: `filesDir/data/library.sqlite3`, identificado por `ReiAnixSqliteDatabase.DATABASE_FILE_NAME = "library.sqlite3"`.
- Sem migração de schema neste trabalho: não há nova versão, tabela, índice, banco, Room ou cópia do catálogo.

Este documento descreve o estado de código deste branch. Ele não afirma que todos os escritores Python já foram removidos do aplicativo.

## 1. Banco canônico e schema

O Android Kotlin abre exclusivamente o arquivo existente `filesDir/data/library.sqlite3`. O contrato de runtime é gerado a partir do `LibraryStore` real e da definição do schema 29; o acesso nativo valida o contrato e rejeita um arquivo ausente, vazio, corrompido ou incompatível em vez de inicializar um catálogo vazio ou substituir dados.

O schema observado em `core/library_store.py` contém estas 14 tabelas canônicas:

| Tabela | Responsabilidade observada |
|---|---|
| `folders` | Fontes de descoberta configuradas, autorização e identidade SAF. |
| `anime` | Identidade local estável, metadados editoriais, campos manuais e estado pessoal. |
| `episodes` | Arquivos locais, identificadores persistidos, identificação de episódio, disponibilidade e progresso. |
| `episode_observations` | Evidências por fonte/escopo para reconciliar descobertas e remoções sem apagar o histórico de outras fontes. |
| `artwork` | Referências e estado de arte/capas e seus caminhos locais. |
| `associations` | Relação entre `lookup_title` local e ID AniList. |
| `pending_matches` | Correspondências remotas que aguardam resolução. |
| `account` | Valores persistidos de conta. |
| `preferences` | Preferências com timestamp de atualização. |
| `genres` | Registro canônico de gêneros. |
| `genre_aliases` | Aliases normalizados de gêneros. |
| `anime_genres` | Relação de gênero para obra. |
| `schema_migrations` | Versões de schema aplicadas pelo LibraryStore. |
| `scan_runs` | Execuções, escopo e resultado do scanner. |

Além da versão de migração, o validador compara as colunas do DDL real, tipos declarados, nulabilidade, defaults, posição das chaves primárias, índices e respectivas colunas/ordenação, restrições de unicidade, chaves estrangeiras e regras pertinentes de integridade. A verificação usa PRAGMA de SQLite; não corrige nem modifica um banco durante a inspeção.

## 2. Conexão, transações e erros

`ReiAnixSqliteDatabase` é o limite de acesso ao arquivo existente. O grafo de dependências (`ReiAnixAppDependencies`) compartilha a instância de database e o `ReiAnixSqliteLibraryRepository` entre os serviços nativos que já foram conectados. Consultas e gravações são executadas fora da thread principal por APIs suspensas com `Dispatchers.IO`; as operações de escrita usam transações e parâmetros vinculados.

O caminho de abertura não utiliza `CREATE_IF_NECESSARY`, não executa DDL de produção, não migra o arquivo silenciosamente e não converte exceções em catálogo vazio. Falha de consulta, ausência de registro e lista legítima vazia são estados diferentes. O contrato de transação permite distinguir commit, rollback e resultado desconhecido; um resultado desconhecido não deve ser automaticamente repetido por outro escritor.

Não foram alterados o schema v29, o banco de dados do usuário, os índices canônicos ou as regras do backup/restauração.

## 3. Repositório Kotlin existente e operações disponíveis

O trabalho reutiliza `ReiAnixSqliteLibraryRepository` e seus modelos `ReiAnixSqliteModels.kt`. A camada existente já oferece:

- Consulta de obra por ID persistido e listagem paginada de obras.
- Pesquisa SQLite paginada, filtros de favoritos/consumo/gênero e ordenação estável com IDs persistidos.
- Patch transacional de metadados de uma obra existente, respeitando `metadata_manual_fields` e não sobrescrevendo estado pessoal nem valores locais com strings vazias.
- Consulta de episódios por ID e por obra, listagem paginada de reprodução recente, fontes configuradas e artwork por obra.
- Gravação transacional de progresso do player; dados são persistidos nas colunas existentes de `episodes`, em vez de uma tabela de histórico paralela.
- Indexação em lote de snapshots do scanner nativo, com operação transacional, relatório de indexação e callback de resultado de transação. A indexação/reconciliação nativa preserva IDs, evidências de origem e histórico; uma varredura parcial/cancelada não deve ser usada para marcar todo o restante como ausente.
- Preferências na tabela existente por meio de `ReiAnixSqliteSettingsRepository`, inclusive escrita atômica em lote.

A camada de consulta `ReiAnixLibraryQueryService` converte falhas e resultados em contratos explícitos. O `ReiAnixLibraryViewModel` já usa consultas nativas para detalhes, episódios, consumo recente, fontes, artwork e pesquisa nativa filtrada.

## 4. Operações implementadas neste branch

Foi adicionado `ReiAnixSqliteLibraryMutations.kt` como extensão do repositório canônico existente — não é um novo repositório e não possui conexão/banco independente. Ele fornece as seguintes operações:

| Operação | Contrato e proteção |
|---|---|
| `getAnimeByLookupTitle` | Consulta exata por chave local, com parâmetro vinculado. |
| `findEpisodeByPathOrFileUri` | Consulta de caminho exato; reconhece equivalência entre caminho absoluto e `file://`. Não inventa equivalências SAF/content URI; recusa resultados ambíguos. |
| `findEpisodesByMediaIdentity` | Consulta pela identidade de mídia persistida, sem gerar IDs substitutos. |
| `setAnimeFavorite` | Define o estado desejado dentro de transação. A operação de favorito já era nativa via `ReiAnixPersonalListService`; a nova função fornece também a operação idempotente para consumidores que necessitem definir o estado. |
| `toggleAnimePinned` | Alterna o campo canônico `anime.is_pinned` em uma transação; falha para ID inexistente. |
| `setAnimePersonalNote` | Persiste no campo canônico, mantém o limite de 2.000 caracteres e normaliza nota vazia para `NULL`. |
| `setAnimeUserTags` | Persiste JSON no campo `anime.user_tags`, normaliza espaços, deduplica sem diferenciar maiúsculas/minúsculas e não cria tabela alternativa. |
| `setManualMetadataByLookupTitle` | Atualiza apenas a allowlist de campos editoriais manuais e marca a propriedade manual na mesma transação. Exige correspondência entre chave local e ID persistido. |
| `clearManualMetadataByLookupTitle` | Remove apenas os marcadores de propriedade manual; não apaga os valores editoriais armazenados. Recusa JSON de propriedade inválido em vez de apagar a proteção. |
| `setEpisodeWatchedState` | Atualiza estado assistido, progresso e timestamp na mesma transação; corresponde ao contrato de `LibraryStore.set_watched`: marcar como não assistido redefine o progresso a zero; marcar como assistido define o progresso à duração conhecida. |

Os métodos acima não criam nem removem registros e não introduzem novas tabelas. A inserção/atualização em lote de mídia descoberta já ocorre pelo indexador nativo existente, em vez de se introduzir uma segunda implementação de scanner.

## 5. Integração dos consumidores

### Biblioteca e listas pessoais

A mudança de favorito já estava no caminho Kotlin: `ReiAnixLibraryViewModel.toggleFavorite` usa `ReiAnixPersonalListService`, que modifica o campo `anime.favorite` do banco canônico. A ponte de projeção `ReiAnixLibraryRepository` é usada depois da gravação para republicar o catálogo apresentado, não para repetir a mutação de favorito.

O ViewModel passou a usar explicitamente a instância compartilhada de `ReiAnixSqliteLibraryRepository` do grafo nativo para os serviços de consulta, lista pessoal e consumo. O fallback de construção também compartilha uma instância de repositório/database dentro daquele ViewModel, evitando locks independentes entre os serviços que participam do mesmo fluxo.

### Estado assistido

Antes desta mudança, `ReiAnixLibraryViewModel.setEpisodeWatched` delegava a mutação ao adapter/IPC Python. Agora ele chama `ReiAnixConsumptionService.setWatched`, que grava uma única vez com `setEpisodeWatchedState` no SQLite canônico. Somente após o commit, solicita ao adapter existente que publique um novo snapshot para atualizar a interface. A exceção de persistência é exposta por `consumptionMutationError`; cancelamentos de coroutine são propagados.

### Limites híbridos preservados

O adapter `data/library/ReiAnixLibraryRepository.kt` continua sendo uma ponte de projeção/IPC e não deve ser confundido com o repositório SQLite canônico. A mutação de estado assistido deixou de ser delegada ao Python; a publicação de snapshot ainda utiliza a ponte existente porque o snapshot atual e outras ações da biblioteca ainda possuem consumidores Python.

A criação e a remoção de fontes de descoberta, reconciliadores gerais de fonte/escopo, fluxo de pastas SAF, importação de mídia/observações legadas, alguns caminhos de edição e matching de metadados, produção/publicação de artwork, backup/restauração, preferências ainda conectadas ao adapter antigo e operações relacionadas à conta continuam exigindo análise e integração em etapas próprias. Os novos métodos de estado pessoal e metadados manuais são APIs nativas disponíveis, mas isto não significa que todas as telas/ações Python que editam esses campos já estejam cortadas para Kotlin.

Não foi removido globalmente Python, Flet, NativeMailbox, LibraryStore, LibraryService, scanner, cliente AniList, ArtworkEngine ou player. Fazê-lo neste prompt quebraria contratos ainda usados e criaria o risco proibido de dois caminhos incompletos.

## 6. Modelos e preservação

Os modelos de persistência continuam espelhando as colunas reais; números de episódio em REAL, timestamps, JSON/texto bruto, campos anuláveis, IDs persistidos e dados locais não são substituídos por valores fictícios. As alterações de favoritos, tags, notas, progresso e metadados manuais atualizam somente as colunas de propriedade correspondente. O patch remoto preexistente continua respeitando os marcadores em `metadata_manual_fields`; `local_path`/URI e relações de episódios não são recriados por um update de metadata.

A busca por arquivo não considera um URL remoto como caminho local e não faz correspondência aproximada de conteúdo URI. A identidade de mídia permanece uma chave persistida fornecida pelo indexador/reconciliador, nunca um valor aleatório recalculado por leitura.

## 7. Testes e validação

Foi ampliado `ReiAnixSqliteRepositoryRuntimeTest` com um cenário isolado Robolectric/JVM para consultar caminho e file URI, alterar estado pessoal, aplicar metadados manuais, verificar a proteção contra atualização remota e testar o contrato de estado assistido. O teste não usa dados reais: a fixture é gerada em banco temporário a partir do `LibraryStore` canônico.

Foi adicionado `tests/test_kotlin_sqlite_data_layer_contract.py` para o workflow Python já existente validar os contratos de ownership, transação, caminho canônico e integração do estado assistido. Nenhum workflow novo foi criado. O workflow de Kotlin/JVM continua manual (`workflow_dispatch`) e não deve ser acionado neste trabalho. Nenhum teste instrumentado/emulador foi adicionado.

Os testes e o build deste branch ainda precisam ser confirmados pelos workflows obrigatórios associados ao SHA final do PR. A existência dos testes ou a compilação de um commit anterior não comprova o resultado do commit final. Os artefatos APK/ZIP devem ser confirmados no run pertinente quando o workflow os publicar.

## 8. Matriz de status para a entrega

| Critério | Estado do código neste branch |
|---|---|
| Banco/schema canônico identificado e preservado em v29 | Implementado; nenhuma alteração de schema. |
| Nenhum banco paralelo/Room criado | Atendido. |
| Consultas principais, filtros, paginação e ordenação Kotlin existentes | Reutilizados; pesquisa nativa já é consumida pelo ViewModel. |
| Inserção/atualização em lote da mídia descoberta | Reutilizada pelo indexador nativo existente. |
| Favoritos e lista pessoal | Já eram nativos antes deste branch; preservados. |
| Mutação de estado assistido sem escrita Python duplicada | Conectada ao writer Kotlin; snapshot ainda é publicado pela ponte após commit. |
| Operações nativas de pin, nota, tags, metadados manuais e resolução de localização | Implementadas como extensões do repositório, com conexão transacional. |
| Todas as mutações de LibraryStore substituídas em todos os consumidores | **Pendente** — responsabilidades híbridas explicitadas acima; não se declara remoção total de Python nesta etapa. |
| Scanner, artwork, backup e recuperação reescritos | Não faz parte desta etapa; integrações existentes são preservadas. |
| Teste JVM do novo comportamento executado manualmente | Não executado por política; aguarda o momento autorizado para a suíte manual. |
| Testes Python/build/artefatos no SHA final | Pendente até os workflows oficiais terminarem. |

## 9. Histórico desta mudança

O branch parte da `main` observada em `4b42eea36bdeaeea55c4bba3a2f36d4fedc8f1cb`. A sequência e os resultados efetivos de commits, diff, workflows e artefatos devem ser registrados no relatório de entrega vinculado ao Pull Request. Este documento não usa uma execução anterior como prova do SHA final.
