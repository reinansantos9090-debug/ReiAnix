# ReiAnix — roadmap executável de migração integral para Kotlin nativo

## 1. Objetivo e referência

Este plano parte do snapshot auditado em 2026-10-14, SHA 40e8c86be49282774ec45060aea9374155a14ca9, e dos achados de docs/KOTLIN_MIGRATION_AUDIT.md. O objetivo final é um único aplicativo Android Kotlin/Jetpack Compose, cujo build e execução não dependam de Python, Flet, Flutter, serious_python ou main.py.

As etapas são unidades funcionais completas, não prompts artificiais para arquivos individuais. Cada uma só termina quando o comportamento, persistência, integração, estados de erro, cancelamento e critérios de validação estiverem cobertos. A sequência abaixo contém 21 pacotes de implementação possíveis, MIG-02 a MIG-22. Isso é uma decomposição inicial baseada nas dependências encontradas, não uma promessa de prazo ou número imutável: antes de abrir cada PR, confirme que o escopo continua coerente com a main mais recente.

## 2. Regras aplicáveis a todas as etapas

1. Preservar o SQLite canônico em filesDir/data/library.sqlite3 e data_dir/library.sqlite3, inicialmente schema v29 e os dados já existentes. Não criar Room, um segundo SQLite ou um repositório concorrente para o mesmo domínio.
2. Definir um owner lógico por operação. Durante a coexistência, Python e Kotlin podem ter conexões diferentes ao mesmo arquivo, mas a mesma operação não pode executar duas escritas concorrentes nem repetir uma transação cujo resultado é desconhecido.
3. Não remover ponte, módulo, tela Flet ou dependência Python antes de identificar consumidores indiretos, imports dinâmicos, efeitos colaterais, eventos e regressões cobertas.
4. Não usar listas vazias, dados de demonstração, stubs ou respostas de sucesso padrão para simular serviços ainda não migrados. Falhas offline, de permissão ou do banco devem ser estados explícitos.
5. Todo requisito precisa ter um critério verificável e uma evidência. Se o comportamento não for conhecido, classificar como não verificado e investigar, sem declarar paridade por inferência.
6. Não limpar nem recriar o banco do usuário para migrar. Backup e recuperação devem continuar compatíveis com os formatos já suportados.
7. O workflow Kotlin/JVM permanece limitado a workflow_dispatch. Não executar manualmente os testes Kotlin/JVM durante este prompt nem adicionar ou executar testes instrumentados/de emulador.
8. Manter os testes automáticos atuais e o build obrigatório até que existam verificações equivalentes. Resultados de CI devem ser vinculados ao SHA exato, não a um commit anterior.
9. Runtime e build híbridos só podem ser removidos depois que os fluxos essenciais funcionarem no caminho nativo. A porcentagem de linguagens do GitHub não é critério de conclusão.
10. Cada PR funcional futuro deve ter diff revisado, contagem real de linhas/arquivos/pastas modificados, critérios de aceitação, limitações e artefatos reais quando previstos pelos workflows.

## Estado transversal — Prompt 02/29: fundação de execução nativa

Este trabalho é uma fundação arquitetural transversal necessária ao corte gradual para Kotlin. Ele não substitui nem marca como concluído o pacote funcional MIG-02 — Contratos de domínio e baseline de paridade, descrito adiante: inventariar as operações concretas de LibraryStore/LibraryService e comprovar paridade continuam sendo trabalho pendente e são a próxima etapa.

Referência verificada: main em 40e8c86be49282774ec45060aea9374155a14ca9; a implementação parte da head do PR #166, 5e8e6e92f077a1a0a23c57794867c4ac517f5bb6, que carrega os documentos de auditoria e roadmap do Prompt 01.

Escopo implementado neste PR:
- Registrar ReiAnixApplication no manifesto do overlay e no manifesto real gerado pelo hook Flet, mantendo a ancestralidade FlutterApplication e a entrada MainActivity : FlutterFragmentActivity.
- Criar ReiAnixAppDependencies, uma composition root manual por processo, com dependências lazy(SYNCHRONIZED) e sem trabalho de banco, scan ou rede no startup.
- Compartilhar a instância Kotlin de ReiAnixSqliteDatabase e ReiAnixSqliteLibraryRepository entre leitores de Biblioteca, favoritos, settings nativas, AniList, consumo/progresso e indexação de snapshots pelo NativeScanRunner, sem trocar o writer Python dos comandos que ainda passam pela mailbox.
- Injetar o grafo nas factories dos ViewModels da Activity existente; manter os repositórios de projeção/IPC sob o lifecycle dos respectivos ViewModels e manter o serviço de consumo ligado ao lifecycle da Activity do player.
- Cobrir por testes de contrato Python já integrados ao workflow a Application do manifesto renderizado e os pontos de integração essenciais.

Problemas tratados: a construção via Context repetia objetos SQLite independentes com locks distintos dentro de serviços que precisam operar coordenadamente; a Activity, os ViewModels e o player não possuíam um composition root explícito; a Application do manifesto gerado pelo Flet não tinha um contrato do ReiAnix que registrasse o grafo nativo.

Limites ainda abertos: a aplicação continua híbrida; main.py, LibraryStore, LibraryService, ScanCoordinator, os adapters JSON/NativeMailbox, parte de metadados/artwork e backup/recovery continuam com consumidores reais. O compartilhamento da instância Kotlin não resolve por si só o ownership concorrente de operações Python/Kotlin nem transforma o SQLite em writer exclusivamente Kotlin. Nenhuma operação de domínio é migrada integralmente nesta etapa.

Aceitação da etapa: o código e os testes de contrato foram adicionados, mas o critério de entrega só é satisfeito pelo build/teste Python obrigatório concluído com sucesso para o SHA final, com APK e ZIP confirmados nos artefatos quando previstos. Use o check run ligado ao head atual do PR e o relatório de entrega; nunca reutilize o sucesso de outro SHA. O workflow Kotlin/JVM permanece manual e não deve ser disparado nesta etapa; não foi criado nem acionado teste de emulador.

Próximo pacote funcional: MIG-02 — contratos de domínio e baseline de paridade, sem alterar o schema v29. O grafo de dependências agora fornece a estrutura para os consumidores nativos, mas não comprova a paridade funcional de parsing, identidade, metadata ou reconciliação.

## Estado transversal — Prompt 03/29: camada de dados SQLite Kotlin

Referência de execução desta etapa: branch `implementation/prompt-03-sqlite-data-layer-20261015`, criada a partir da `main` observada em `4b42eea36bdeaeea55c4bba3a2f36d4fedc8f1cb`. A implementação está sendo revisada neste PR; somente o SHA final com workflows obrigatórios verdes poderá ser aceito como entrega validada.

### Implementado neste pacote

