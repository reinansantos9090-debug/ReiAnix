# Migração de Minha Lista e estados pessoais para Kotlin — Prompt 10/29

**Repositório:** `reinansantos9090-debug/ReiAnix`  
**Branch de integração:** `main`  
**Data:** 16 de outubro de 2026  
**Schema canônico:** v29; nenhum schema ou banco adicional foi criado nesta etapa.

## Resultado desta etapa

O fluxo da tela Compose Minha Lista e as gravações de favoritos já utilizavam a camada Kotlin na versão-base desta etapa. Este prompt fecha uma delegação obsoleta do protocolo NativeMailbox: o adapter Compose não oferece mais `toggleFavorite()`, o codec não define mais a ação `toggle_favorite` e o dispatcher `compose_library_command` de `main.py` não grava mais favoritos. A gravação Compose permanece exclusivamente em `ReiAnixPersonalListService`.

O caminho Flet de detalhes ainda possui consumidor explícito que chama `LibraryStore.toggle_favorite`. Esse caminho permanece documentado como legado e não é confundido com o caminho Compose. Ele deve ser removido somente quando o consumidor Flet correspondente tiver sido migrado e seus contratos preservados.

## Modelo canônico e semântica

A semântica encontrada no código atual é uma lista de favoritos, não uma tabela de listas nomeadas. A coluna existente `anime.favorite` é a associação que determina a presença em Minha Lista. Não foram criados campos, categorias, tabelas ou timestamps artificiais para representar itens pessoais.

O modelo diferencia os estados relacionados:
- **Minha Lista/favorito:** `anime.favorite`.
- **Consumo e progresso:** campos das linhas canônicas de `episodes`, operados pelo serviço de consumo.
- **Associação AniList:** campos persistidos da obra e tabela `associations`.
- **Biblioteca local:** identidade da obra, episódios, observações de origem, caminhos e permissões existentes.
- **Artwork:** registros e cache canônicos existentes.

Incluir ou remover um favorito altera apenas `anime.favorite`. Não remove episódios, progresso, histórico, associações AniList, caminhos locais ou artwork.

## Arquitetura e fluxo de leitura

1. `ReiAnixSqliteLibraryRepository.loadLibraryCatalogRows()` lê obras e relações necessárias a partir do SQLite canônico.
2. `ReiAnixNativeLibraryCatalogProjector` projeta os registros em modelos Compose, preservando IDs persistidos, metadados opcionais, favoritos, progresso e referências de artwork.
3. `ReiAnixLibraryViewModel` mantém a projeção nativa em `nativeCatalogState` e fornece o catálogo canônico de interface.
4. `myListPresentationState` calcula contagem e estado de carregamento/erro usando o catálogo canônico e o estado de disponibilidade já existente.
5. `myListAnimes` filtra a mesma projeção imutável pela propriedade `favorite` e pelos filtros existentes (`Todos`, `Favoritos`, `Assistindo` e `Completos`).
6. `ReiAnixMyListRoute` observa os fluxos com `collectAsStateWithLifecycle()`; os cards usam `stableKey` e reutilizam o card comum do aplicativo.

A apresentação principal da Minha Lista não depende do snapshot Python para recuperar as obras. O status de disponibilidade/armazenamento ainda utiliza os campos de compatibilidade atualmente fornecidos pelo adapter híbrido.

## Inclusão, remoção, favorito e concorrência

`ReiAnixPersonalListService` implementa `list()`, `contains()`, `add()`, `remove()` e `toggle()` sobre o ID inteiro da obra já persistido. As mutações executam transações no banco existente com parâmetros vinculados. `add()` e `remove()` chamam a mesma operação de definição de estado; se o estado atual já for o estado desejado, retornam o resultado sem uma segunda gravação. A proteção não depende de um conjunto em memória.

O ViewModel usa o serviço compartilhado do grafo nativo. Depois de uma gravação bem-sucedida, atualiza primeiro a projeção Kotlin. Quando aplicável, solicita também a republicação de compatibilidade do catálogo; essa publicação não executa a mutação de favorito novamente. Falhas de persistência são expostas como erro, sem apresentar uma gravação falha como sucesso.

