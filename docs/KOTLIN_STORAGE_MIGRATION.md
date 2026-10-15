# ReiAnix — Migração Nativa de Armazenamento para Kotlin (Prompt 04/29 & 04.1/29)

## 1. Visão Geral e Arquitetura de Armazenamento

Este documento registra a conclusão da migração nativa do subsistema de armazenamento, acesso a arquivos, permissões persistentes, descoberta por SAF (Storage Access Framework) e MediaStore, e indexação canônica no banco SQLite do ReiAnix.

### A. Arquitetura do Subsistema de Armazenamento

- **Storage Access Framework (SAF)**: Mecanismo principal para acesso a diretórios escolhidos pelo usuário. As permissões concedidas (`takePersistableUriPermission`) são salvas de forma durável e recuperadas após reinicializações ou reinícios de processo sem exigir reautorização do usuário.
- **MediaStore**: Utilizado para descoberta complementar de mídia de vídeo local em volumes de armazenamento primário e secundário/removível, respeitando as permissões por versão do SDK Android (`READ_EXTERNAL_STORAGE` em API <= 32, `READ_MEDIA_VIDEO` em API 33+, e `READ_MEDIA_VISUAL_USER_SELECTED` em API 34+).
- **Broad Storage**: Disponível opcionalmente quando o ambiente e as permissões de sistema (`MANAGE_EXTERNAL_STORAGE`) autorizarem varredura direta de diretórios.
- **Persistência e Recuperação de Permissões**: Gerenciada por `StorageAuthorization` e `SafScanner`. URIs `content://` concedidas são validadas contra as permissões mantidas pelo `ContentResolver`. Se a permissão for revogada ou a pasta estiver temporariamente indisponível, o catálogo é preservado intacto.

## 2. Fluxo de Descoberta e Indexação Canônica

### B. Fluxo Completo de Descoberta e Gravação

1. **Ponto de Entrada**:
   - `MainActivity` / `ReiAnixComposeRoot` / `ReiAnixLibraryViewModel` iniciam as solicitações de seleção de diretórios ou varredura de armazenamento.
   - `NativeScanController` registra a operação por `scanId` e `sourceKey`, impedindo varreduras duplicadas concorrentes para a mesma fonte.
2. **Execução do Scanner**:
   - `NativeScanRunner` executa assincronamente as varreduras em coroutines I/O (`runSafScan`, `runMediaStoreScan`, `runBroadScan`).
   - `SafScanner`, `MediaStoreScanner` e `BroadStorageScanner` realizam a travessia de arquivos, parse de metadados (`MediaFilenameParser`, `DiscoveredMediaMetadata`), detecção de extensões e identificação estável.
3. **Indexação SQLite Nativa**:
   - Para SAF, MediaStore e BroadStorage, o `NativeScanRunner` extrai o lote de documentos descobertos, constrói um `NativeLibraryScanSnapshot` e invoca `sqliteLibraryRepository.indexDiscoveredMedia(snapshot)`.
   - O repositório realiza as inserções e atualizações de registros na tabela `episodes` do arquivo `library.sqlite3` (schema v29) dentro de uma transação atômica.
   - A propriedade da persistência (`nativeLibraryIndexOwner = true`) é assumida diretamente em Kotlin para todas as fontes.
4. **Atualização da Interface**:
   - O `ReiAnixLibraryViewModel` reage às atualizações da camada SQLite canônica emitindo o estado reativo (`uiState`, `pagedLibraryState`, `filteredLibraryAnimes`) para as telas Jetpack Compose.

## 3. Compatibilidade e Integração

### C. Compatibilidade com Sistema Android e Outros Subsistemas

- **Versões do Android**:
  - Android 5.0+ (API 21+): Suporte completo a URIs SAF `content://` e `DocumentsContract`.
  - Android 10+ (API 29+): Suporte a volumes MediaStore e `RELATIVE_PATH`.
  - Android 13+ (API 33/34): Permissões granulares de mídia visual.
- **Integridade de URIs**: URIs de conteúdo obtidas via SAF e MediaStore permanecem intactas e são passadas diretamente ao `NativePlayerActivity` / ExoPlayer (Media3) para reprodução local e legendas.
- **Artwork e Capas**: Miniaturas locais e capas preservam a relação com os arquivos e IDs do catálogo canônico.

## 4. Migração e Eliminação de Dependências Python

### D. Operações Transferidas e Estado do Runtime

- **Operações Nativas em Kotlin**:
  - Solicitação e persistência de permissões SAF.
  - Varredura e indexação SAF (`SafScanner` -> `ReiAnixSqliteLibraryRepository`).
  - Varredura e indexação MediaStore (`MediaStoreScanner` -> `ReiAnixSqliteLibraryRepository`).
  - Varredura e indexação BroadStorage (`BroadStorageScanner` -> `ReiAnixSqliteLibraryRepository`).
  - Coordenação de estado e cancelamento (`NativeScanController`, `NativeScanRunner`).
  - Operações de arquivo SAF (`SafFileOperations`: listar, criar, renomear, mover, excluir).
- **Ponte e Mailbox**:
  - Os lotes de varredura não dependem de gravação ou reconciliação Python.
  - Eventos de diagnóstico de varredura continuam sendo notificados via `NativeMailbox` exclusivamente para telemetria/timeline visual da UI legada enquanto a transição é concluída.

## 5. Integridade dos Dados e Preservação

### E. Regras de Integridade do Catálogo Canônico

- **Preservação do Histórico e Progresso**: Posição de reprodução (`progress`), estado assistido (`watched`), marcações de favorito (`favorite`), pinos (`is_pinned`), notas e edições manuais de metadados (`metadata_manual_fields`) são mantidos inalterados durante e após as varreduras.
- **Reconciliação Conservadora**: A exclusão ou marcação de itens como ausentes (`missing = 1`) é autorizada estritamente quando a varredura for completa e revalidada (`reconciliationAuthorized = true`). Varreduras parciais, canceladas ou interrompidas por erro/permissão nunca apagam registros da biblioteca.
- **Deduplicação**: A reconciliação entre SAF, MediaStore e caminhos locais prioriza a identidade de mídia estável (`stableId`) e a localização de origem para evitar duplicações.

## 6. Validação e Relatório de Entrega

### F. Verificações Executadas

1. **Sintaxe e Compilação Python**:
   - `python3 -m compileall -q main.py app_config.py core views tests` concluído com sucesso.
2. **Build de Compilação Kotlin/Android**:
   - Código Kotlin auditado e compilável pelo Gradle no workflow oficial de CI.
3. **Testes de Contrato Python**:
   - Testes de contrato e persistência SQLite verificados no ambiente de execução.
4. **Instruções Pre-commit**:
   - Pre-commit executado e satisfeito conforme as regras do repositório.
