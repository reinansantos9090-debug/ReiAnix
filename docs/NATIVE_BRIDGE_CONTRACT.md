# ReiAnix — Native Bridge Contract

## Objetivo

Este documento registra as fronteiras Python/Flet ↔ Android/Kotlin que permanecem após a consolidação da UI em um único shell Jetpack Compose.

A regra geral é: o bridge transporta comandos, eventos ou projeções de dados; ele não vira uma segunda fonte de verdade para SQLite, catálogo, permissões ou reprodução.

## Topologia atual

```
Compose UI
  ↓
ViewModel / Repository
  ↓
ComposeLibraryBridge / ComposeSettingsBridge (projeção Python derivada)
  ↓
LibraryService / Settings / LibraryStore
  ↓
SQLite

Compose UI / legacy Flet
  ↓
AndroidBridge
  ↓
MainActivity
  ↓
SAF / MediaStore / Storage / auth
  ↓
NativeMailbox
  ↓
Python mailbox poller
  ↓
Catalog / scanner / progress / diagnostics

Player / thumbnail internal commands
  ↓
NativeCommandDispatcher
  ↓
NativePlayerActivity / VideoThumbnailExtractor
  ↓
NativeMailbox
  ↓
Python
```

## Bridges preservados

### AndroidBridge — Python → Android

Responsabilidade: fronteira histórica de comandos Android que ainda possuem consumidores reais.

Preserva comandos de storage/scanner (`select_tree`, `scan_tree`, `verify_tree`, `release_tree`, `scan_media_store`, `request_media_access`, `open_broad_storage_settings`, `open_storage_settings`, `check_storage_access`, `scan_all_storage`, `cancel_scan`), autenticação Google, player e thumbnail.

Os comandos de navegação visual antigos (`open_library`, `open_organize`, `open_settings`, `hide_library`, `hide_settings`) permanecem somente como compatibilidade para callers legados. No Android atual, eles convergem para o único `ReiAnixComposeLibraryHost`; não montam uma segunda UI.

Entrada: URI `reiflix://native` ou mailbox interno quando o comando é player/thumbnail.

Correlação: `request_id` + `protocol_version` + `created_at` quando disponíveis.

### ComposeLibraryBridge — Python → Compose

Responsabilidade: publicar a projeção derivada da biblioteca real e receber comandos estreitos da UI nativa.

Fonte de verdade: `LibraryService` / `LibraryStore` / SQLite.

Identidade: anime por ID estável; episódio por ID estável; mídia por `media_identity` quando existente.

Não possui banco próprio. `reianix-compose/library.json` é IPC/projeção derivada.

### ComposeSettingsBridge — Python → Compose

Responsabilidade: publicar a projeção persistida das configurações e receber alterações explicitamente permitidas.

Fonte de verdade: settings persistidos existentes.

Não cria um segundo armazenamento de preferências.

### NativeMailbox — Android → Python

Responsabilidade: transporte assíncrono durável de eventos.

Envelope normalizado: `eventId`, `eventVersion`, `eventType`, `createdAt`, `timestamp` e `requestId` quando existente.

Escrita: arquivo temporário + promoção atômica. Eventos de telemetry best-effort usam fila de executor separada para não realizar I/O de arquivo no thread de UI.

Consumo: Python faz claim, deduplicação, processamento e acknowledgement/requeue conforme o contrato existente.

### NativeCommandDispatcher — Python → Android interno

Responsabilidade: somente comandos que não devem depender do deep-link da `MainActivity`: player, extração de thumbnail e cancelamento de transição.

Threading: dispatcher serializado em executor único; thumbnails usam executor limitado de 2 workers.

O dispatcher não possui player alternativo e não implementa navegação visual.

### NativeRequestState — correlação/lifecycle de requests

Responsabilidade: deduplicar request IDs e persistir estados de operação/lifecycle.

Estados existentes: `RECEIVED`, `QUEUED`, `RUNNING`, `COMPLETED`, `CANCELLED`, `FAILED`, `TIMEOUT`.

Não usar estado de request para transportar estado persistente da UI.

## Eventos preservados

Eventos de domínio/integração mantidos sem renomear:

- scanner/storage: `scan_request`, `saf_scan`, `broad_storage_scan`, `mediastore_scan`, seus progressos, permissões e erros;
- player: `player_progress`, `player_paused`, `player_completed`, `player_mark_watched`, `player_mark_unwatched`, `player_autoplay_changed`, `player_exited`, `player_error`;
- thumbnail: `thumbnail_ready`, `thumbnail_error`;
- Compose navigation: `compose_navigation_changed`, `compose_library_navigation`, `compose_settings_navigation`;
- Compose commands: `compose_library_command`, `compose_settings_set`;
- identidade: eventos `google_*` existentes.

Eventos são mensagens transitórias. Estado persistente continua em StateFlow/ViewModel/repository conforme a arquitetura existente.

## Identidade e progresso

Nunca usar título, índice visual ou número do episódio como identidade quando houver ID estável.

O fluxo de progresso continua associado à mídia/episódio identificado por seu contrato real. `player_exited` preserva metadados de sessão/transição necessários para impedir que uma saída antiga atualize uma reprodução nova.

## Storage

SAF continua transportando URIs `content://`; o bridge não converte URI SAF em caminho absoluto para persistência.

MediaStore e broad storage continuam com seus contratos existentes.

Nenhuma dependência nova de `MANAGE_EXTERNAL_STORAGE` foi introduzida.

## Scanner

O scanner continua sob `ScanCoordinator`/scanners nativos. Eventos terminais são encaminhados ao coordenador e, depois, ao catálogo canônico.

O scanner não reconstrói componentes Compose diretamente.

## Player

`NativePlayerActivity` + Media3 continuam sendo o único mecanismo de reprodução.

O bridge não reimplementa next/previous, autoplay ou persistência de posição.

A saída do player mantém a correlação de request/session/episode/media necessária para a reconciliação determinística do catálogo.

## UI e navegação

Após a migração, existe um único host visual nativo: `ReiAnixComposeLibraryHost` (nome legado mantido por compatibilidade).

Settings, Storage, Home, Library, Search, My List e Organize são rotas dentro desse shell.

Os antigos `ReiAnixComposeSettingsHost` e `ReiAnixComposeStorageHost` foram removidos porque não tinham consumidores de runtime após o earlier validation stage 33.

Flet continua somente onde há superfície legada real ou fallback que ainda não foi migrado. A limpeza deste stage não remove esses consumidores.

## Diagnostics

`DiagnosticTimeline.record()` aceita os campos tipados existentes (`request_id`, `scan_id`, `source`, `result`, `counts`, `error`) e também preserva campos adicionais em `extra`.

Chamadas existentes devem usar a assinatura real; erros de keyword não devem ser mascarados por `except` amplo.

## Regra de manutenção

Antes de remover ou renomear qualquer contrato:

1. procurar consumidores diretos e registro de callbacks;
2. verificar bootstrap/lifecycle;
3. verificar scanner, player, progress, storage e UI;
4. verificar request/event IDs;
5. verificar fallback legado;
6. confirmar que o fluxo continua tendo uma única fonte de verdade.

Não criar outro banco, outro player, outro cache de domínio ou outra camada de navegação para "simplificar" o bridge.