- Mantido o arquivo SQLite canônico `filesDir/data/library.sqlite3` e o schema v29 definido por `core/library_store.py`; sem Room, banco paralelo, mudança de schema ou migração destrutiva.
- Reutilizados o database, repositório, modelos, serviço de consulta, serviço da lista pessoal, serviço de consumo e repository de configurações que já existiam na fundação nativa.
- Adicionadas operações Kotlin transacionais para leitura por chave local, resolução exata entre caminho absoluto/file URI, consulta por identidade de mídia, pin, nota pessoal, tags, associação AniList, edição manual de metadata e remoção de marcadores de edição manual.
- Adicionado o método de consumo que reproduz a semântica de `LibraryStore.set_watched`: progresso e estado de conclusão são persistidos juntos; desfazer “assistido” zera o progresso, e a duração conhecida é usada ao marcar como assistido.
- Conectado `ReiAnixLibraryViewModel.setEpisodeWatched` ao escritor Kotlin; a ponte existente só republica o snapshot depois do commit, sem executar a mesma mutação em Python.
- Preservados os serviços de consulta paginada, pesquisa, patch de metadados e indexação de snapshots do scanner nativo; nenhuma lógica de scanner, cliente AniList, artwork, backup ou player foi reescrita.
- Adicionado teste de runtime isolado ao conjunto Kotlin/JVM já existente e teste Python de contrato para o workflow normal.

### Correção pontual do Prompt 03.1 — resolução de associação AniList pendente

A resolução nativa agora atualiza a linha local `anime`, escreve o mesmo ID na tabela canônica `associations` e remove a linha correspondente de `pending_matches` na mesma transação. A escolha pendente é persistida como match manual, em linha com `LibraryService.resolve_match`. A resolução falha antes de gravar quando a chave local não existe; testes de regressão cobrem a consulta pública imediatamente após a resolução, a preservação da pendência para anime inexistente e rollback quando a gravação em `associations` falha. Nenhum schema, versão do banco, writer Python, tela ou subsistema externo é alterado por esta correção.

### Correção complementar do Prompt 03.2 — validação do estado de pendência AniList

A operação Kotlin lê `pending_matches` e `associations` dentro da mesma transação das gravações. Uma pendência correspondente autoriza a resolução; sem pendência, só uma associação anterior positiva e coerente com `anime.anilist_id` autoriza a substituição. Quando nenhuma condição existe, a chamada é rejeitada antes de modificar qualquer linha. Os testes JVM existentes agora incluem a rejeição sem escrita e a troca autorizada sem pendência, preservando também a cobertura anterior de resolução pendente, anime local inexistente e rollback.

A inspeção do código Python revelou uma diferença importante: `LibraryStore.anilist_match(lookup)` consulta a linha em `anime` e retorna um registro mesmo quando `anilist_id` está vazio; portanto, a mera existência da linha local não comprova uma associação efetiva. Para atender à precondição explícita deste prompt e impedir resoluções obsoletas, o Kotlin valida o ID persistido e sua coerência com `associations`. Nenhum código Python foi alterado neste prompt; a semântica legada permanece documentada como uma questão de paridade futura, fora do escopo desta correção.
A validação automática do commit corretivo e os artefatos correspondentes devem ser registrados apenas depois que o run de PR referente ao SHA final terminar.

### Limite de ownership que permanece explícito

O adapter `data/library/ReiAnixLibraryRepository.kt` continua responsável pela projeção/IPC de várias ações ainda híbridas. A gravação de favoritos já era nativa antes deste pacote; a alteração real de ownership neste prompt é o estado assistido. As APIs nativas de metadata manual, tags, notas e pin agora existem, mas ainda não significam que todas as telas/ações Flet/Python que as expõem tenham sido migradas para consumidores Kotlin. Também permanecem no fluxo de transição as mutações de fontes de descoberta e reconciliação geral, alguns fluxos de associação/matching, publicação de artwork, backup/restauração e preferências ainda delegadas ao adapter legado. Cada uma depende de migrar seu consumidor/efeito colateral sem replicar writers; não foi fingido que essas responsabilidades desapareceram.

Não remova pontes, LibraryStore, LibraryService, NativeMailbox nem os sistemas de scanner/SAF/MediaStore, artwork, AniList e player enquanto houver consumidores ativos. Não interprete a existência de métodos Kotlin como evidência de que cada chamada de produção já foi trocada.

### Verificação da entrega

- O contrato do schema e a ausência de escrita destrutiva são mantidos pelo database existente.
- A cobertura JVM adicionada não foi executada manualmente e continua no workflow manual `workflow_dispatch`, conforme a regra do projeto.
- A aceitação aguarda o workflow obrigatório no SHA final do PR, conferência de jobs/status e confirmação real de APK/ZIP quando publicados. Um run de outro SHA não vale para esta etapa.

## 3. Dependências de alto nível

- **Contratos, SQLite e domínio (MIG-02 a MIG-04)** sustentam os subsistemas posteriores porque definem IDs, transações, schema, modelos, erros e ownership.
- **Scanner e armazenamento (MIG-05 a MIG-06)** dependem das semânticas de catálogo e persistência; exigem coordenação única e reconciliação conservadora.
- **AniList e artwork (MIG-07 a MIG-08)** dependem da autoridade do catálogo e de um patch de metadata que preserve dados locais e campos manuais.
- **UI central e pesquisa (MIG-09 a MIG-10)** dependem de serviços nativos/Flow e metadata/artwork reais.
- **Listas, histórico e organização (MIG-11 a MIG-12)** dependem de IDs persistidos, identidade local e scanner.
- **Configurações, conta, backup e diagnósticos (MIG-13 a MIG-16)** dependem do schema e dos serviços nativos; backup também depende da compatibilidade do banco e da arte manual.
- **Orquestração do player (MIG-17)** depende de repositório, URIs SAF, histórico e estado de sessão.
- **Cutover da Activity/shell nativos (MIG-18)** só acontece quando nenhum fluxo essencial depende do runtime híbrido.
- **Remoção de bridges (MIG-19)** depende de todos os produtores e consumidores terem sido migrados. NativeMailbox é global e não deve ser removido no início.
- **Build standalone, limpeza e certificação (MIG-20 a MIG-22)** dependem do caminho de runtime nativo já funcional. O código Python só é removido depois que deixa de ter consumidores reais.

## 4. Etapas de implementação

### MIG-02 — Contratos de domínio e baseline de paridade

**Objetivo:** Estabilizar contratos de mídia, URI, IDs, scan generations, campos manuais de metadata, progresso e schema v29 antes de mover ownership.

**Escopo fechado:** Inventariar cada operação pública atual de LibraryStore/LibraryService contra serviço Kotlin equivalente; consolidar modelos e fixtures a partir de dados e casos reais; registrar divergências de parsing e mapeamento. Não alterar schema.

**Arquivos principais:** data/media/MediaDomainModels.kt; MediaFilenameParser.kt; MediaIdentity.kt; data/sqlite/ReiAnixSqliteModels.kt; SqliteDomainMappers.kt; tests/test_media_identity.py; tests/test_native_sqlite_persistence_contract.py.

**Dependências:** Auditoria Prompt 01.

**Preservar:** IDs persistidos, nomes de arquivo, números decimais de episódios, local_path, metadata_manual_fields, URIs SAF/MediaStore e schema v29.

