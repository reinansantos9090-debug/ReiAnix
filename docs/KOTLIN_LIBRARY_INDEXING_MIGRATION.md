# Migração da indexação e reconciliação da biblioteca para Kotlin

## Estado desta implementação

Implementação do Prompt 05 no branch `migration/prompt-05-native-library-indexing-20261015`, baseada na `main` observada em `e115d69e9d970defb075c812b34663f17cbbefb4`.

O caminho Android de descoberta SAF, indexação canônica e recuperação de snapshots positivos após rollback confirmado é executado em Kotlin. O Prompt 05 adiciona `ReiAnixLibraryService` como a entrada de domínio para as mutações do subsistema e conecta o scanner, o histórico de varredura, as alterações de volume e a reconciliação de identidade SAF ao mesmo repositório SQLite compartilhado. Isto não significa que todo o runtime Python/Flet ou todos os consumidores de projeção da biblioteca tenham sido removidos.

## 1. Arquitetura e ownership

- `MainActivity` é a entrada de lifecycle, permissões e intents no Android; ela não contém as regras de reconciliação.
- `NativeScanController` continua sendo o único coordenador Kotlin de execuções iniciadas pelo fluxo Android: reserva uma execução por chave de origem, mantém estados observáveis, rejeita pedidos duplicados incompatíveis e oferece cancelamento cooperativo.
- `MediaStoreRetryScheduler` envia uma intent nativa para `MainActivity` após a janela de deduplicação do observer; não publica mais um evento `scan_request` para o Python decidir e iniciar a varredura.
- `MainActivity` encaminha essa ação pelo entry point nativo e `NativeScanController` controla a execução específica de MediaStore.
- `NativeScanRunner` executa os scanners de descoberta SAF/MediaStore/BroadStorage, controla as gerações persistidas, publica progresso e gerencia o protocolo de recuperação.
- `NativeIndex` é o índice físico/staging durável usado para lotes e recuperação. Ele não substitui o catálogo canônico.
- `ReiAnixLibraryService` é a camada de serviço Kotlin para mutações da varredura. Usa o mesmo `ReiAnixSqliteLibraryRepository` e serializa chamadas de mutação do serviço, sem manter locks durante a travessia do armazenamento.
- `ReiAnixSqliteLibraryRepository.indexDiscoveredMedia` permanece a implementação transacional da reconciliação. O novo serviço não replica SQL ou regras de identidade.
- `ReiAnixLibraryQueryService` é a camada de consulta sobre a mesma base, não um writer adicional.
- `ReiAnixAppDependencies` fornece uma única instância compartilhada de repositório e serviço Kotlin.
- `NativeMailbox` continua sendo transporte de compatibilidade e de estado terminal enquanto o runtime híbrido e o pipeline de projeção ainda possuem consumidores.

Fluxo do caminho SAF normal:

`MainActivity → NativeScanController → NativeScanRunner → NativeIndex (descoberta/staging) → NativeLibraryScanSnapshot → ReiAnixLibraryService → ReiAnixSqliteLibraryRepository.indexDiscoveredMedia → SQLite canônico → registro terminal de scan_runs → evento terminal de compatibilidade`.

O caminho não entrega a persistência normal nem a recuperação de snapshots positivos da versão Android atual a Python. `nativeLibraryIndexOwner` permanece verdadeiro para eventos gerados pelo runner atual; snapshots recuperados são submetidos ao mesmo repositório usando um estado explicitamente parcial, sem reconciliação de ausências. Resultado de transação desconhecido não autoriza um segundo writer e não é transferido ao finalizador legado.

## 2. Validação e reconciliação

O contrato `NativeLibraryScanSnapshot` exige IDs/escopo explícitos, estados processados e sinalização de falhas/cancelamento. `mayReconcile` só permite reconciliar ausências após uma geração declarada completa, autorização válida, cobertura das localizações esperadas, ausência de erros/itens não processados e comprovação de pertencimento ao escopo.

`indexDiscoveredMedia`:
- rejeita observações fora do escopo e identidades ambíguas;
- procura a linha existente por identidade estável e por localização, tratando conflito entre resultados como ambiguidade;
- atualiza apenas fatos de descoberta (localização, nome, MIME, tamanho, mtime, fonte/volume e disponibilidade);
- preserva IDs, progresso, watched, histórico, metadados AniList, capas/miniaturas e campos manuais;
- só insere episódio novo quando a identificação da obra/episódio tem confiança explícita suficiente, sem associar por nome aproximado ou sobrescrever um episódio lógico existente;
- registra observações e mantém referências antigas como superseded em vez de apagar o histórico de localização;
- marca arquivos ausentes como indisponíveis, sem excluir linhas nem dados associados, somente quando `mayReconcile` autoriza a operação.

