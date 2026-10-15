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
- MediaStore/BroadStorage continuam diagnósticos sob o contrato atual e não são convertidos automaticamente em fontes canônicas adicionais, para não duplicar arquivos já enumerados por SAF.

Essas dependências remanescentes devem ser removidas apenas quando seus contratos específicos e consumidores forem substituídos. Não foi removido globalmente Python, Flet, Flutter ou NativeMailbox, pois ainda possuem consumidores fora do caminho de registros principal descrito acima.

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
