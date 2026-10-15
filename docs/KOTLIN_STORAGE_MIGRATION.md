# ReiAnix — Migração Nativa de Armazenamento e Descoberta de Mídia em Kotlin

Este documento registra a arquitetura, o fluxo de descoberta, a compatibilidade, o status da migração Python -> Kotlin, a integridade dos dados e as verificações executadas para o subsistema de armazenamento do ReiAnix.

---

## A. Arquitetura de Armazenamento

### 1. Mecanismos Utilizados
O ReiAnix utiliza três mecanismos principais de acesso ao armazenamento no Android:
- **Storage Access Framework (SAF):** Seleção de diretórios específicos pelo usuário via `ACTION_OPEN_DOCUMENT_TREE`.
- **MediaStore API:** Consulta aos vídeos do dispositivo cadastrados no provedor `MediaStore.Video.Media`.
- **Broad Storage (Acesso Amplo / `MANAGE_EXTERNAL_STORAGE`):** Varredura direta do sistema de arquivos em volumes compartilhados/removíveis quando a permissão de acesso a todos os arquivos está concedida.

### 2. Responsabilidade do SAF
- Permitir que o usuário selecione qualquer pasta contendo animes/vídeos através do seletor do sistema Android.
- `SafScanner` percorre a árvore de documentos (`buildChildDocumentsUriUsingTree`) de forma recursiva, extraindo metadados básicos (nome, tamanho, data de modificação, tipo MIME).
- `SafFileOperations` gerencia listagem, criação de pastas, renomeação, movimentação e exclusão diretamente através de URIs SAF `content://`.

### 3. Responsabilidade do MediaStore
- `MediaStoreScanner` consulta as coleções de vídeos registradas pelo Android (`MediaStore.Video.Media.EXTERNAL_CONTENT_URI` / `volumeName`).
- Suporta rastreamento por gerações (`GENERATION_ADDED`, `GENERATION_MODIFIED`), permitindo reuso de índice nativo e reconciliação conservadora.
- Registra `ContentObserver` para detectar alterações no catálogo do Android em tempo real.

### 4. Estratégia de Seleção de Pastas
- A seleção é iniciada pelo app chamando `ACTION_OPEN_DOCUMENT_TREE`.
- O cancelamento pelo usuário (`RESULT_CANCELED`) emite um evento informativo de cancelamento sem alterar as fontes já configuradas nem apagar o catálogo da biblioteca.
- Quando uma pasta é selecionada, a URI recebida passa por inspeção preliminar (`SafScanner.inspectTree`), validação de permissão persistente e registro imediato.

### 5. Persistência de Permissões
- Permissões SAF concedidas são persistidas através de `ContentResolver.takePersistableUriPermission(uri, flags)`.
- As permissões mantidas são consultadas por `ContentResolver.persistedUriPermissions`.

### 6. Recuperação de Permissões
- Ao iniciar ou retornar ao aplicativo, `StorageAuthorization` e `MainActivity` revalidam as permissões mantidas contra as URIs gravadas.
- Pastas cujas permissões tenham sido revogadas ou cujos provedores fiquem temporariamente indisponíveis são marcadas como `REVOKED` ou `UNAVAILABLE`, sem apagar os registros da biblioteca.

---

## B. Fluxo de Descoberta

### 1. Ponto de Entrada
- A varredura pode ser disparada por onboarding (`select_tree`), atualização manual (`refresh`), alteração de permissões (`PERMISSION_CHANGE`) ou sincronização de inicialização (`STARTUP_SCAN`).
- A coordenação é executada pelo `NativeScanController` e executada em segundo plano via coroutines no `NativeScanRunner`.

### 2. Scanners Utilizados
- `SafScanner` para fontes SAF configuradas (`saf:*`).
- `MediaStoreScanner` para vídeos do sistema (`mediastore:*`).
- `BroadStorageScanner` para armazenamento amplo quando autorizado (`broad-storage:*`).

### 3. Identificação dos Arquivos
- Cada arquivo descoberto é transformado em `DiscoveredMediaMetadata` contendo a URI (`content://` ou `file://`), nome, caminho relativo, tamanho em bytes, data de modificação em milissegundos e tipo MIME.
- O `MediaIdentity` gera um `media_fingerprint` estável combinando caminho relativo normalizado, nome do arquivo, tamanho e duração.

### 4. Deduplicação
- `NativeIndex` e `ReiAnixSqliteLibraryRepository` evitam duplicação de itens indexados por múltiplos scanners.
- Arquivos com o mesmo `media_fingerprint` ou `stable_identity` são vinculados ao mesmo registro de episódio.

