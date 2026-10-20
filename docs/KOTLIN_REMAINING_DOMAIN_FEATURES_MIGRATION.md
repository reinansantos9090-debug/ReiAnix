# Prompt 10/18 — funcionalidades complementares e regras de domínio restantes

## Baseline e auditoria

- Repositório `reinansantos9090-debug/ReiAnix`; branch `main`; commit-base: `a0a9ca6bef3595b46291dbc7bb9b772e3fe9b470`.
- Banco canônico `filesDir/data/library.sqlite3`; contrato SQLite v29.
- A auditoria comparou módulos Python de domínio, serviços Kotlin, grafo de dependências, APIs de ViewModel e os relatórios/testes de migração existentes. A presença de arquivo Kotlin de nome semelhante, isoladamente, não foi considerada prova de integração.
- Antes da mudança, `docs/KOTLIN_REMAINING_BUSINESS_LOGIC_MIGRATION.md` e `docs/PROMPT_23_COMPOSE_INTERFACE_COVERAGE.md` identificavam a lacuna Gacha: `views/home_view.py` chamava `LibraryService.gacha_pick`, que usa `LibraryStore.random_catalog_item`; um sorteio simplificado não preservaria os filtros combinados de catálogo.
- Parser/identidade, discovery, gêneros, favoritos/lista pessoal, consumo, AniList, settings, artwork, backup/recovery e jornada já tinham implementação Kotlin identificada, mas diversos consumidores Flet continuam ativos.

## Matriz de rastreabilidade

Estados: **Integrado nativo** significa regra, dados e consumidor Kotlin conectados; **Parcial** significa código Kotlin existe, mas consumidores Python/Flet continuam; **Migrado nesta etapa (domínio)** significa serviço e ViewModel ligados com UI Compose consumidora ainda pendente; **Fora do escopo** cobre telas, player, bootstrap/build e remoção global do runtime.

| Funcionalidade / comportamento | Python atual | Kotlin observado/criado | Consumidores/dados | Situação, critério e risco |
|---|---|---|---|---|
| Filtros combinados query/state/genre/sort/tag/media_type/season/episode_type/source_kind/availability/metadata/artwork | `core/library_store.py:catalog_page` | `searchAnimeIds` era outro contrato; novos `ReiAnixCatalogFilters` e `randomCatalogAnime` | `anime`, `episodes`, `folders`, `genres`, `anime_genres`, `artwork` no SQLite v29 | Parcial → migrado no domínio; ainda requer teste dinâmico por fixtures SQLite |
| Sorteio de anime elegível e exclusão do ID anterior | `core/library_store.py:random_catalog_item` | Novo seletor nativo no repositório canônico | IDs persistidos; somente leitura | Python-only → migrado no domínio; evita repetir o ID se há alternativa |
| Sorteio opcional de episódio regular/presente e duração | `core/library_service.py:gacha_pick`, `marathon_episodes` | Novo `ReiAnixGachaService`; reaproveita `marathonEpisodes` e `formatDuration` | `ReiAnixAnimeRecord`, `ReiAnixEpisodeRecord` | Python-only → migrado no domínio; pool vazio retorna null, erros propagam |
| Timeline, anomalias e plano de maratona | `core/library_discovery.py` | `ReiAnixLibraryDiscovery`, `ReiAnixLibraryDiscoveryService`, repository/ViewModel | SQLite, Home/Details | Parcial: regras Kotlin migradas antes, UI Flet continua. Não refeito |
| Parse/identidade/temporadas/episódios | `core/library_parser.py`, `core/media_identity.py` | `MediaFilenameParser`, `MediaIdentity`, scanner nativo | SAF/MediaStore/NativeIndex/SQLite | Parcial devido a fallback Python; não duplicado |
| AniList e matching | `core/anilist.py`, `core/organizer_ai.py` | client/matcher/integration Kotlin | IDs AniList e metadados | Parcial/híbrido; caminho Python mantido |
| Gêneros/aliases/classificação | `core/genre_registry.py`, `core/genre_classifier.py` | `ReiAnixGenreRegistry`, `ReiAnixGenreClassifier` | tabelas existentes de gênero | Parcial por Flet; Gacha só consulta relações existentes |
| Favoritos/Minha Lista | `core/library_store.py`, `core/library_service.py` | `ReiAnixPersonalListService`, serviços SQLite | `anime.favorite`; schema não declara listas nomeadas separadas | Parcial no runtime híbrido; não refeito |
| Vistos/progresso/histórico | `core/consumption.py`, Store | `ReiAnixConsumptionService` | `episodes.progress/watched/last_played_at` | Parcial/híbrido; Gacha apenas lê |
| Busca/ordenação Search/Library/Organize | `core/search_engine.py`, `catalog_page` | query service, `searchAnimeIds`, filter engine Compose | SQLite canônico | Parcial/híbrido; Gacha não substitui as consultas atuais |
| Artwork e cache | `core/artwork.py`, `core/artwork_palette.py` | `ReiAnixArtworkService` e projeções visuais | SQLite/artwork; consumidores Flet | Parcial; não alterado |
| Backup/recovery/diagnósticos | `core/backup.py`, `core/recovery.py`, `core/diagnostic_service.py` | serviços Kotlin descritos no Prompt 09 | dados/restauração/relatórios | Parcial híbrido; não refeito |
| Configurações | `core/settings.py`, bridges | settings repository/contratos Kotlin | preferências canônicas | Parcial conforme Prompt 08; não refeito |
| Shell Flet/dialogs/bootstrap | `main.py`, `views/*.py`, bridges | Activity/navigation/Compose em níveis variados | runtime e rotas | Fora do escopo; arquivos mantidos |
| Player/lifecycle | bridge e rotas Python | Media3/player nativo em migração | sessões, URIs, progresso | Fora do escopo; nada alterado |
| Build/remover global Python/Flet/Flutter | `pyproject.toml`, `requirements.txt`, scripts | app híbrido atual | empacotamento/runtime | Fora do escopo; dependências não removidas |