O método `toggle()` permanece uma operação de alternância por definição. As chamadas explícitas `add()` e `remove()` são as APIs idempotentes destinadas aos casos em que o estado final desejado já é conhecido.

## Identificação, ordenação e metadados incompletos

As operações usam o ID inteiro da obra já persistido, não o título apresentado e nem um ID AniList inventado. O ID AniList pode estar ausente; isso não impede que uma obra local existente seja favorita. Os metadados e artwork opcionais são projetados a partir dos registros canônicos, e itens sem capa ou com campos incompletos não são descartados apenas pela ausência desses valores.

A lista de serviço ordena os itens por título sem diferenciar maiúsculas/minúsculas e depois pelo ID. A tela Compose aplica uma ordenação alfabética estável, com ID como desempate, consistente com o comportamento existente nesta versão.

## Delegação Python removida e dependências restantes

**Removido deste fluxo Compose:**
- `ReiAnixLibraryRepository.toggleFavorite(animeId)`, que enviava a mutação para o runtime Python.
- `ReiAnixLibraryCommandCodec.Action.TOGGLE_FAVORITE` e o campo de payload `animeId` exclusivo dessa ação.
- O handler `action == "toggle_favorite"` do worker `compose_library_command` em `main.py`.
- A ação `toggle_favorite` da lista de comandos aceitos pelo dispatcher Compose.

**Preservado deliberadamente:**
- `LibraryStore.toggle_favorite` e o callback `_toggle_favorite_from_details`, pois `views/details_view.py` ainda possui um consumidor Flet real. Esse caminho separado não é usado pelo ViewModel Compose e não deve ser reportado como já migrado.
- O comando legado `set_watched`, enquanto consumidores antigos do subsistema de consumo ainda estiverem sendo migrados.
- `publish_catalog` como solicitação de atualização de projeção/compatibilidade após commits nativos; não é um writer da lista.
- Python/Flet/Flutter, NativeMailbox, scanner, SAF/MediaStore, AniList, ArtworkEngine e player, pois suas responsabilidades externas a esta etapa ainda têm consumidores.

Não há duplicação de writer no fluxo Compose para favoritos. A implementação Flet remanescente é uma rota temporária separada, com destino de remoção após a migração do respectivo consumidor.

## Banco de dados e preservação

- Arquivo canônico: `filesDir/data/library.sqlite3`.
- Versão do schema: 29, preservada.
- Nenhuma tabela, banco, repositório concorrente ou fonte de verdade foi criada.
- Nenhum registro pessoal é reinicializado por esta migração.
- A remoção de favorito não apaga progresso, histórico, associação AniList, dados locais ou artwork.

## Testes e validação

`tests/test_compose_library_integration.py` foi atualizado para não exigir mais que o canal Compose delegue favoritos ao Python. Um novo contrato de regressão verifica a propriedade Kotlin do fluxo Compose, transações/idempotência de add/remove, filtros/keys da tela, ausência da mutação no dispatcher e a dependência Flet que ainda permanece.

Foi atualizado `ReiAnixLibraryCommandCodecTest.kt` para refletir o conjunto de ações remanescente, mas o teste Kotlin/JVM não foi executado manualmente, conforme a política do projeto. Nenhum teste de emulador, workflow novo ou gatilho automático da suíte Kotlin/JVM foi adicionado.

O estado de pytest, compilação Android e publicação de APK/ZIP deve ser verificado nos workflows oficiais associados ao SHA final desta etapa; a documentação não substitui essa evidência.

## Status honesto

A rota Compose de Minha Lista/favoritos não precisa de Python para consultar ou gravar os estados principais, e o comando redundante de gravação no NativeMailbox foi removido. O projeto geral continua híbrido e o callback Flet de detalhes ainda grava o mesmo campo no SQLite; ele fica explicitamente registrado como consumidor legado pendente, não como migração completa de todas as superfícies do aplicativo.
