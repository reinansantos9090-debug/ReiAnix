# ReiAnix — Migração Nativa de Armazenamento e Descoberta de Mídia em Kotlin

Este documento registra a arquitetura e o estado verificável da migração do armazenamento para Kotlin.

**Status de aceitação: EM ANDAMENTO.** A seleção SAF, a gravação da identidade da pasta, a remoção de fontes e a atualização manual Compose são encaminhadas a Kotlin. O scanner SAF persiste/reconcilia o catálogo e, no fluxo normal com ownership nativo confirmado, registra também o histórico em `scan_runs` antes de publicar o evento terminal. A etapa ainda não está concluída: o transporte terminal/projeção compatível usa NativeMailbox/main.py; o finalizador Python permanece como fallback de recuperação quando o ownership/resultado transacional nativo não pode ser comprovado; fluxos Flet legados e a reconciliação de identidade documental ainda têm consumidores Python.

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
- A varredura pode ser disparada por onboarding (`select_tree`), atualização manual Compose (`scan_library`), alteração de permissões (`PERMISSION_CHANGE`) ou sincronização de inicialização/volume.
- Gatilhos nativos e atualização Compose usam `NativeScanController` e `NativeScanRunner`, com coroutines fora da thread principal. Solicitações originadas em caminhos Flet legados ainda podem usar `core/scan_coordinator.py`; a coordenação completa de todos os consumidores ainda não é exclusivamente nativa.

### 2. Scanners Utilizados
- `SafScanner` para fontes SAF configuradas (`saf:*`); este é o fluxo de descoberta que alimenta o catálogo canônico.
- `MediaStoreScanner` para consultas diagnósticas ao índice de vídeos do sistema (`mediastore:*`).
- `BroadStorageScanner` para diagnósticos do armazenamento amplo quando autorizado (`broad-storage:*`).
- MediaStore e BroadStorage não são fontes globais de importação da biblioteca no contrato atual. Seus resultados não devem ser gravados no catálogo apenas por serem encontrados.

### 3. Identificação dos Arquivos
- Cada arquivo descoberto é transformado em `DiscoveredMediaMetadata` contendo a URI (`content://` ou `file://`), nome, caminho relativo, tamanho em bytes, data de modificação em milissegundos e tipo MIME.
- O `MediaIdentity` gera um `media_fingerprint` estável combinando caminho relativo normalizado, nome do arquivo, tamanho e duração.

### 4. Deduplicação
- `NativeIndex` evita duplicações dentro das gerações de descoberta; o repositório SQLite canônico reconcilia a fonte SAF conforme as regras de identidade existentes.
- MediaStore e BroadStorage continuam diagnósticos e não são mesclados ao catálogo por padrão. Isso evita transformar um índice global ou uma varredura ampla em uma nova fonte de biblioteca.
- Arquivos SAF conhecidos são reconciliados usando as identidades e localizações já adotadas pelo projeto; nome de arquivo sozinho não é uma identidade global.

### 5. Reconciliação
- A reconciliação do catálogo SQLite ocorre no fluxo SAF quando a varredura do escopo é concluída com sucesso (`COMPLETED` ou `EMPTY_COMPLETE`) e a autorização/identidade do snapshot permite a operação.
- Varreduras parciais, canceladas, bloqueadas por permissão ou com resultado de transação incerto **nunca** autorizam exclusões destrutivas de episódios/animes.
- Ausência de snapshot não equivale a um snapshot vazio; uma pasta vazia só pode ser reconciliada quando houver um resultado vazio explicitamente completo e verificável.

### 6. Persistência
- Os resultados SAF são salvos de forma transacional no banco SQLite canônico (`ReiAnixSqliteDatabase` / `ReiAnixSqliteLibraryRepository`) na versão de esquema `v29`.
- A inserção e atualização usam transações/SAVEPOINTs conforme os contratos do repositório, preservando os registros existentes em falhas parciais.
- Os scanners MediaStore/BroadStorage não executam mutações no catálogo canônico.

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

