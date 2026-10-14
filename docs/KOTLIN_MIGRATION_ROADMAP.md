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

**Entrega do Prompt 03/29:** docs/KOTLIN_DOMAIN_PARITY_BASELINE.md contém a matriz de operações reais e suas lacunas; dois TSVs em android/app/src/test/resources formam o corpus comum Python/Kotlin para parsing e identidade física. tests/test_media_domain_parity_baseline.py valida o lado Python no workflow normal e MediaDomainParityBaselineTest.kt consome os mesmos recursos para execução Kotlin/JVM manual/autorizada. A documentação distingue campos mapeados de operações de escrita realmente migradas. Essa entrega estabelece baseline, não declara paridade integral de LibraryStore/LibraryService.

**Verificações:** Testes Python existentes via CI normal e revisão estática. Testes Kotlin/JVM continuam manuais e não devem ser executados nesta etapa; sem testes instrumentados/emulador. Schema v29 e os dados existentes não são alterados.

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

### MIG-06 — Storage, permissões SAF e operações de arquivo

**Objetivo:** Tornar o ciclo de permissões, onboarding e operações sobre URIs integralmente nativo.

**Escopo fechado:** Persistir/revalidar grants, picker de diretório, onboarding, permissões MediaStore por versão Android, acesso amplo opcional, listar/criar/mover/renomear/excluir, observers/lifecycle e reconciliação da identidade.

**Arquivos principais:** MainActivity.kt; StorageAuthorization.kt; SafFileOperations.kt; SafScanner.kt; MediaStoreScanner.kt; BroadStorageScanner.kt; ui/storage/*; core/storage_access.py.

**Dependências:** MIG-04 e MIG-05.

**Preservar:** Grants SAF válidos, acesso às pastas previamente autorizadas e identidade após move/rename; não exigir seleção repetida quando o grant ainda é válido.

**Riscos:** Solicitar novamente a pasta a cada startup, perder identidade ao mover ou renomear arquivo.

**Critérios objetivos de aceitação:** Grants reabrem após reinício; permissões revogadas geram erro recuperável; operações e reconciliação do catálogo funcionam sem mailbox/Python.

**Verificações:** tests/test_storage_onboarding.py, test_storage_permission_flow.py e test_saf_professional.py na CI comum. Não executar workflow instrumentado.

**Prioridade:** P0.

### MIG-07 — AniList, matching, metadados e localização PT-BR

**Objetivo:** Consolidar consultas remotas e regras de associação na implementação Kotlin.

**Escopo fechado:** GraphQL search/lookup/refresh, associação manual/automática, normalização HTML, sinopse PT-BR, títulos alternativos, gêneros, estúdios, formato, ano, episódios, status, cache, limites e cancelamento.

**Arquivos principais:** core/anilist.py; core/library_service.py; ReiAnixAniListClient.kt; ReiAnixAniListIntegrationService.kt; ReiAnixSqliteAniListAssociation.kt; docs/ANILIST_MATCHING.md.

**Dependências:** MIG-02, MIG-03 e MIG-04.

**Preservar:** Metadados manuais/locais, local_path e capas; falha de rede não apaga dados existentes; PT-BR continua disponível.

**Riscos:** Cache divergente, tradução parcial tratada como válida, limites da API e falta de cancelamento.

**Critérios objetivos de aceitação:** Todos os caminhos de UI usam os serviços Kotlin; cache offline, rate limit, cancelamento e erro são explícitos; não resta cliente Python no runtime.

**Verificações:** tests/test_native_anilist_integration_contract.py e test_metadata_engine.py com fixtures reais e CI.

**Prioridade:** P1.

### MIG-08 — Artwork, capas, cache e miniaturas

**Objetivo:** Migrar o pipeline produtor sem Pillow e preservando os dados/caches existentes.

**Escopo fechado:** Download validado, cache keys, deduplicação, workers limitados, cancelamento, invalidação por geração, overrides manuais, registro de arte, extração de miniaturas e publicação de eventos incrementais consumidos por Coil.

**Arquivos principais:** core/artwork.py; core/artwork_palette.py; ui/artwork/ReiAnixLocalArtwork.kt; VideoThumbnailExtractor.kt; repositório SQLite de artwork.

**Dependências:** MIG-03, MIG-04 e MIG-07.

**Preservar:** local_path, escolhas manuais de capa, cache de arte local/offline e identidade/mtime do vídeo; resultado antigo não substitui geração atual.

**Riscos:** Capas desaparecidas, downloads duplicados, alto consumo de memória, UI não invalidada.

**Critérios objetivos de aceitação:** Um único pipeline Kotlin atualiza a fonte canônica e comunica estado à UI; download/cache/miniaturas funcionam sem Pillow ou produtor Python.

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