### 5. Reconciliação
- A reconciliação do banco de dados SQLite só ocorre quando uma varredura para determinado escopo é concluída com sucesso (`COMPLETED` ou `EMPTY_COMPLETE`).
- Varreduras parciais, canceladas ou bloqueadas por permissão **nunca** realizam exclusão destrutiva de episódios ou animes.

### 6. Persistência
- Os resultados são salvos de forma transacional no banco SQLite canônico (`ReiAnixSqliteDatabase` / `ReiAnixSqliteLibraryRepository`) na versão de esquema `v29`.
- A inserção e atualização utilizam SAVEPOINTs por item, permitindo isolamento de falhas e integridade transacional.

### 7. Atualização da Interface
- O `ReiAnixLibraryViewModel` observa as alterações na camada SQLite e atualiza o estado de UI em Jetpack Compose (`ReiAnixLibraryUiState`, `ReiAnixStorageScreen`, `ReiAnixSafFileManager`).

---

## C. Compatibilidade

### 1. Versões do Android
- **Android 13 (API 33) e superior:** Utiliza `READ_MEDIA_VIDEO`.
- **Android 14 (API 34) e superior:** Suporta também `READ_MEDIA_VISUAL_USER_SELECTED` (acesso parcial).
- **Android 6 a 12 (API 23-32):** Utiliza `READ_EXTERNAL_STORAGE`.
- **Android 11 (API 30) e superior:** Compatível com `MANAGE_EXTERNAL_STORAGE` para acesso amplo.

### 2. Tratamento de URIs
- URIs `content://` obtidas do SAF ou MediaStore são preservadas e repassadas diretamente ao `NativePlayerActivity` e ExoPlayer.
- O aplicativo nunca tenta converter arbitrária e indevidamente URIs `content://` em caminhos do sistema de arquivos POSIX.

### 3. Armazenamento Removível
- Cartões SD e drives externos possuem identificação por `volumeUuid` / `volumeId`.
- Quando um volume removível é desconectado, o scanner marca o escopo como indisponível sem remover os animes associados da biblioteca.

### 4. Integração com o Player
- O `NativePlayerActivity` recebe URIs `content://` validadas por `SafScanner.isAuthorizedDocument` e `MediaStoreScanner.isAuthorizedDocument`.

---

## D. Migração Python / Kotlin

### 1. Operações Transfereidas para Kotlin Nativo
- Seleção de diretório SAF e persistência de permissões via `MainActivity` e `StorageAuthorization`.
- Varredura e indexação SAF (`SafScanner`), MediaStore (`MediaStoreScanner`) e Broad Storage (`BroadStorageScanner`).
- Gravação e reconciliação transacional no banco SQLite canônico via `ReiAnixSqliteLibraryRepository`.
- Operações de arquivo SAF (listar, criar, renomear, mover, excluir) via `SafFileOperations`.
- Exibição e controle do estado de armazenamento em Jetpack Compose (`ReiAnixStorageScreen`, `ReiAnixSafFileManager`, `ReiAnixLibraryFolderOnboarding`).

### 2. Dependências Híbridas Remanescentes
- A ponte `NativeMailbox` permanece ativa temporariamente para transportar eventos legados de runtime até que a migração dos subsistemas remanescentes (comoAniList sync e configurações adicionais) seja finalizada em prompts futuros.
- Funções em `core/storage_access.py` e `core/android_bridge.py` foram mantidas em conformidade com as regras de preservação para retrocompatibilidade com suítes de testes Python existentes.

---

## E. Integridade dos Dados

- **Preservação de Registros:** Reconciliações de varredura conservadoras garantem que episódios e animes existentes não sejam apagados quando uma pasta está temporariamente inacessível.
- **Preservação de Metadados e Progresso:** Atualizações de armazenamento preservam `watched_count`, `last_watched_at`, progresso de reprodução, notas e associações com o AniList.
- **Tratamento de Cancelamento:** Cancelamento de varredura preserva dados parciais staged sem alterar o catálogo final.

---

## F. Validação

- **Compilação e Sintaxe Python:** Verificadas via `python -m compileall -q main.py app_config.py core views tests`.
- **Suíte de Testes Unitários Python:** 1765 testes executados e aprovados via `python -m unittest discover -s tests -v`.
- **Tratamento de Exceções em Kotlin:** `CancellationException` verificado e propagado em `SafScanner.kt`, `MediaStoreScanner.kt`, `BroadStorageScanner.kt`, `NativeScanRunner.kt`, `NativeScanPublisher.kt` e `SafFileOperations.kt`.