### Responsabilidades executadas em Kotlin
- Seleção de diretório via ACTION_OPEN_DOCUMENT_TREE, validação e persistência do grant SAF.
- Telas Compose iniciam o seletor por Intent para MainActivity, sem mandar o pedido pelo mailbox Python.
- A pasta selecionada é registrada na tabela `folders` canônica antes da descoberta; `nativeFolderConfigOwner=true` impede gravação duplicada pelo handler Python.
- A remoção de fonte envia a propriedade para Kotlin: o grant persistente é liberado e `removeSafFolder` atualiza observações/availability na mesma base, preservando histórico e arquivos físicos.
- A atualização Compose usa a ação nativa `scan_library`; o controlador aguarda os resultados SAF terminais e publica um evento de conclusão com estado real.
- A indexação SAF e o histórico `scan_runs` são gravados por Kotlin no fluxo normal quando o commit nativo está confirmado. Python lê o catálogo para publicar a projeção, sem finalizar novamente o mesmo scan.
- Alterações de volume atualizam `native_volume_states`, a marca temporal e a disponibilidade das observações no SQLite canônico via Kotlin; o evento informa que o scan também tem owner nativo, evitando um segundo pedido ao ScanCoordinator Python.

### Dependências híbridas ainda ativas — etapa não concluída
- NativeScanRunner/NativeScanPublisher publicam progresso e término por NativeMailbox, consumido por main.py para atualizar a projeção compatível e os estados de UI.
- O finalizador `LibraryService.finish_ingest_documents` continua como fallback de recuperação quando o resultado/ownership da transação nativa não pode ser confirmado e em receipts antigos de recuperação.
- A reconciliação `saf_document_identity_change` e alguns caminhos Flet de seleção/remoção/rescan ainda usam `core/android_bridge.py`, `core/storage_access.py`, `core/scan_coordinator.py` ou `LibraryService`.
- Algumas telas Compose ainda consomem snapshots JSON legados; NativeMailbox não pode ser removido globalmente enquanto outros subsistemas o usam.

### Critérios restantes
- Migrar a finalização de recuperação para Kotlin sem bloquear a thread principal nem reabrir um caminho de escrita concorrente.
- Migrar a reconciliação de identidade documental e decidir/remover somente os consumidores Flet de armazenamento que já não são necessários.
- Manter a projeção compatível sem delegar descoberta, mutação de catálogo ou finalização normal do scan ao Python.
- Comprovar por CI no SHA final que resultados completos, parciais, cancelados ou sem autorização preservam dados anteriores e não geram duplicações.

## E. Integridade dos Dados

- **Preservação de Registros:** Reconciliações de varredura conservadoras garantem que episódios e animes existentes não sejam apagados quando uma pasta está temporariamente inacessível.
- **Preservação de Metadados e Progresso:** Atualizações de armazenamento preservam `watched_count`, `last_watched_at`, progresso de reprodução, notas e associações com o AniList.
- **Tratamento de Cancelamento:** Cancelamento de varredura preserva dados parciais staged sem alterar o catálogo final.

---

## F. Validação

- **Compilação e Sintaxe Python:** Verificadas via `python -m compileall -q main.py app_config.py core views tests`.
- **Suíte Python histórica:** a etapa anterior registrou 1765 testes aprovados. Esse resultado é histórico e não valida as alterações corretivas desta revisão.
- **Tratamento de exceções Kotlin:** a implementação contém tratamento explícito e propagação de `CancellationException` nos scanners, runner, publisher e operações SAF; a compilação do SHA final continua sendo a verificação exigida.
- **Validação desta revisão:** a execução anterior #5543 falhou na suíte Python (4 falhas de contratos/assertions; 1.844 testes passaram), antes de build/compilação Android e sem APK/ZIP. Os contratos foram corrigidos e a validação do SHA final desta correção permanece pendente; somente o workflow do SHA final pode certificar a etapa. Não execute manualmente testes Kotlin/JVM, não adicione testes instrumentados/emulador e não afirme validação em dispositivo real sem executá-la.
- **Artefatos:** APK, ZIP e SHA-256 só podem ser declarados disponíveis quando forem encontrados no workflow/release associado ao SHA final.


## G. Estado de conclusão desta etapa

**Status: PARCIAL — não declarar o Prompt 04/29 concluído.**

A descoberta e a persistência SAF são nativas, e a propriedade dos lotes foi ajustada para impedir gravações duplicadas em Python. MediaStore e BroadStorage continuam diagnósticos, conforme o contrato de domínio existente. Entretanto, o caminho de coordenação de solicitações ainda depende de `ScanCoordinator` em Python, e a ponte de armazenamento continua com consumidores ativos. A etapa só poderá ser concluída quando essas responsabilidades forem migradas sem criar um segundo coordenador, a paridade funcional for comprovada e os workflows obrigatórios terminarem com sucesso no SHA final.
