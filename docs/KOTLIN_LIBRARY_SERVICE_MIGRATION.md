# Prompt 05/18 — Serviço central da biblioteca Kotlin

## Identificação e baseline

- Repositório: `reinansantos9090-debug/ReiAnix`.
- Branch de trabalho: `main`, conforme a instrução de execução; nenhuma PR foi criada.
- Commit-base: `3358b10ef71ac225e5b81d1b19d17461a504b2d8`, merge do Prompt 04.1/18.
- O serviço de mutações de indexação, a fachada de consultas, o repositório SQLite e os consumidores Compose já existiam na baseline. Esta etapa os consolidou; não partiu de uma biblioteca vazia.
- Banco canônico: `filesDir/data/library.sqlite3`, gerido por `ReiAnixSqliteDatabase`.
- Schema-alvo mantido em v29. Esta etapa não modifica o DDL, caminho, bootstrap ou migrações.

## Matriz de responsabilidades

| Responsabilidade | Implementação e fonte de dados | Consumidores/dependências | Estado nesta etapa / risco |
|---|---|---|---|
| Consultar anime por ID | `ReiAnixLibraryService.animeById` → `ReiAnixSqliteLibraryRepository` | Fachada de consultas, ViewModel e abertura do player | Centralizada; distingue não encontrado de falha |
| Consultar episódio por ID e por anime | `episodeById`, `episodesForAnime` | ViewModel e player nativo | Centralizada; valida anime pai e propaga falhas |
| Paginação e busca com filtros | `animePage`, `searchAnime` | `ReiAnixLibraryQueryService`, ViewModel | Consulta executada pelo repositório SQLite existente; sem índice/catálogo paralelo |
| Pastas, reprodução recente e arte registrada | Métodos de consulta do serviço central | Consumidores Kotlin existentes | Centralizadas; resultado vazio, inexistente e erro permanecem distintos |
| Snapshot para projeção da biblioteca | `libraryCatalogRows` → `loadLibraryCatalogRows` | `ReiAnixLibraryViewModel`, `ReiAnixNativeLibraryCatalogProjector` | ViewModel passa pela camada central; a projeção mantém o modelo Compose atual |
| Registrar mídia descoberta | `indexDiscoveredMedia` → transação existente do repositório | `NativeScanRunner`, scanners SAF/MediaStore | Reaproveitada; o evento de mudança só é publicado quando o callback confirma `COMMITTED` |
| Histórico de execução de scan | `finishNativeScanRun` | `NativeScanRunner` | Continua separado da transação do catálogo; não é sinal artificial de sucesso |
| Mudança de volume e identidade SAF | `recordNativeVolumeChange`, `reconcileSafDocumentIdentityChange` | MainActivity, operações SAF e scanner | Mesmas validações de escopo/identidade; nenhum novo coordenador |
| Atualização de metadados/progresso | `updateAnimeMetadata`, `updatePlaybackProgress` | Consumidores que usam patches de domínio | Usa transações e regras existentes do repositório |
| Pin, nota, tags e identificação manual | Serviço central delega às extensões de `ReiAnixSqliteLibraryMutations.kt` | `ReiAnixLibraryViewModel` | As operações passam pela entrada central; campos fora do escopo continuam preservados |
| Estado após escrita confirmada | `StateFlow<ReiAnixLibraryChange>` | ViewModel e projeções Compose | Revisão emitida depois do retorno da escrita; consumidores recarregam o SQLite |
| Compatibilidade com Python/Flet | `ReiAnixLibraryRepository`, NativeMailbox e chamadas legadas | Consumidores ainda não migrados, onboarding e projeções | Mantidos; não declarados removidos ou totalmente migrados |

## Contratos de entrada e saída

A camada central aceita IDs persistidos, limites/offsets validados, filtros existentes, `ReiAnixAnimeMetadataPatch`, `ReiAnixPlaybackProgressUpdate`, snapshots de indexação e payloads já utilizados pelo armazenamento nativo.

As consultas retornam `ReiAnixLibraryQueryResult`: `Success`, `Empty`, `NotFound` ou `Failure`. O cancelamento de coroutine é propagado, não convertido em falha genérica ou lista vazia. Erros de leitura não simulam biblioteca vazia. Uma consulta de itens por anime distingue anime ausente de anime existente sem episódios.

As operações de mutação mantêm os contratos de retorno do repositório existente. A revisão observável é emitida somente depois do retorno bem-sucedido da gravação. Na indexação, a publicação requer evidência explícita de `ReiAnixSqliteTransactionOutcome.COMMITTED`; o resultado da indexação continua sendo decidido pelo scanner e pelo repositório, não por um escopo paralelo.

## Integridade, identidade e reconciliação