**Riscos:** Contratos paralelos e falsos positivos de paridade baseados somente nos nomes das classes.

**Critérios objetivos de aceitação:** Matriz de operação→implementação→efeito persistido; corpus para identidade cobrindo casos edge; divergências documentadas; nenhuma criação de banco paralelo.

**Verificações:** Testes Python existentes via CI normal e revisão estática. Testes Kotlin/JVM continuam manuais e não devem ser executados neste prompt; sem testes instrumentados/emulador.

**Prioridade:** P0.

### MIG-03 — Authority do SQLite Kotlin e migrações compatíveis

**Objetivo:** Portar bootstrap, criação de schema e migrações para Kotlin sobre o mesmo arquivo existente.

**Escopo fechado:** Derivar DDL/migrações do schema atual v29 e executá-los em Kotlin; manter inspeção PRAGMA e contrato estrutural; estabelecer política clara de ownership, lock, transação, falha e rollback. Usar o asset de contrato apenas como comparação enquanto necessário.

**Arquivos principais:** core/library_store.py; data/sqlite/ReiAnixSqliteDatabase.kt; ReiAnixSqliteLibraryRepository.kt; ReiAnixSqliteSettingsRepository.kt; scripts/generate_native_sqlite_test_fixture.py; testes JVM existentes.

**Dependências:** MIG-02 e seus contratos aceitos.

**Preservar:** Mesmo arquivo library.sqlite3, schema e registros existentes; nenhuma limpeza, reset ou troca silenciosa de versão.

**Riscos:** Inicialização/escrita simultânea pelo Python e Kotlin; alterações implícitas de tipos, índices, chaves estrangeiras ou constraints.

**Critérios objetivos de aceitação:** Um banco existente é aberto sem mutação destrutiva; uma instalação nova inicializa o schema equivalente; a atualização a partir do schema v29 e falhas de transação são tratadas com segurança; ownership por operação está documentado.

**Verificações:** Inspeção de schema/PRAGMA e testes JVM via workflow manual somente quando forem autorizados no momento apropriado. Não acionar o workflow manual durante esta tarefa.

**Prioridade:** P0.

### MIG-04 — Repositório de biblioteca e semânticas de domínio

**Objetivo:** Completar a paridade entre LibraryStore/LibraryService e os repositórios/casos de uso Kotlin.

**Escopo fechado:** Migrar as operações faltantes de metadata patch, marcação de item ausente, pastas, associações, registros de scan, observações, configurações e projeções; redirecionar o fluxo ativo para Kotlin apenas quando a semântica estiver equivalente.

