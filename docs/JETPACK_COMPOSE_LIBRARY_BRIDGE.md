# Compose ↔ biblioteca local

A etapa Compose não cria um segundo banco. O fluxo permanece:

SQLite → LibraryStore → LibraryService → ComposeLibraryBridge → library.json → StateFlow → Compose

As operações que ainda dependem de serviços Python seguem a direção inversa por um contrato pequeno:

Compose → NativeMailbox → main.py → LibraryStore/ScanCoordinator/AndroidBridge

A transição é por operação. No caminho Compose/Android, favoritos e estado de consumo são gravados por `ReiAnixPersonalListService` e `ReiAnixConsumptionService` no SQLite canônico. As ações Kotlin que emitiam `set_watched`, `select_saf`, `dismiss_storage_onboarding` e `remove_saf` foram retiradas do adapter: Compose grava consumo no serviço nativo e inicia seleção/remoção SAF pela Activity Kotlin; o cancelamento do onboarding também pertence à Activity. Os handlers Python correspondentes são preservados provisoriamente para consumir mensagens duráveis que possam ter sido enfileiradas por versões anteriores; a remoção desses receivers exige encerrar explicitamente a compatibilidade. O antigo comando `toggle_favorite` já tinha sido removido do adapter. Separadamente, a tela Flet antiga de detalhes ainda tem um callback próprio para `LibraryStore.toggle_favorite`; ele não é o caminho do ViewModel Compose e só deve ser removido quando o consumidor Flet for migrado.

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

A UI Compose ainda emite `publish_catalog` como solicitação de republicação da projeção híbrida depois de uma mutação nativa. A ação `refresh` passa pela URI nativa `reiflix://native?action=scan_library`. O produtor Kotlin `load_library_page`, seu estado paginado e a aplicação de resultados de página na UI do adapter foram removidos porque nenhuma tela Compose ativa os utiliza: as páginas atuais vêm de `ReiAnixLibraryQueryService` sobre o SQLite canônico. O decoder de resposta ainda entende mensagens de página antigas, que o adapter consome e descarta sem recriar estado obsoleto. Os receivers/projetores Python continuam apenas para drenar comandos duráveis de versões anteriores e superfícies de compatibilidade; isso não certifica a remoção global do runtime Python/Flet.

Nenhum objeto de catálogo completo é serializado pela navegação ou pelos comandos.