- A base `library.sqlite3` e `ReiAnixSqliteLibraryRepository` permanecem a única fonte persistente do catálogo nativo. Nenhum banco, tabela de catálogo alternativa ou cópia persistente independente foi criado.
- Schema v29, identificadores persistidos, caminhos/URIs, metadados, capas, campos de usuário, histórico e progresso permanecem sob as regras do repositório existente.
- `ReiAnixAnimeMetadataPatch` representa campos nulos como “não fornecidos”; a camada não converte um patch parcial em instrução para limpar colunas. A propriedade de campos manuais continua no repositório.
- `ReiAnixPlaybackProgressUpdate` mantém a validação e as proteções de concorrência/callback obsoleto do repositório.
- A deduplicação de mídia utiliza os contratos de identidade em `NativeLibraryIndexing` e no repositório; título isolado ou nome do arquivo não vira identidade universal.
- Conflitos ambíguos não escolhem arbitrariamente um registro. Falha de consulta, scan parcial, pasta inacessível, permissão revogada ou ausência de evidência completa não autorizam remoção destrutiva de registros.
- Reconciliação SAF só altera um registro existente após os controles de identidade e escopo já usados pelo projeto. A etapa não adiciona operação genérica de exclusão como mecanismo de recuperação.

## Coordenação e estado observável

`NativeScanController` e `NativeScanRunner` continuam sendo os únicos coordenador/limite de execução da varredura. O serviço central serializa suas próprias gravações por `Mutex`; não faz traversal do armazenamento, I/O de rede nem mantém o bloqueio entre fases de scan. O repositório continua sendo o limite transacional de atomicidade.

O ViewModel observa o sinal de mudança do serviço e recarrega os dados confirmados do SQLite. As notificações de indexação/volume/identidade SAF não provocam um segundo refresh completo no observador de mutações, pois as conclusões de scan continuam cobertas pelo observador terminal de `NativeScanController`. Os estados Compose anteriores e a projeção paginada foram preservados.

## Arquivos e alterações desta etapa

1. `android/app/src/main/kotlin/com/reiflix/reiflix_local/data/sqlite/ReiAnixLibraryService.kt`: expandido de serviço de mutações de scan para ponto de entrada central de consultas/mutações e revisão observável.
2. `android/app/src/main/kotlin/com/reiflix/reiflix_local/data/sqlite/ReiAnixLibraryQueryService.kt`: mantido como fachada compatível, delegando contratos de leitura ao serviço central.
3. `android/app/src/main/kotlin/com/reiflix/reiflix_local/ReiAnixAppDependencies.kt`: grafo lazily compartilhado agora fornece a mesma instância central à fachada.
4. `android/app/src/main/kotlin/com/reiflix/reiflix_local/viewmodel/ReiAnixLibraryViewModel.kt`: snapshot do catálogo, mutações pessoais e leituras de episódio/anime para o player passam pela camada central; os critérios de elegibilidade, ordenação e handoff do player foram preservados.
5. `docs/KOTLIN_LIBRARY_SERVICE_MIGRATION.md`: documentação desta migração.

Nenhum arquivo foi removido ou movido. Nenhum teste foi adicionado, removido ou editado. Nenhum workflow foi criado ou alterado. Nenhuma pasta foi criada ou removida; `docs` já existia.

## Dependências legadas e escopo não migrado

`ReiAnixLibraryRepository` continua ativo como adaptador de projeção/IPC e como sinal de compatibilidade para fluxos ainda não migrados. NativeMailbox, `main.py`, Python/Flet/Flutter, serviços especializados de consumo/lista/arte/metadados e outras pontes não foram removidos. A independência total do runtime legado, a migração integral do player, do motor de arte e dos serviços de metadados estão fora do escopo deste prompt.

## Testes, build e CI

- A suíte Kotlin/JVM não foi executada manualmente por decisão do projeto. Nenhum teste de emulador/instrumentado foi adicionado ou executado manualmente.
- O workflow oficial já existente executa a regressão Python durante a compilação/release. No commit intermediário `f5fc768a588c1190e161dd0086b501c6abcc931c`, run [39245511228](https://github.com/reinansantos9090-debug/ReiAnix/actions/runs/39245511228) terminou com falha da suíte estática (8 falhas, 1963 aprovados, 58 subtestes aprovados). A análise dos logs apontou expectativas de strings de chamada em arquivos antigos que mudaram de localização após centralização; nenhuma suíte ou workflow foi alterado para suprimir os resultados.
- Os arquivos Kotlin permanecem com os fluxos centralizados; comentários de migração tornam explícita a localização nova para leitores dos contratos fonte. O estado final de build, certificação, testes automáticos e artefatos deve ser lido nos workflows oficiais vinculados ao SHA final desta etapa, reportados no relatório de execução. Não presumir sucesso a partir de um commit ou de uma execução intermediária.