**Arquivos principais:** core/library_store.py; core/library_service.py; data/sqlite/ReiAnixSqliteLibraryRepository.kt; ReiAnixLibraryQueryService.kt; NativeLibraryIndexing.kt; data/library/*.

**Dependências:** MIG-02 e MIG-03.

**Preservar:** Fonte canônica única; metadados manuais e caminhos locais não podem ser sobrescritos; erros nunca viram lista vazia silenciosa.

**Riscos:** Escritas duplicadas e upserts que apagam campos locais.

**Critérios objetivos de aceitação:** Cada operação de domínio tem equivalente Kotlin verificável, consultas parametrizadas, transações atômicas e erros explícitos; comparação de resultados e efeitos persistidos é equivalente.

**Verificações:** Contratos Python existentes e verificações permitidas; sem alteração não solicitada de schema.

**Prioridade:** P0.

### MIG-05 — Coordenação nativa de varredura e reconciliação

**Objetivo:** Migrar ScanCoordinator e os estados de domínio da varredura para Kotlin sem criar outro scanner.

**Escopo fechado:** Fila/prioridade/deduplicação, exclusividade, requests full/incrementais, start/cancel, generations, resultados parciais/terminais, logging, agendamento inicial e reconciliação conservadora.

**Arquivos principais:** core/scan_coordinator.py; core/library_service.py; scanner/NativeScanController.kt; NativeScanRunner.kt; NativeScanPublisher.kt; storage/NativeIndex.kt; ReiAnixSqliteLibraryRepository.kt.

**Dependências:** MIG-02 a MIG-04.

**Preservar:** Scan parcial/cancelado não autoriza exclusões; snapshot SAF fica preservado até commit; recuperação implementada no PR #160; identidade e estados terminais existentes.

**Riscos:** Apagar arquivos após scan incompleto, dupla execução ou deadlock no SQLite.

**Critérios objetivos de aceitação:** Um coordinator Kotlin detém todas as solicitações, com estado idempotente e cancelamento real; fallback de descoberta não depende de Python; scan incompleto nunca autoriza reconciliação destrutiva.

**Verificações:** tests/test_scan_coordinator.py, test_reconciliation.py e test_rescan_and_reconciliation.py via CI existente; não criar testes instrumentados.

**Prioridade:** P0.

## Estado transversal — Prompt 05/29: coordenação e indexação de biblioteca

Base efetiva desta implementação: `main` em `e115d69e9d970defb075c812b34663f17cbbefb4`; branch `migration/prompt-05-native-library-indexing-20261015`. A validação final depende do SHA final desta branch e dos workflows obrigatórios da PR.

### Alterações desta etapa

- Criado `data/sqlite/ReiAnixLibraryService.kt` como serviço nativo de domínio com consumidores reais; usa o repositório SQLite compartilhado, serializa as próprias mutações e não cria scanner, coordenador, banco ou repositório paralelo.
- `NativeScanRunner` encaminha indexação SAF, recuperação positiva pós-rollback e registro terminal de `scan_runs` pelo serviço Kotlin.
- `MediaStoreRetryScheduler` encaminha retries do observer por intent Android; deixa de publicar um `scan_request` para Python iniciar a varredura.
- `MainActivity` encaminha mudanças de volume ao serviço; `ReiAnixLibraryViewModel` encaminha reconciliação de identidade SAF depois da mutação confirmada pelo provider.
- `NativeLibraryScanSnapshot` e `indexDiscoveredMedia` preservam o contrato de escopo, deduplicação, patch seletivo, dados associados e autorização conservadora para marcar ausências.
- A recuperação usa o mesmo repositório em estado explícito parcial. Resultado de transação desconhecido não é reindexado nem transferido ao Python; receipts positivos/rollback que continuam recuperáveis são preservados para nova tentativa Kotlin.
- Adicionados `ReiAnixNativeLibraryCatalogProjector` e `loadLibraryCatalogRows()`: Home/Biblioteca e consumidores derivados do `ReiAnixLibraryViewModel` recebem anime/episódios/artwork/gêneros lidos de um snapshot SQLite consistente no Android 15+; a paginação e filtros da Biblioteca usam `ReiAnixLibraryQueryService.searchAnime(libraryOnly=true)` e `searchAnimeIds()` com ordenação, offset/limit e total correspondentes, sem delegar para `ReiAnixLibraryRepository.loadLibraryPage`.
- Adicionada `docs/KOTLIN_LIBRARY_INDEXING_MIGRATION.md` e teste de contrato para o ownership e o cutover de projeção Compose.

### Fronteiras que continuam abertas

- `ReiAnixLibraryRepository` e `ComposeLibraryBridge` permanecem para comandos híbridos, onboarding/estado de armazenamento, resultados terminais e outros contratos não migrados; não são a fonte dos registros de anime/episódios usados pela projeção principal.
- `NativeMailbox` permanece para compatibilidade de eventos/diagnóstico e outros subsistemas com consumidores ativos.
- `core/scan_coordinator.py` e `core/library_service.py` permanecem para consumidores não Android, desenvolvimento e compatibilidade com eventos/versões antigas do APK. O fluxo Android atual de descoberta/indexação/recovery não transfere snapshots positivos ao writer Python.
- MediaStore/BroadStorage continuam fontes diagnósticas, sem se tornarem fontes canônicas extras que poderiam duplicar arquivos enumerados por SAF.
- Schema v29 preservado; nenhum banco paralelo foi criado. Nenhum teste Kotlin/JVM foi executado manualmente e nenhum teste instrumentado/emulador foi adicionado.

A migração do escritor normal SAF, recovery positivo, retry do observer MediaStore e projeção principal de registros para Compose foi implementada na branch. A remoção global do runtime híbrido não é parte desta afirmação: ainda há fronteiras de comandos/estado e compatibilidade acima. A conclusão desta PR depende da CI obrigatória no SHA final e da verificação de APK/ZIP desse mesmo run.

### MIG-06 — Storage, permissões SAF e operações de arquivo (EM ANDAMENTO)

**Objetivo:** Migrar armazenamento e descoberta para Kotlin sem criar scanners, bancos ou coordenadores paralelos.

**Status:** EM ANDAMENTO — seleção, descoberta SAF, indexação/recovery canônicos, retry do observer MediaStore e projeção principal de registros para Compose são nativos; ainda há comandos/estados híbridos e compatibilidade para APKs antigos.

**Implementado nesta correção:** A seleção SAF persiste a fonte e a identidade usando argumentos nomeados no repositório SQLite canônico; a remoção pelo bridge delega a liberação do grant e a remoção/reconciliação de observações ao mesmo repositório Kotlin; o refresh Compose e o refresh Android legado enviam a ação nativa scan_library e aguardam estados terminais; o fluxo normal finaliza scan_runs em Kotlin antes do evento terminal; eventos de volume atualizam preferências/availability via Kotlin e não disparam uma segunda varredura Python; renomeações/movimentações SAF atualizam a URI e as observações na transação Kotlin antes do evento de projeção. Quando nativeLibraryIndexOwner e nativeScanRunOwner confirmam ownership, main.py apenas lê o catálogo para a projeção e não repete a finalização.

**Bloqueios restantes:** Eventos `saf_document_identity_change` de APKs antigos ainda podem usar o finalizador de compatibilidade Python; a publicação de estado terminal continua passando por NativeMailbox/main.py para estado/diagnóstico e compatibilidade, mas a projeção principal dos registros de anime/episódios agora é lida do SQLite por Kotlin. A versão atual não transfere snapshots positivos de recovery ao writer Python.

**Arquivos principais:** MainActivity.kt; NativeScanController.kt; NativeScanRunner.kt; NativeScanPublisher.kt; SafScanner.kt; MediaStoreScanner.kt; BroadStorageScanner.kt; NativeIndex.kt; ReiAnixSqliteLibraryRepository.kt; ReiAnixLibraryRepository.kt; NativeRequestState.kt; core/android_bridge.py; core/library_service.py; core/storage_access.py; core/scan_coordinator.py.

**Dependências:** MIG-04 e MIG-05. A finalização de recuperação, a reconciliação de identidade e os consumidores legados precisam ser encerrados antes da aceitação final.

**Preservar:** grants SAF válidos, identidade após move/rename, observações de outras fontes e histórico de reprodução; varredura parcial ou permissão revogada não comprova ausência de arquivos.

**Critérios restantes:** Retirar fallback de eventos antigos apenas quando não houver consumidores compatíveis; terminar os contratos remanescentes de estado/comandos de armazenamento; validar varreduras completas/parciais/canceladas/sem autorização sem mutações duplicadas.

**Verificação-base:** O commit 188f868d2f8cbe6d6fc756ea8a60241d1f1bd528 passou workflows APK/certificação, mas é anterior às correções deste PR e não as valida. O workflow #5543 falhou na suíte Python e não gerou APK/ZIP; o workflow do SHA final desta correção ainda precisa concluir com sucesso.

**Prioridade:** P0.

## Estado transversal — Prompt 06/29: AniList nativo e localização PT-BR

Após o commit da metadata, ReiAnixArtworkService em Kotlin baixa, valida e persiste posters/backdrops usando a tabela artwork e atualiza anime.cover_cache com proteção para campos manuais. NativeMailbox publica ARTWORK_PUBLISHED após o commit; o caminho Compose não delega materialização ao ArtworkEngine Python.

**Limite explícito:** core/anilist.py, core/library_service.py e core/artwork.py continuam disponíveis para consumidores Flet/legados que ainda não foram migrados. No caminho Compose, LibraryService não executa o ArtworkEngine e ReiAnixArtworkService é o writer persistente; o subsistema global só poderá ser considerado 100% Kotlin quando os consumidores Python remanescentes forem migrados.

**Verificações adicionadas:** testes de regressão do cliente GraphQL e do artwork nativo, incluindo os contratos do pipeline persistente, fronteira Python/Compose e registro canônico de thumbnails. Testes Kotlin/JVM seguem manuais e não são executados automaticamente. O status final do workflow/artefatos deve ser conferido no SHA de conclusão.

### MIG-07 — AniList, matching, metadados e localização PT-BR

**Objetivo:** Consolidar consultas remotas e regras de associação no serviço Kotlin consumido pelo Compose.

**Escopo implementado nesta branch:** GraphQL search/lookup/refresh, associação manual/automática, matching fuzzy portado, normalização HTML, sinopse PT-BR, títulos alternativos, gêneros, estúdios, formato, ano, episódios, status, cache, limites e cancelamento. A hidratação nativa é acionada pelo host Compose, isolada do ViewModel de pesquisa; lê o catálogo SQLite e persiste resultados/estados de tentativa. Estados não encontrados/ambíguos são terminais, enquanto falhas de rede/limite/JSON são registradas, mas voltam a ser elegíveis em um ciclo posterior.

core/library_service.py (hidratação/editorial e fallback do ArtworkEngine somente para consumidores Flet legados); ReiAnixArtworkService.kt (writer persistente Kotlin no caminho Compose)

**Dependências:** MIG-02, MIG-03 e MIG-04.

**Preservar:** Metadados manuais/locais, local_path e capas; falha de rede não apaga dados existentes; PT-BR continua disponível.

**Limite remanescente:** Este pacote não elimina o runtime Python/Flet nem o ArtworkEngine Python dos fluxos Flet ainda ativos;

**Critérios de aceitação desta etapa:** Todos os consumidores Compose usam os serviços Kotlin; os estados automáticos são persistidos para evitar rede repetida; o worker Python não faz AniList hydration quando Compose está ativo; cache offline, rate limit, cancelamento e erros são explícitos.

**Verificações:** `tests/test_native_anilist_integration_contract.py`, `tests/test_anilist_service.py`, testes Kotlin/JVM manuais (não executar nesta etapa) e CI do SHA final.

**Prioridade:** P1.

### MIG-08 — Artwork, capas, cache e miniaturas

**Objetivo:** Fornecer o pipeline produtor Kotlin para o caminho Compose e manter compatibilidade com dados/caches existentes; aposentar o writer Python apenas quando os consumidores Flet restantes tiverem sido migrados.

**Escopo fechado:** Download validado, cache keys, deduplicação, workers limitados, cancelamento, invalidação por geração, overrides manuais, registro de arte, extração de miniaturas e publicação de eventos incrementais consumidos por Coil.

**Arquivos principais:** core/artwork.py; core/artwork_palette.py; ui/artwork/ReiAnixLocalArtwork.kt; VideoThumbnailExtractor.kt; repositório SQLite de artwork.

**Dependências:** MIG-03, MIG-04 e MIG-07.

**Preservar:** local_path, escolhas manuais de capa, cache de arte local/offline e identidade/mtime do vídeo; resultado antigo não substitui geração atual.

**Riscos:** Capas desaparecidas, downloads duplicados, alto consumo de memória, UI não invalidada.

**Critérios objetivos de aceitação:** Um único writer Kotlin atualiza a fonte canônica do caminho Compose, comunica estado à UI e atende downloads/cache/thumbnails sem Pillow nem delegação Python; o ArtworkEngine só permanece nas rotas Flet documentadas até a migração dos respectivos consumidores.

**Verificações:** tests/test_artwork_engine.py e tests/test_thumbnails.py por CI; não substituir asserts por listas vazias.

**Prioridade:** P1.

### MIG-09 — Home, Biblioteca e Detalhes a partir de Kotlin

**Objetivo:** Remover snapshots Python das principais telas depois que os repositórios nativos forem equivalentes.

**Escopo fechado:** Paginação, filtros, ordenação, cards, continuar assistindo, detalhes/episódios, capas, refresh e estados loading/error/empty usando ViewModels/Flow.

**Arquivos principais:** ReiAnixHome.kt; ReiAnixLibrary.kt; ReiAnixDetails.kt; ui/model/*; ui/mapper/LibraryUiMappers.kt; ReiAnixLibraryViewModel.kt; core/compose_library_bridge.py.

**Dependências:** MIG-03, MIG-04, MIG-07 e MIG-08.

**Preservar:** Filtros/ordenação, posição de scroll, origem de retorno, metadata e episódios persistidos.

**Riscos:** Tela visualmente migrada renderiza dados fictícios ou lista vazia enquanto o banco tem itens.

**Critérios objetivos de aceitação:** Cada tela consome SQLite/serviços Kotlin; nenhuma depende do ComposeLibraryBridge; falha do banco é diferenciada de catálogo vazio.

**Verificações:** tests/test_compose_library_integration.py, test_details_compose_contract.py e test_compose_states.py via CI.

**Prioridade:** P1.

### MIG-10 — Pesquisa nativa e roteamento de detalhes

**Objetivo:** Unificar pesquisa e navegação para detalhes/player no contrato Kotlin.

**Escopo fechado:** Normalização de acentos, aliases, títulos romaji/English/native, pesquisa na biblioteca e metadata, cancelamento e navegação por IDs reais.

**Arquivos principais:** ReiAnixSearch.kt; ReiAnixSearchEngine.kt; ReiAnixLibraryQueryService.kt; core/search_engine.py; ReiAnixNavigation.kt.

**Dependências:** MIG-04, MIG-07 e MIG-09.

**Preservar:** Pesquisa local offline, variantes de título e abertura do item correto.

**Riscos:** Resultado duplicado ou regressão de matching.

**Critérios objetivos de aceitação:** Engine Kotlin única com corpus de regressão; nenhum caminho da tela chama search_engine.py ou consulta snapshot Python.

**Verificações:** tests/test_native_search.py, test_search_engine.py e test_search_details_organize.py.

**Prioridade:** P2.

### MIG-11 — Minha Lista, histórico e consumo

**Objetivo:** Completar o ciclo Kotlin de listas pessoais e projeções de histórico/progresso.

**Escopo fechado:** Membership, continuar assistindo, assistido, ordenação por reprodução recente, ponto de retomada, persistência, reconciliação e projeção de UI.

**Arquivos principais:** ReiAnixPersonalListService.kt; ReiAnixConsumptionService.kt; ReiAnixSqliteLibraryRepository.kt; ReiAnixMyList.kt; ReiAnixHome.kt; core/consumption.py.

**Dependências:** MIG-03, MIG-04 e MIG-09.

**Preservar:** IDs, progresso e histórico; toggles não zeram posição/duração de forma incorreta.

**Riscos:** Progresso resetado/duplicado e lista apenas visual.

**Critérios objetivos de aceitação:** Todas as leituras/escritas/projeções são Kotlin; retorno ao app retoma a posição persistida; membership sobrevive ao reinício.

**Verificações:** tests/test_player_consumption_cycle.py, test_progress_resume.py, test_progress_continuity.py e test_persistence.py.

**Prioridade:** P1.

### MIG-12 — Organização, gêneros e coleções

**Objetivo:** Migrar as regras de domínio atualmente atrás da UI de organização.

**Escopo fechado:** Parser, aliases/registry/classifier de gêneros, jornadas/coleções, propostas de rename/move, duplicados e confirmação de ações destrutivas.

**Arquivos principais:** core/organizer_ai.py; core/genre_classifier.py; core/genre_registry.py; core/collector_journey.py; ReiAnixOrganize.kt; data/media/*; SafFileOperations.kt.

**Dependências:** MIG-03 a MIG-07.

**Preservar:** Regras, aliases, ordem das coleções e identidade local; operações de arquivo exigem confirmação e permissões válidas.

**Riscos:** Funcionalidade secundária perdida apesar de a tela Compose continuar existindo.

**Critérios objetivos de aceitação:** Cada regra Python tem um equivalente Kotlin ou um registro comprovado de código sem consumidor; corpus de entrada gera classificação esperada; nenhuma ação é stub.

**Verificações:** tests/test_organize.py, test_organize_collections.py e test_smart_library_tools.py.

**Prioridade:** P2.

### MIG-13 — Configurações integralmente Kotlin

**Objetivo:** Remover snapshots, comandos e mutações de preferências que dependem de Python.

**Escopo fechado:** Todas as categorias e chaves; player/áudio/legendas/gestos/biblioteca/metadata/artwork/storage/scan/privacy/diagnóstico/cache/backup; atualização e rollback do estado visual.

**Arquivos principais:** ReiAnixSettings.kt; ReiAnixSettingsViewModel.kt; data/settings/*; core/settings.py; core/compose_settings_bridge.py.

**Dependências:** MIG-03, MIG-06, MIG-08, MIG-14 e MIG-15.

**Preservar:** Preferências atuais e defaults; falha de atualização restaura UI ao valor persistido verdadeiro.

**Riscos:** Categoria visual sem ação real e snapshot atrasado sobrescrevendo preferência nova.

**Critérios objetivos de aceitação:** Cada chave tem fonte/destino/validação; ações são Kotlin; settings.json/FileObserver não são necessários ao runtime.

**Verificações:** tests/test_settings_contract.py, test_settings_contract_parity.py, test_settings_store.py e test_compose_settings_preferences.py.

**Prioridade:** P1.

### MIG-14 — Conta e autenticação

**Objetivo:** Preservar login/logout e estado de conta sem runtime híbrido.

**Escopo fechado:** Credential Manager, configuração pública de OAuth, cancelamento, persistência do estado, sair/trocar conta e cards de configurações.

**Arquivos principais:** GoogleIdentity.kt; ReiAnixAccountAvatar.kt; ReiAnixSettingsRepository.kt; core/google_account.py; docs/google/ANDROID_LOGIN_SETUP.md.

**Dependências:** MIG-13; coordenar com MIG-15 quando a conta integrar backup.

**Preservar:** Client IDs/configuração já usada, cancelamento explícito, logout seguro e estados de erro; nunca embutir client secret privado.

**Riscos:** UI mostra conta conectada sem credenciais válidas; ambiente sem configuração OAuth.

**Critérios objetivos de aceitação:** Login/logout operam sem bridge Python; estado é recuperado após reinício; configuração ausente explica o problema sem crash.

**Verificações:** Testes de contrato/configuração na CI. Teste de login externo em aparelho deve ser executado pelo mantenedor quando adequado, sem instrumented workflow.

**Prioridade:** P2.

### MIG-15 — Backup, recuperação e integridade

**Objetivo:** Migrar exportação/importação, manifest/checksum, snapshot de segurança e tela de recovery.

**Escopo fechado:** Compatibilidade de arquivos, migradores de manifest, validação contra path traversal, checks de integridade, whitelist de preferências, arte manual, confirmação do usuário e rollback após falha.

**Arquivos principais:** core/backup.py; core/recovery.py; views/recovery_view.py; ReiAnixSqliteDatabase.kt; BACKUP_RESTORE.md; destino nativo de configurações/recuperação.

**Dependências:** MIG-03; MIG-08 para arte manual; MIG-13 para configurações.

**Preservar:** Backups existentes ainda restauráveis; nenhuma remoção automática do banco; restauração somente sob fluxo explícito confirmado.

**Riscos:** Perda/corrupção da biblioteca e arquivos antigos incompatíveis.

**Critérios objetivos de aceitação:** Kotlin valida e restaura o formato atual e os formatos anteriores suportados; snapshot de segurança e rollback são exercitados em testes permitidos; interface não usa views Python.

**Verificações:** tests/test_recovery_mode.py, checks SQLite e casos de arquivo corrompido/path inválido no ambiente permitido.

**Prioridade:** P1.

### MIG-16 — Diagnósticos, logs e performance

**Objetivo:** Consolidar telemetria, relatórios e redaction em Kotlin.

**Escopo fechado:** Timeline de diagnóstico, trace/request/session IDs, storage/scans/artwork/player, ocultação de dados sensíveis, export/compartilhamento de relatório e histórico limitado.

**Arquivos principais:** core/diagnostic_service.py; core/diagnostics.py; core/performance.py; PerformanceDiagnostics.kt; NativePlayerActivity.kt.

**Dependências:** MIG-05, MIG-07, MIG-08, MIG-11, MIG-14 e MIG-15.

**Preservar:** Diagnóstico suficiente para regressões; logs não podem expor tokens, conteúdo privado ou PII desnecessária.

**Riscos:** Perder rastreabilidade de scan/player ou exibir informação sensível.

**Critérios objetivos de aceitação:** Fluxos críticos enviam eventos estruturados para um serviço nativo; cada log duplicado tem consumidor comprovado ou pode ser removido com evidência.

**Verificações:** tests/test_performance_monitor.py, test_player_forensic.py e test_runtime_regressions.py.

**Prioridade:** P2.

### MIG-17 — Orquestração do player totalmente nativa

**Objetivo:** Retirar dependências Python da abertura, transições e consumo, preservando o motor Media3 existente.

**Escopo fechado:** Lançamento por rota nativa, resolução de URI/legenda, fila next/previous, autoplay, posição inicial/restauração, lifecycle result, IDs de request, generations, revogação e tratamento de erro.

**Arquivos principais:** MainActivity.kt; NativePlayerActivity.kt; NativeCommandDispatcher.kt; NativePlayerRequest.kt; ReiAnixConsumptionService.kt; ui/player/*; core/android_bridge.py.

**Dependências:** MIG-04, MIG-06, MIG-09, MIG-11 e MIG-16.

**Preservar:** Engine Media3, IDs de sessão/geração, cadência de persistência, permissões SAF e comportamento existente de fila.

**Riscos:** Evento tardio da sessão anterior muta a sessão atual; evento antigo da mailbox perde o próximo episódio.

**Critérios objetivos de aceitação:** Compose inicia Activity diretamente; nenhuma solicitação de reprodução exige AndroidBridge Python; transições/progresso/resultado são nativos e eventos obsoletos são rejeitados.

**Verificações:** tests/test_native_player_lifecycle.py, test_playback_contract.py, test_next_transition.py e test_previous_transition.py via CI existente.

**Prioridade:** P1.

### MIG-18 — Composição e lifecycle do aplicativo nativo

**Objetivo:** Trocar a entrada híbrida por Activity/Compose somente depois de toda paridade funcional.

**Escopo fechado:** Activity nativa, setContent e NavHost direto, bootstrap único, configuração inicial, lifecycle foreground/background, botão voltar, app links, onboarding e recriação de processo.

**Arquivos principais:** AndroidManifest.xml; MainActivity.kt; ReiAnixComposeRoot.kt; ReiAnixComposeLibraryHost.kt; ReiAnixNavigation.kt; todos os ViewModels.

**Dependências:** MIG-05 a MIG-17; nenhum fluxo essencial pode depender da ponte Python.

**Preservar:** Rota inicial, onboarding de armazenamento, retorno do player, tema e deep links existentes.

**Riscos:** Cutover prematuro expõe dependências ocultas que eram inicializadas pelo Flet.

**Critérios objetivos de aceitação:** Cold start e todos os destinos funcionam sem FlutterFragmentActivity/Flutter engine; tratamento de lifecycle validado.

**Verificações:** Build nativo e verificações Kotlin permitidas; nenhum teste instrumentado novo.

**Prioridade:** P0, gate de cutover.

### MIG-19 — Remoção segura de NativeMailbox e snapshots híbridos

**Objetivo:** Remover o transporte somente quando não restar consumidor funcional.

**Escopo fechado:** Inventariar cada event type e action com producer, consumer, payload, IDs, estado final e persistência; migrar consumidores; retirar snapshots, FileObservers, codecs e fallback Python que se tornarem órfãos.

**Arquivos principais:** NativeMailbox.kt; NativeCommandDispatcher.kt; NativeRequestState.kt; core/android_bridge.py; core/compose_library_bridge.py; core/compose_settings_bridge.py; testes relacionados.

**Dependências:** MIG-09, MIG-13, MIG-14, MIG-17 e MIG-18.

**Preservar:** Garantias de entrega, idempotência, requestId e geração, erro terminal e diagnósticos.

**Riscos:** Remoção de uma ponte global antes que um fluxo secundário tenha sido migrado.

**Critérios objetivos de aceitação:** Busca por referências prova que não há consumidores de produção; todos os eventos/ações têm serviço Kotlin equivalente; os testes antigos são adaptados por semântica, não apagados.

**Verificações:** Contratos e regressões atualizados, CI verde no SHA final.

**Prioridade:** P0, gate global.

### MIG-20 — Build Gradle Android standalone e distribuição

**Objetivo:** Alterar o build oficial para compilar e empacotar diretamente o aplicativo Kotlin pelo Gradle.

**Escopo fechado:** Gradle settings/scripts/wrapper, manifesto e resources, dependências, signing, versioning, variante APK arm64, ZIP de distribuição e proveniência por SHA.

**Arquivos principais:** android/settings.gradle.kts, se existente; android/build.gradle.kts; android/app/build.gradle.kts; Gradle wrapper; .github/workflows/build_apk.yml; release_certification.yml; scripts ligados ao template Flet.

**Dependências:** MIG-18 e prova de runtime nativo; preparação Gradle pode ser feita antes, mas não substituir o build oficial até o cutover funcionar.

**Preservar:** Application ID, versionCode/versionName, ABI, APK/ZIP e compatibilidade de atualização/assinatura.

**Riscos:** Build local passa, CI depende do template oculto, ou mudança de assinatura impede atualização.

**Critérios objetivos de aceitação:** CI compila por Gradle/Android SDK/JDK sem setup Python/Flutter/Flet/serious_python; APK e ZIP de fato existem e estão associados ao SHA exato.

**Verificações:** Build em pull request e certificação aplicável; artefatos conferidos no run final.

**Prioridade:** P0.

### MIG-21 — Remoção do runtime Python e higiene do repositório

**Objetivo:** Remover apenas código, dependências e arquivos gerados comprovadamente sem consumidor no runtime nativo.

**Escopo fechado:** Retirar Flet/Pillow/plugins, scripts de empacotamento híbrido, main.py, app_config.py, pyproject.toml, requirements.txt, views/core Python e testes tornados obsoletos com evidência. Manter documentação histórica/fixtures justificadas e migrar checks CI para Kotlin/Gradle.

**Arquivos principais:** main.py; app_config.py; pyproject.toml; requirements.txt; core/; views/; scripts/; .github/workflows/*.

**Dependências:** MIG-19 e MIG-20; inventário de referências concluído.

**Preservar:** Nenhuma regra de domínio antes de ter teste equivalente; backup e dados existentes mantidos.

**Riscos:** Excluir módulos usados por import dinâmico, scripts gerados ou fluxo secundário.

**Critérios objetivos de aceitação:** APK executa a funcionalidade sem interpreter; busca estática e inspeção do pacote não encontram dependências de runtime Python/Flet/Flutter, serious_python, main.py ou Pillow no caminho de produção; referências arquivais são documentadas.

**Verificações:** CI nativo, imports/repository hygiene, regressões e APK/ZIP reais; sem novos testes instrumentados/emulador.

**Prioridade:** P0.

### MIG-22 — Certificação final de paridade e dados existentes

**Objetivo:** Verificar a independência nativa em fluxos de uso e no caminho de upgrade dos dados atuais.

**Escopo fechado:** Repassar matriz completa, compatibilidade de DB v29 por cópia protegida, SAF/MediaStore, scan/reconcile, metadata/artwork, progress/history, backup/recovery, account/settings, launch/lifecycle, artefatos e dependências do pacote.

**Arquivos principais:** docs/KOTLIN_MIGRATION_AUDIT.md; este roadmap; testes; workflows; tooling de certificação.

**Dependências:** MIG-02 a MIG-21.

**Preservar:** Nunca apagar o banco real; usar cópia/snapshot controlado para cenários de upgrade/restore; nenhum sucesso baseado apenas em layout visual.

**Riscos:** Declarar 100% Kotlin por uma métrica de linguagem ou por APK que ainda embarca runtime.

**Critérios objetivos de aceitação:** Todas as funcionalidades ficam NATIVO COMPLETO ou são removidas por comprovação real de ausência de consumidor; nenhum runtime Python ativo; CI requerido verde para o SHA final; artefatos correspondem exatamente a esse SHA.

**Verificações:** Todos os workflows obrigatórios concluídos, sem pendências; inspeção de dependências/arquivos APK; Kotlin/JVM somente via dispatch manual autorizado no estágio adequado; nenhum teste de emulador novo.

**Prioridade:** P0, gate final.

## 5. Estratégia incremental de ownership do banco

- Primeiramente, manter o schema e o arquivo canônicos inalterados, medir o comportamento de cada operação e completar o contrato Kotlin.
- Quando uma operação for migrada, redirecionar o fluxo ativo ao owner novo e desativar a escrita equivalente antiga para essa semântica. Um fallback só é aceitável quando sua condição é explícita e quando não há risco de repetir uma transação com resultado desconhecido.
- Não permitir que snapshots atrasados, produtores concorrentes ou replay de um evento sobrescrevam metadata manual/progresso que já foi persistido.
- O uso de requestId/generation deve atender a um contrato definido, não servir de paliativo ad hoc.
- O cutover final move bootstrap e migrações para Kotlin. Até lá, não ativar duas inicializações independentes nem deixar uma operação ser executada pelos dois runtimes.
- Compatibilidade deve ser demonstrada para o schema v29, backups existentes e dados em disco. Qualquer atualização futura de schema deve ser versionada e coberta por migração explícita.

## 6. Política para pontes e código legado

NativeMailbox, NativeCommandDispatcher e os Compose bridges possuem múltiplos consumidores e não são candidatos iniciais à remoção. Antes do cutover, produzir uma matriz de tipos de evento/ação com produtor, consumidor, contrato, IDs, estados terminais, erro e persistência. Mover os consumidores funcionais para serviços Kotlin, atualizar os testes por comportamento e procurar referências estáticas/dinâmicas em todo o repositório. Só depois retirar produtor, consumidor, filas, codecs e contratos órfãos.

Eventos de sessão do player e de scan exigem cuidado adicional por causa de generations, revogação, scan parcial e fallback idempotente. Uma classe Kotlin com o mesmo nome, uma tela Compose renderizada ou um teste que apenas verifica a existência do arquivo não prova paridade funcional.

## 7. Requisitos mínimos para cada PR futuro

Cada PR deve conter a implementação completa da unidade funcional, integração com ViewModel/StateFlow/serviço nativo/SQLite canônico, handling de erro/cancelamento/estado vazio, compatibilidade com dados/permissões, testes de regressão permitidos, diff revisado e resultados do CI vinculados ao SHA. Informar no relatório arquivos e pastas adicionados/modificados/removidos e contagem real de linhas do diff. Não remover critérios de teste para fazer a execução ficar verde. O workflow Kotlin/JVM permanece manual; não adicionar nem disparar testes instrumentados/emulador.

## 8. Gate de build native-only

O gate só passa quando o workflow para de instalar Python/Flet/Flutter e deixa de gerar host Flet. Deve compilar com Gradle, Android SDK e JDK; produzir APK instalável (arm64 conforme distribuição atual) e ZIP de distribuição quando previsto; o pacote não pode conter runtime Python/serious_python. O APK precisa ser inspecionado e associado ao SHA exato. Um artefato chamado APK que ainda embute serious_python não prova independência.

O build atual integra Compose, Navigation, Lifecycle, Coil, Media3, Credentials e Robolectric ao host Flet customizado. Reutilizar as dependências Android apropriadas, mas não criar um segundo app Kotlin paralelo só para compilar. A CI nativa deve substituir as responsabilidades atuais preservando applicationId, versioning e assinatura necessários para atualização.

## 9. Gate final de paridade

A migração só pode ser chamada 100% Kotlin quando:
- Todas as unidades funcionais da auditoria estão NATIVO COMPLETO, ou foram removidas com evidência verificável de que não tinham requisito/consumidor.
- Não há dependência ativa de FlutterFragmentActivity, Flutter engine, Flet, serious_python, main.py, imports Python ou snapshots gerados pelo runtime no caminho de produção.
- O mesmo SQLite canônico e as permissões SAF/dados existentes seguem compatíveis.
- Storage/scan, catálogo/metadata/artwork, player/progresso/histórico, listas, organização, settings/account, backup/recovery e diagnostics têm paridade nativa.
- Todos os workflows obrigatórios passam no SHA final, sem runs pendentes; os artefatos esperados existem e correspondem a esse SHA.
- Testes/regressões existentes são preservados ou substituídos por testes semânticos, não simplesmente apagados.
- O relatório final contém commits/PRs, diff, validações, artefatos e bloqueios restantes.

## 10. Próxima etapa recomendada

Começar por MIG-02 e prosseguir para MIG-03/MIG-04 antes de aumentar a quantidade de fluxos Kotlin que escrevem no banco. Parser, identidade, contratos de metadata e transações são dependências comuns de scanner, AniList, artwork, player e UI. Um erro nessa camada tem maior raio de impacto e pode apagar informações locais. Esta recomendação define a ordem técnica, mas não inicia o próximo prompt.

### MIG-02 — paridade do título após o marcador de episódio (20/10/2026)

Base da correção: `main` após o corte de entrada nativa, commit `d34ae5cd332e0974f836c53b9b1f0563332427bd`. O parser Python em `core/library_parser.py` já tinha regra conservadora para `_episode_title_after`: remove anotações entre colchetes e tokens técnicos, mas não remove palavras comuns no título do episódio. O parser Kotlin aplicava `MediaTitleNormalizer.cleanMediaTitle` ao sufixo inteiro, que também remove palavras como `complete` e `batch`; isso pode transformar um display title válido no título da série.

**Implementação desta unidade:** `MediaTitleNormalizer.episodeTitleAfter` agora replica a limpeza específica do sufixo e `MediaFilenameParser` a utiliza. Os testes Kotlin/JVM cobrem tanto a preservação de `Complete Batch` após remover `1080p WEB-DL` quanto o fallback quando o sufixo contém apenas uma versão (`v2`). Um contrato Python adicional verifica a expectativa da implementação de referência e o encaminhamento para o helper nativo.

**Limite de validação:** a suíte Python da CI pode verificar a implementação de referência e o contrato de fonte. O teste Kotlin/JVM foi adicionado, mas não é declarado executado nesta etapa: o workflow JVM é manual e permanece reservado conforme `AGENTS.md`. A CI de APK deve compilar o código Kotlin de produção e executar os contratos Python; a equivalência em runtime do teste JVM permanece não validada até a fase de testes autorizada.

Esta correção fecha apenas uma divergência identificada de apresentação de metadados. Não declara concluído MIG-02 nem a remoção de `main.py`; outros casos de parsing/identidade e os consumidores Python restantes precisam de suas próprias evidências antes do cutover.

### MIG-03 — Next/Previous nativo para sessões Compose (20/10/2026)

**Estado: implementação adicionada; validação CI pendente; não confundir esta unidade com a migração global do `main.py`.**

Problema confirmado por inspeção do caminho de execução: `NativePlayerActivity.requestEpisode()` emitia `player_next_request`/`player_previous_request` por `NativeMailbox`, mas a decisão de destino residia no consumidor Python em `main.py`. O APK nativo não contém runtime Python/Flet, então sessões abertas pela biblioteca Compose não podem depender desse consumidor.

Implementação desta unidade:

- Adiciona `ReiAnixPlayerEpisodeNavigator`, política Kotlin pura que replica a ordenação usada em `LibraryStore._episode_order_key`, os tipos não regulares e as flags de navegação do destino.
- Adiciona `ReiAnixSqliteLibraryRepository.playerNavigation(episodeId)`, que lê o episódio atual e candidatos adjacentes da base SQLite canônica, com a mesma condição de disponibilidade (`missing = 0`, `COALESCE(availability_state,'available')='available'`, tipos regulares) usada pelo caminho Python legado.
- Encaminha os botões Próximo/Anterior de sessões marcadas `nativeCompose=true` diretamente à consulta SQLite e cria um novo pedido de reprodução Kotlin, preservando ID/sessão/geração/direção de origem, settings efetivas, flags de destino e regra de retomada. O `NativePlayerActivity.onNewIntent()` continua sendo a barreira que valida o sucessor antes de reutilizar o player.
- A marca `nativeCompose` é copiada do pedido de abertura no `MainActivity`; apenas esse caminho utiliza a nova implementação. O caminho legado Flet/desktop conserva seu consumidor Python e a rota por `NativeMailbox`.
- Adiciona testes Kotlin/JVM para ordenação/flags e contrato Python para verificar o encaminhamento nativo, o predicado SQLite e a retenção explícita do caminho Flet.

**Limites e validação:** os testes Python de contrato serão executados pelo workflow usual da PR. Os testes Kotlin/JVM são adicionados, mas não serão declarados aprovados enquanto o workflow manual reservado no repositório não for executado numa fase permitida. A CI de build também precisa confirmar a compilação de produção/Kotlin e a criação/validação do APK. Ainda não houve teste de dispositivo físico.

**Escopo remanescente conhecido:** `main.py` continua necessário ao comando `flet run main.py` no Desktop e contém outros consumidores Python, incluindo partes de atualização de progresso/estado, integração Flet, eventos de armazenamento e fluxos legados. Esta unidade remove uma dependência funcional Python apenas para Próximo/Anterior em sessões nativas Compose; não remove `NativeMailbox` como um todo e não autoriza a exclusão de `main.py`.