Uma permissão revogada, árvore inacessível, cancelamento, falha de provider, localização incompleta, ambiguidade ou resultado parcial bloqueia a reconciliação destrutiva. Descobertas positivas comprovadas podem ser persistidas, mas o restante do catálogo é preservado. A identificação de episódios continua usando o parser Kotlin disponível; a indexação não faz requisições AniList obrigatórias e não aguarda ArtworkEngine para persistir vídeos locais.

## 3. Serviço de biblioteca nativo

`ReiAnixLibraryService` recebe consumidores reais do fluxo:
- `NativeScanRunner` usa o serviço para indexar os snapshots SAF e para registrar o `scan_runs` terminal após a decisão confirmada da transação;
- `MainActivity` usa o serviço para persistir transições de volume no mesmo SQLite;
- `ReiAnixLibraryViewModel` usa o serviço para reconciliar mudança de identidade SAF após uma mutação de arquivo confirmada pelo provider.

O serviço não cria banco, schema, scanner, repositório ou coordenador adicional. As operações de persistência continuam implementadas no repositório canônico; o serviço fornece a fronteira única de domínio e serialização para as mutações pertencentes a este subsistema.

## 4. Consumidores Compose e dependências restantes

O estado dos registros de anime, episódios e continuação de reprodução do `ReiAnixLibraryViewModel` agora é projetado em Kotlin diretamente do SQLite canônico:

- `ReiAnixSqliteLibraryRepository.loadLibraryCatalogRows()` lê anime, episódios, associações de gêneros e artwork em consultas SQL agrupadas sob `readConsistentSnapshot()`. No Android 15+ isso fixa uma transação somente-leitura para que as entidades relacionadas venham do mesmo snapshot; não há listas grandes de IDs nos parâmetros nem um segundo catálogo persistente.
- `ReiAnixNativeLibraryCatalogProjector` converte os registros nativos nos modelos Compose já existentes, mantendo as chaves de anime/episódios, o progresso, o estado de disponibilidade, a arte e o alvo de reprodução.
- `ReiAnixLibraryViewModel.uiState` combina esses dados canônicos com o estado de armazenamento, onboarding e diagnóstico. Se a leitura SQLite falhar, o erro é exposto e o último catálogo válido é preservado; um erro de consulta nunca é convertido em biblioteca vazia.
- `pagedLibraryState` usa `loadNativeLibraryPage()` e `ReiAnixLibraryQueryService.searchAnime(libraryOnly=true)`, cuja consulta SQL Kotlin aplica query/aliases/episódios, gênero, favoritos, assistindo/concluídos, ordenação, offset/limit, `hasMore` e total de resultados. A conversão final de IDs para os modelos usa o snapshot projetado do mesmo SQLite. As páginas não são mais solicitadas ao `ReiAnixLibraryRepository.loadLibraryPage` via IPC.
- O estado de varredura Compose também é derivado de `NativeScanController.progress`; início, execução, cancelamento e término nativos não dependem do status velho do snapshot híbrido. A mudança terminal observável aciona nova leitura depois da persistência. A fonte de verdade dos registros continua sendo o SQLite; o Flow sinaliza lifecycle/invalidação, não transporta os registros.

Home, Biblioteca, Detalhes, Minha Lista, Pesquisa e Organizar consomem os modelos expostos pelo ViewModel; a Pesquisa também mantém suas consultas/IDs nativas por `ReiAnixLibraryQueryService`. O estado legado recebido pela ponte não fornece mais os registros de anime/episódio usados pela projeção principal.