A matriz contém **14 linhas de responsabilidades agrupadas**, e não uma contagem de todas as funções de cada arquivo nem prova de migração integral.

## Arquivos alterados

Criados:
- `android/app/src/main/kotlin/com/reiflix/reiflix_local/data/sqlite/ReiAnixCatalogFilters.kt`: modelo de consulta sem persistência própria.
- `android/app/src/main/kotlin/com/reiflix/reiflix_local/data/library/ReiAnixGachaService.kt`: operação de domínio Gacha.
- `tests/test_prompt10_remaining_domain_features_migration.py`: contrato estrutural Python dentro da suíte existente.
- Este documento.

Modificados:
- `ReiAnixSqliteLibraryRepository.kt`: seletor Gacha nativo com filtros combinados e consulta read-only.
- `ReiAnixAppDependencies.kt`: serviço registrado, compartilhando a instância do repositório canônico.
- `ReiAnixLibraryViewModel.kt`: adiciona `drawNativeGacha`, com filtros, ID a excluir e escolha opcional de episódio, reutilizando o serviço compartilhado.

Removidos/movidos: nenhum.

## Regras de negócio e persistência

- Fonte de verdade: `filesDir/data/library.sqlite3`, schema v29; nenhum banco, tabela, cache persistente ou namespace de IDs novo.
- Pool inicial segue `catalog_page`: anime tem ao menos um episódio. Filtros de estado, gênero/tag, mídia, temporada, tipo de episódio, source kind, availability, metadata e artwork são combinados.
- Query numérica de temporada/episódio/absolute number é interpretada antes de texto livre; texto usa normalização local, sem consultas remotas.
- Expressões de sort são fixas para as opções conhecidas; valores de filtro usam parâmetros SQL vinculados.
- O cursor é processado item a item e reservoir sampling evita materializar todo o catálogo visual. O ID anterior é excluído quando há alternativa elegível.
- Sorteio de episódio usa a consulta canônica de episódios regulares/presentes e o formatador Kotlin existente.
- Nenhuma escrita de favorito/pin, metadados, gênero, artwork, notas/tags, progresso ou histórico. Pool vazio retorna null; erro da base propaga, sem ser apresentado como catálogo vazio.
- A leitura ocorre em `Dispatchers.IO`; não foram introduzidos retry, polling infinito, scope global ou processamento paralelo de escritas.

## Integração e limite de UI

O serviço está registrado em `ReiAnixAppDependencies.gachaService`. A Home Flet continua chamando `LibraryService.gacha_pick`; a UI Compose Gacha correspondente pertence a uma etapa posterior e não foi substituída. Até a nova API ser consumida por uma rota nativa, o cutover funcional percebido pelo usuário é **parcial**.

## Testes, build e pendências

- O contrato Python adicionado verifica estruturalmente os filtros, repositório, grafo e ViewModel; não equivale a um teste dinâmico de paridade usando fixtures SQLite.
- Não executei manualmente testes Kotlin/JVM; não adicionei testes instrumentados/emulador nem workflows.
- O workflow existente disparado por push à `main` deve ser verificado em estado terminal no SHA final; runs de commits anteriores não validam este diff.
- Continuam consumidores Python ativos em `core/library_store.py`, `core/library_service.py` e `views/home_view.py`, além dos fluxos Flet de discovery, parser/identity, AniList, gêneros, listas/consumo, settings, arte, backup e diagnóstico.
- Próximos prompts: telas/dialogs Compose e cutover Gacha; player/lifecycle; bootstrap/entry point; build independente; remoção global de Python/Flet/Flutter. `main.py`, bridges e testes Python não foram removidos.

## Veredito

**PARCIAL** — o domínio Gacha está portado, mas a interface Compose consumidora e a validação terminal do workflow ainda não foram comprovadas.
