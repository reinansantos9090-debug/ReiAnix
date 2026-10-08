# Compose ↔ biblioteca local

A etapa Compose não cria um segundo banco. O fluxo permanece:

SQLite → LibraryStore → LibraryService → ComposeLibraryBridge → library.json → StateFlow → Compose

As operações de escrita seguem a direção inversa por um contrato pequeno:

Compose → NativeMailbox → main.py → LibraryStore/ScanCoordinator/AndroidBridge

## Responsabilidades

- `LibraryStore` continua sendo a fonte de verdade persistente.
- `LibraryService` continua responsável pela projeção canônica da biblioteca.
- `ComposeLibraryBridge` publica somente um subconjunto serializável dos dados reais necessários à UI.
- O arquivo `reianix-compose/library.json` é IPC/projeção derivada; ele não deve ser editado pela UI nem tratado como banco.
- `NativeMailbox` continua sendo a fronteira Android/Python já existente.
- `main.py` executa mutações SQLite e consultas bloqueantes com `asyncio.to_thread`.
- `refresh` continua pertencendo ao `ScanCoordinator`; Compose não dispara scanners diretamente.
- A abertura de mídia delega ao caminho existente `play_episode`/`AndroidBridge.play`, preservando Media3, sessão, progresso, Next/Previous e todas as validações atuais.

## Atualização da UI

O Android observa `library.json` com `FileObserver`. A leitura e o parsing são executados em `Dispatchers.IO`, e o resultado é exposto como `StateFlow`.

A projeção usa uma revisão monotônica local. Requisições de publicação são coalescidas por um único worker, evitando leituras concorrentes que possam sobrescrever uma publicação mais nova.

A coleta no Compose deve usar `collectAsStateWithLifecycle()` através de `ReiAnixLibraryRoute`.

## Estados

`READY` indica catálogo disponível. `EMPTY` representa biblioteca vazia ou ainda sem fonte configurada. `SOURCE_UNAVAILABLE` representa fontes conhecidas, mas indisponíveis. `ERROR` representa falha de leitura/parsing.

## Comandos

Somente quatro comandos atravessam a fronteira Compose:

- `toggle_favorite` por `animeId`;
- `set_watched` por `episodeId` + estado;
- `refresh`, encaminhado ao `ScanCoordinator`;
- `open_media` por `episodeId`, resolvido contra a linha canônica do SQLite antes de usar o player existente.

Nenhum objeto de catálogo completo é serializado pela navegação ou pelos comandos.
