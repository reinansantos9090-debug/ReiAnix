# Compose ↔ biblioteca local

A etapa Compose não cria um segundo banco. O fluxo permanece:

SQLite → LibraryStore → LibraryService → ComposeLibraryBridge → library.json → StateFlow → Compose

As operações que ainda dependem de serviços Python seguem a direção inversa por um contrato pequeno:

Compose → NativeMailbox → main.py → LibraryStore/ScanCoordinator/AndroidBridge

A transição é por operação. No caminho Compose/Android, inclusão pessoal/favoritos são gravados por `ReiAnixPersonalListService` no SQLite canônico e o estado de consumo é gravado por `ReiAnixConsumptionService`; a republicação posterior do snapshot não executa novamente a mutação. O antigo comando NativeMailbox `toggle_favorite` foi removido porque nenhum consumidor Compose restante deve escrever esse estado via Python. O handler legado `set_watched` continua temporariamente no canal de comandos enquanto consumidores antigos de consumo ainda existirem. Separadamente, a tela Flet antiga de detalhes ainda tem um callback próprio para `LibraryStore.toggle_favorite`; ele não é o caminho do ViewModel Compose e só deve ser removido quando o consumidor Flet for migrado.

## Responsabilidades

- `LibraryStore` continua sendo a fonte de verdade persistente.
- `LibraryService` continua responsável pela projeção canônica da biblioteca.
- `ComposeLibraryBridge` publica somente um subconjunto serializável dos dados reais necessários à UI.
- O arquivo `reianix-compose/library.json` é IPC/projeção derivada; ele não deve ser editado pela UI nem tratado como banco.
- `NativeMailbox` continua sendo a fronteira Android/Python já existente.
- `main.py` executa mutações e consultas bloqueantes dos fluxos híbridos que ainda passam pela mailbox, com `asyncio.to_thread`; isso não o torna writer de uma mutação já transferida para Kotlin.
- `refresh` continua pertencendo ao `ScanCoordinator`; Compose não dispara scanners diretamente.
- A abertura de mídia do Compose consulta diretamente `ReiAnixSqliteLibraryRepository.getEpisode()`, lê as preferências persistidas e encaminha uma URI `reiflix://native?action=play` para `MainActivity`. Ela não publica mais `open_media` pela `NativeMailbox`. O caminho Python `play_episode`/`AndroidBridge.play` permanece para consumidores Flet legados e para transições que ainda dependem do runtime híbrido; a validação e o player continuam na implementação nativa existente.

## Atualização da UI

O Android observa `library.json` com `FileObserver`. A leitura e o parsing são executados em `Dispatchers.IO`, e o resultado é exposto como `StateFlow`.

A projeção usa uma revisão monotônica local. Requisições de publicação são coalescidas por um único worker, evitando leituras concorrentes que possam sobrescrever uma publicação mais nova.

A coleta no Compose deve usar `collectAsStateWithLifecycle()` através de `ReiAnixLibraryRoute`.

## Estados

`READY` indica catálogo disponível. `EMPTY` representa biblioteca vazia ou ainda sem fonte configurada. `SOURCE_UNAVAILABLE` representa fontes conhecidas, mas indisponíveis. `ERROR` representa falha de leitura/parsing.

## Comandos

Os comandos de projeção/coordenação ainda utilizados pela UI Compose são:

- `publish_catalog`, após uma mutação nativa persistida;
- `refresh`, encaminhado ao `ScanCoordinator`;
- `load_library_page`: o método/protocolo compatível ainda está no adapter, mas as consultas paginadas ativas da tela Compose usam `ReiAnixLibraryQueryService` e a pesquisa estática não encontrou chamada de produção a esse método. A remoção integral fica classificada como não conclusiva nesta etapa porque o estado/reader de páginas e de resultados ainda existe no contrato do repositório.

A UI Compose não envia `toggle_favorite` nem `set_watched` para realizar essas mutações: o estado pessoal e o consumo têm owners Kotlin. `toggle_favorite` deixou de fazer parte do protocolo `compose_library_command`; a gravação direta que permanece em `views/details_view.py` pertence ao consumidor Flet legado, não à tela Compose. `set_watched` permanece temporariamente no dispatcher para os consumidores antigos ainda identificados. `publish_catalog` continua sendo somente uma solicitação de republicação/compatibilidade posterior ao commit nativo.

Nenhum objeto de catálogo completo é serializado pela navegação ou pelos comandos.