Ainda permanecem fronteiras híbridas, sem que sejam confundidas com a propriedade dos registros:
- `ReiAnixLibraryRepository` continua atendendo comandos de compatibilidade, onboarding/armazenamento e alguns estados de operação ainda não migrados.
- `NativeMailbox` e `ComposeLibraryBridge` continuam sendo usados por eventos terminais, diagnóstico e outros subsistemas com consumidores ativos; a ponte não é o escritor canônico da indexação SAF.
- `core/scan_coordinator.py` continua para consumidores não Android e compatibilidade de eventos/versões antigas do APK. O gatilho Android do observer MediaStore e a indexação/recovery de snapshots atuais são nativos.
- `core/library_service.py` permanece para caminhos não Android, desenvolvimento e eventos de APKs antigos; o runner atual não envia snapshots positivos ao escritor Python.
- **MediaStore (follow-up Prompt 28.2):** a descoberta é delimitada às árvores SAF persistidas e ainda legíveis em `folders`, somente para `ExternalStorageProvider`, volumes e caminhos que podem ser correlacionados com `RELATIVE_PATH`. Cada volume usa `NativeIndex.SOURCE_MEDIASTORE` para staging, publicação, leitura de snapshots e recibos de transação; os lotes passam por `ReiAnixLibraryService.indexDiscoveredMedia` no mesmo SQLite canônico. Cache só é reutilizado quando a assinatura das árvores autorizadas, versão/geração do MediaStore e recibo de commit do catálogo coincidem. A identidade `shared:<volume>:<relativePath>` permite encontrar linhas já indexadas via SAF; quando a linha pertence a uma árvore SAF, MediaStore atualiza metadados de descoberta sem substituir o URI SAF nem seu caminho relativo à árvore. Scans parciais/negados não reconciliam ausências. O runner também revalida as concessões SAF depois da enumeração e antes da reconciliação por volume; se uma raiz externa configurada estiver inacessível ou tiver sido revogada, mantém a escrita de descobertas positivas dos escopos legíveis e desativa a inferência de ausências para essa passagem, publicando um aviso na UI/estado terminal. Uma raiz `primary:` só é tratada como raiz de volume quando foi explicitamente selecionada; um identificador vazio continua inválido. Em Android anterior à API 29, onde este fluxo não consegue provar o escopo com `RELATIVE_PATH`/`VOLUME_NAME`, MediaStore informa bloqueio para indexação delimitada; SAF continua disponível.
- **Broad Storage:** continua diagnóstico-only, pois enumera um escopo físico global com semântica de permissão diferente da pasta SAF selecionada. Torná-lo escritor canônico neste prompt permitiria que uma varredura global alterasse a disponibilidade de arquivos em árvores não selecionadas e competisse com as fontes SAF/MediaStore. Não existe ainda um contrato autorizado de escopo/deduplicação que torne essa conversão segura.

Essas dependências remanescentes devem ser removidas apenas quando seus contratos específicos e consumidores forem substituídos. Não foi removido globalmente Python, Flet, Flutter ou NativeMailbox, pois ainda possuem consumidores fora do caminho de registros principal descrito acima.

## Permissões e compatibilidade por versão Android

- Android 14 (API 34+) solicita `READ_MEDIA_VIDEO` e `READ_MEDIA_VISUAL_USER_SELECTED`. O modelo diferencia `FULL` de `PARTIAL`: acesso parcial permite descobrir e indexar somente os vídeos expostos pelo provider, mas não qualifica uma geração como completa nem autoriza reconciliar ausências.
- Android 13 (API 33) usa `READ_MEDIA_VIDEO`. Android 6 até Android 12L (API 23–32) usa `READ_EXTERNAL_STORAGE`, com limite de manifesto já aplicado.
- Concessões negadas não provocam limpeza do catálogo: o runner encerra a varredura com mensagem de permissão e preserva os registros anteriores. SAF continua sendo uma alternativa baseada na URI de árvore escolhida pelo usuário e na permissão de leitura persistida ainda válida.
- Broad Storage depende de `Environment.isExternalStorageManager()` e permanece opcional/diagnóstico; a aplicação não assume esse acesso nem o usa para ampliar a biblioteca além das árvores configuradas.

## Prompt 28.2/29 — indexação MediaStore nativa dentro de árvores autorizadas

O runner obtém as pastas SAF do `folders` canônico e, antes de construir os escopos MediaStore, valida a URI de árvore, a autoridade `com.android.externalstorage.documents` e a autorização persistida/leitura atual. Nenhum escopo é inventado a partir de permissão ampla sozinha. Para MediaStore, a identidade de caminho usa o volume e `RELATIVE_PATH` do provider; árvores sobrepostas escolhem a raiz configurada mais específica para evitar encaminhar o mesmo arquivo a mais de um escopo.

A geração de `NativeIndex` utiliza a mesma chave de origem (`mediastore`) desde `NativeScanPublisher` até start/finish, snapshot e transação. A assinatura das árvores autorizadas é persistida nos metadados da geração e participa da decisão de reutilização; snapshots legados sem assinatura/recibo de escrita confirmada precisam de uma nova varredura. Cada volume gera um `NativeLibraryScanSnapshot` e é indexado por `ReiAnixLibraryService`/`ReiAnixSqliteLibraryRepository` com recibo `IN_PROGRESS` e resultado `COMMITTED`, `NOT_COMMITTED` ou `UNKNOWN`. O estado terminal só é publicado depois da decisão da transação e da tentativa de gravar o histórico `scan_runs`.

