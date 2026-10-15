# Compose ↔ biblioteca local

A etapa Compose não cria um segundo banco. O fluxo permanece:

SQLite → LibraryStore → LibraryService → ComposeLibraryBridge → library.json → StateFlow → Compose

As operações que ainda dependem de serviços Python seguem a direção inversa por um contrato pequeno:

Compose → NativeMailbox → main.py → LibraryStore/ScanCoordinator/AndroidBridge

A transição é por operação, com um único writer: favoritos/lista pessoal já são gravados por `ReiAnixPersonalListService` no SQLite canônico. A ação Compose de marcar/desmarcar episódio como assistido agora é gravada por `ReiAnixConsumptionService` e pelo repositório SQLite nativo; depois do commit, o adapter pede a republicação do snapshot. Essa publicação não executa novamente a mutação. Os handlers legados de `toggle_favorite` e `set_watched` permanecem somente para consumidores Python/fluxos antigos que ainda os utilizem.

## Responsabilidades

- `LibraryStore` continua sendo a fonte de verdade persistente.
- `LibraryService` continua responsável pela projeção canônica da biblioteca.
- `ComposeLibraryBridge` publica somente um subconjunto serializável dos dados reais necessários à UI.
- O arquivo `reianix-compose/library.json` é IPC/projeção derivada; ele não deve ser editado pela UI nem tratado como banco.
- `NativeMailbox` continua sendo a fronteira Android/Python já existente.
- `main.py` executa mutações e consultas bloqueantes dos fluxos híbridos que ainda passam pela mailbox, com `asyncio.to_thread`; isso não o torna writer de uma mutação já transferida para Kotlin.
- `refresh` continua pertencendo ao `ScanCoordinator`; Compose não dispara scanners diretamente.
- A abertura de mídia delega ao caminho existente `play_episode`/`AndroidBridge.play`, preservando Media3, sessão, progresso, Next/Previous e todas as validações atuais.

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
- `load_library_page`, enquanto a projeção paginada ainda não tiver todos os consumidores cortados para as consultas Kotlin;
- `open_media` por `episodeId`, resolvido contra a linha canônica do SQLite antes de usar o player existente.

A UI Compose não envia `toggle_favorite` nem `set_watched` para realizar essas duas mutações: elas têm owners Kotlin. O protocolo e os handlers continuam disponíveis para compatibilidade com consumidores legados que ainda os chamem.

Nenhum objeto de catálogo completo é serializado pela navegação ou pelos comandos.