O banco conserva a precedência da localização SAF já existente quando a mesma identidade física reaparece via MediaStore **e a URI daquela árvore continua entre as concessões SAF legíveis revalidadas para esta varredura**; se a árvore deixou de estar autorizada, a linha existente é atualizada para a URI MediaStore atual sem recriar a linha nem perder progresso/metadados. Para instalações antigas cujas linhas SAF ainda não têm `media_identity`, o repositório reconstrói uma identidade somente para árvores `ExternalStorageProvider` e `relative_path` comprovados; uma única correspondência é reconciliada e recebe backfill da identidade sem substituir uma identidade não vazia diferente. Múltiplas correspondências ou conflito de identidade são classificados como ambíguos e não são fundidos. Mídia que ainda não tinha linha canônica é atribuída a um escopo `mediastore:<volume>:roots:<sha256-das-raízes-autorizadas>`, e não apenas ao volume. Assim, quando o usuário muda/remova a seleção de pastas, uma varredura da seleção nova não interpreta como ausentes os registros pertencentes à seleção anterior. O scanner publica essa assinatura por volume; uma assinatura ausente/inválida impede reconciliação. A reconciliação de ausências é permitida apenas quando geração, assinatura e concessões atuais são completas, sem erros nem cancelamento, conforme `NativeLibraryScanSnapshot.mayReconcile`. Consulta parcial, mudança/revogação de permissão e erros só podem acrescentar observações positivas verificadas de árvores que continuam legíveis; o runner descarta lotes cuja `libraryScopeUri` deixou de ser revalidada antes da transação, e não marca o catálogo ausente.

Este caminho continua condicionado à confirmação em aparelho real: a CI prova contratos de source, regressões e compilação/empacotamento, não o resultado do `ContentResolver` em cada provider Android. Broad Storage permanece diagnóstico-only pelo motivo de escopo global registrado acima.

## 5. Integridade e validação

Banco único: `filesDir/data/library.sqlite3`, schema existente v29; sem nova tabela, banco, Room ou alteração de schema nesta mudança.

A indexação e reconciliação são executadas dentro da transação que já existe no repositório. O protocolo de recovery conserva o resultado COMMITTED/ROLLED_BACK/UNKNOWN e evita transferir a escrita em caso de resultado desconhecido. O serviço serializa suas próprias chamadas de mutação, e não segura a exclusividade durante descoberta, trabalho de rede ou entre fases.

Verificações de entrega:
- comparar o diff final de cada commit deste branch;
- verificar o resultado dos workflows automáticos para o SHA final da PR;
- confirmar a geração de APK/ZIP nos artifacts do run do SHA final, quando publicados pelo workflow obrigatório;
- não executar manualmente testes Kotlin/JVM, não acionar o workflow manual Kotlin/JVM e não adicionar testes instrumentados/emulador;
- registrar limites de runtime que exigem validação no aparelho sem inventar confirmação.

Critério técnico já satisfeito no código para o fluxo SAF normal: scanner nativo, indexação transacional no SQLite canônico, política de reconciliação não destrutiva e registro terminal de execução pertencem a Kotlin. O critério de cutover integral de todo o subsistema/UI só poderá ser afirmado depois que as dependências de projeção/compatibilidade acima forem substituídas; o resultado da CI do SHA final ainda é a evidência de build necessária para esta PR.

## Prompt 28/29 — corte do transporte de lotes de descoberta

No snapshot de referência `e9e3fcc67bb778f0c7fe861f16dd1ff18d0c976d`, foi confirmado que o `NativeScanRunner` já prepara lotes em `NativeIndex`, indexa os registros no SQLite canônico através de `ReiAnixLibraryService` e finaliza `scan_runs` no lado Kotlin. Para os eventos atuais, `nativeLibraryIndexOwner=true` levava o Python a ignorar os documentos do lote e apenas registrar telemetria. Enviar cada array completo pelo `NativeMailbox` era portanto uma duplicação de IPC, não uma transferência de ownership.

A implementação do Prompt 28 remove esse envio de sucesso: `NativeScanPublisher` continua a persistir/preparar o lote em `NativeIndex`, publica um evento de diagnóstico local limitado a metadados e não copia a lista de documentos para o poller Python. A notificação de erro, sem conteúdo de mídia, permanece temporariamente para preservar a superfície de compatibilidade existente. Os handlers Python para eventos de lote também permanecem como leitura de compatibilidade para mensagens originadas por versões antigas; sua existência não torna o produtor atual dependente deles.

Em conjunto com esse corte, `ReiAnixLibraryViewModel` passa a selecionar o estado de scan entre todos os snapshots `NativeScanController.progress` (SAF, MediaStore e Broad Storage), em vez de tratar apenas SAF como estado nativo. Assim, a UI Compose não precisa dos eventos de lote Python para manter o indicador de scan correto. Isto não remove a `NativeMailbox` nem prova runtime nativo completo: ainda há eventos de progresso/resultado terminal e outros contratos com consumidores Python. A retirada adicional exige migrar esses contratos, incluindo recuperação e persistência dos recibos SAF, antes de apagar a mailbox ou o runtime híbrido.
