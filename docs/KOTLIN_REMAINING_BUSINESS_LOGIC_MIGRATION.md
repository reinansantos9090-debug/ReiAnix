# Migração das regras auxiliares residuais para Kotlin — Prompt 22/29

## Estado de referência

Repositório: reinansantos9090-debug/ReiAnix. Branch de referência: main.
Commit-base consultado: 41098826ba7bc1ed8ac6fb5b092679bfea56603c (Prompt 21/29). A árvore oficial continha 546 entradas, 53 arquivos Python fora de tests e 177 arquivos Kotlin. O build APK (run 5925, ID 38998762826) e a certificação de evidências (run 586, ID 38998762830) desse commit-base estavam concluídos com sucesso. Essa evidência se refere ao commit-base, não às alterações desta etapa.

Foram inventariados os módulos Python operacionais, entry point, views, bridges, scripts, testes relacionados e o código Kotlin equivalente localizado na árvore. A presença de uma implementação Kotlin não foi tratada como prova de que todos os consumidores Python foram removidos. Esta etapa não remove globalmente Python/Flet/Flutter, não altera o schema SQLite v29 e não introduz outra fonte de verdade.

## Migração feita nesta etapa

A lógica pura de core/library_discovery.py agora possui implementação Kotlin em android/app/src/main/kotlin/com/reiflix/reiflix_local/data/library/ReiAnixLibraryDiscovery.kt:

- timeline_groups: ano decrescente, ano inválido/desconhecido ao final, título e ID como desempate.
- duration_anomalies: durações finitas e positivas, mediana, quartis median-of-halves, mínimo de quatro observações para outliers, limites IQR e os limiares relativos legados.
- marathon_plan: exclui mídia ausente/especial, preserva a ordenação por temporada/episódio/absoluto/nome/ID, permite continuar de um caminho pedido e mantém a regra de conclusão por 90% quando a duração é conhecida.
- format_duration: formatação em português para duração conhecida/desconhecida.

As consultas timelineItems, durationObservations e marathonEpisodes foram adicionadas à ReiAnixSqliteLibraryRepository, usando o mesmo ReiAnixSqliteDatabase e schema canônico. ReiAnixLibraryDiscoveryService é compartilhado pelo ReiAnixAppDependencies. Nenhum writer foi adicionado: esses algoritmos são projeções de leitura e não alteram progresso, metadados, associações ou estado de reprodução.

Os consumidores Flet de Home/Details continuam usando core/library_service.py e LibraryStore para seus próprios fluxos, por isso a implementação Python permanece enquanto essas views e contratos estiverem ativos. O serviço nativo fica disponível no grafo Kotlin; esta mudança não declara as ferramentas Flet já conectadas às novas classes Kotlin. A remoção do fallback requer que a UI correspondente e os consumidores residuais sejam transferidos em uma etapa posterior.

Foi adicionado um teste Kotlin/JVM de paridade e ele continua reservado à execução manual, conforme a política do repositório. Também foi adicionado um contrato Python para a CI verificar a existência das implementações nativas, suas consultas canônicas e a documentação.

## Inventário de rastreabilidade

Estados:
- já migrado e validado: existe implementação Kotlin equivalente identificada e contratos prévios continuam no repositório; não implica remoção total de consumidores Python.
- migrado nesta etapa: implementação Kotlin criada neste prompt, enquanto consumidores Flet são mantidos por compatibilidade.
- parcialmente migrado: parte do domínio está em Kotlin; ainda há caminho Python, bridge ou fallback real.
- ainda dependente de Python: módulo continua sendo usado por views, runtime ou serviços híbridos.
- ferramenta de desenvolvimento: script de teste, build, fixture ou certificação, não regra operacional do usuário.

| Módulo/função Python | Responsabilidade real | Estado anterior | Implementação Kotlin | Consumidores atualizados | Dependência Python restante |
|---|---|---|---|---|---|
| core/library_discovery.py | Agrupamento por ano, outliers de duração, maratona e formatação | ainda dependente de Python nas views Flet | migrado nesta etapa: data/library/ReiAnixLibraryDiscovery.kt | Serviço registrado no ReiAnixAppDependencies para consumidores nativos | Sim, para views Flet; elas não foram removidas nem redirecionadas neste commit |
| core/organizer_ai.py | Normalização e ranking de candidatos AniList | parcialmente migrado | já migrado e validado: data/anilist/ReiAnixAniListMatcher.kt | ReiAnixAniListIntegrationService usa o matcher Kotlin | Sim, nos fluxos Flet/compatibilidade |
| core/library_parser.py | Parse de nome/caminho de mídia, temporada e episódio | parcialmente migrado | já migrado e validado: data/media/MediaFilenameParser.kt | Scanner/identificação nativa | Sim, para consumidores do scanner Python |
| core/media_identity.py | Identidade estável de arquivo/SAF/MediaStore | parcialmente migrado | já migrado e validado: data/media/MediaIdentity.kt | Serviços nativos de identidade/indexação | Sim, na orquestração híbrida |
| core/consumption.py | Normalização de progresso e estado de consumo | parcialmente migrado | já migrado e validado: data/sqlite/ReiAnixConsumptionService.kt | Player, progresso e projeções Compose migradas | Sim, para fluxos Flet/compatibilidade |
| core/genre_classifier.py | Sugestão local de gêneros por título | parcialmente migrado | já migrado e validado: data/organize/ReiAnixGenreClassifier.kt | Serviços nativos de organização | Sim, em views Python |
| core/genre_registry.py | Alias, normalização e persistência de gênero | parcialmente migrado | já migrado e validado: data/organize/ReiAnixGenreRegistry.kt | Serviços Kotlin sobre SQLite canônico | Sim, para consumidores híbridos |
| core/search_engine.py | Busca, filtros, ordenação e classificação de resultados | parcialmente migrado | já migrado e validado: ui/search/ReiAnixSearchEngine.kt e consultas SQL nativas | Compose Search | Sim, para busca Flet |
| core/anilist.py | Cliente de API, interpretação de resposta e tradução | parcialmente migrado | já migrado e validado: data/anilist/ReiAnixAniListClient.kt, matcher e integração | AniList Compose/native | Sim, enquanto runtime legado solicitar metadados |
| core/artwork.py | Download/cache de capas, thumbnails e publicação de artwork | parcialmente migrado | já migrado e validado: data/artwork/ReiAnixArtworkService.kt | Pipeline de artwork nativo nos fluxos Compose migrados | Sim, ArtworkEngine permanece com consumidores Flet |
| core/artwork_palette.py | Extração de paleta via Pillow | ainda dependente de Python para artwork Flet | Nenhum consumidor nativo equivalente foi comprovado nesta etapa; Compose usa tokens Kotlin | Nenhum fluxo nativo foi conectado a Pillow | Sim, dentro do ArtworkEngine legado |
| core/backup.py e core/recovery.py | Backup, restauração e recuperação | parcialmente migrado | já migrado e validado: ReiAnixBackupService e Recovery Mode Kotlin | Fluxo nativo de recuperação e diagnóstico | Sim, para chamadas Flet/bridge |
| core/diagnostic_service.py | Diagnóstico de runtime e relatórios | parcialmente migrado | já migrado e validado: serviços de diagnóstico/backup Kotlin | Bootstrap/Recovery nativo | Sim, nos relatórios acionados pelo runtime legado |
| core/diagnostics.py | Timeline E2E e logs estruturados Python | parcialmente migrado | ReiAnixDiagnosticTimeline cobre eventos gerados no lado nativo | Bootstrap, player e serviços Kotlin | Sim, para eventos do runtime Python |
| core/performance.py | Spans, contadores, tarefas e métricas do runtime Flet | parcialmente migrado | PerformanceDiagnostics mede métricas Android específicas | Host/Activity Android | Sim, a monitorização Python não foi removida |
| core/collector_journey.py | XP, conclusão, conquistas e título ativo | parcialmente migrado | já migrado e validado: ReiAnixCollectorJourneyService e snapshot canônico | Navigation Compose e serviço nativo | Sim, no fallback CollectorView |
| core/settings.py | Leitura/escrita de preferências no runtime híbrido | parcialmente migrado | ReiAnixSettingsRepository e contratos nativos | Settings Compose | Sim, no fluxo Flet e bridge |
| core/google_account.py | Whitelist e validação de campos persistíveis do perfil Google | parcialmente migrado | GoogleIdentity executa validação da identidade no Android | Login nativo e bridge de eventos | Sim, para receiver Python legado |
| core/scan_coordinator.py | Coordenação de scan e reconciliação | parcialmente migrado | NativeScanController/Runner/Publisher e scanners SAF/MediaStore | Fluxo nativo de storage e biblioteca | Sim, por caminhos de compatibilidade |
| core/storage_access.py | Normalização de estado/permissão e identidade de armazenamento | parcialmente migrado | Scanners e onboarding nativos | SAF/MediaStore e onboarding Compose | Sim, na orquestração híbrida |
| core/library_store.py | Store, transações, consultas e estado canônico do catálogo no runtime Python | parcialmente migrado | ReiAnixSqliteDatabase e ReiAnixSqliteLibraryRepository | Consumidores Compose migrados usam a base canônica existente | Sim, vários fluxos Flet continuam consumidores |
| core/library_service.py | Orquestração Python de catálogo, scan, metadados, artwork e funções auxiliares | parcialmente migrado | Serviços Kotlin por domínio | Consumidores dos fluxos já nativos | Sim, ainda é serviço ativo do runtime Flet |
| core/compose_library_bridge.py e core/compose_settings_bridge.py | Mensagens, snapshots e contratos Python/Kotlin | parcialmente migrado | Command/snapshot codecs e serviços nativos; bridge preservado para contratos residuais | Consumidores IPC atuais continuam correlacionando request IDs | Sim, enquanto existem consumers híbridos |
| core/android_bridge.py | Fronteira entre evento/comando Android e Python | parcialmente migrado | NativeMailbox, NativeCommandDispatcher, handlers nativos | Handlers nativos executam as operações já migradas | Sim, para comandos/notify que cruzam runtime |
| core/navigation.py | Estado de navegação Flet | parcialmente migrado | ReiAnixNavigation.kt é dono do back stack Compose | Fluxos Compose nativos | Sim, fallback Flet |
| core/genre_classifier.py e core/genre_registry.py | Regras auxiliares de classificação e normalização | parcialmente migrado | Classifier/Registry nativos | Consumidores Kotlin de organização | Sim, nas views legadas |
| core/settings_focus.py | Supressão de eventos duplicados de foco e cancelamento de tarefas da tela Flet | ainda dependente de Python, interface específica | Nenhuma regra de domínio compartilhada a mover foi encontrada | Só Settings Flet | Sim, até retirada dessa UI |
| core/dialogs.py | Abrir/fechar diálogo Flet | ainda dependente de Python, interface específica | Compose usa APIs nativas de diálogo | Views legadas | Sim, interface Flet |
| core/ui.py | Componentes, estilos, gestos e layouts Flet | ainda dependente de Python, interface específica | Design system e telas Compose | Telas Compose migradas | Sim, enquanto views Flet existem |
| core/build_identity.py e app_config.py | Identidade e configuração consumidas no runtime/build híbrido | parcialmente migrado | Configuração/identidade Kotlin e scripts de geração | Build e host Android usam seus próprios contratos | Sim, main.py e empacotamento ainda os consomem |
| core/library_discovery.py (fallback de UI) | Entregar as regras smart tools às views Flet | ainda dependente de Python | Regra de domínio em Kotlin criada nesta etapa | Serviço nativo registrado; UI Flet não redirecionada neste commit | Sim, até migração/retirada do caminho Flet |
| main.py | Inicialização Flet, registro de eventos e orquestração híbrida | ainda dependente de Python | ReiAnixApplication/ReiAnixAppBootstrap assumem partes nativas, sem substituir o runtime global | Shell Compose e serviço graph para operações nativas | Sim, entry point do runtime híbrido |
| views/collector_view.py | UI Flet da Jornada do Colecionador | parcialmente migrado | Jornada Compose e serviço Kotlin | Navigation Compose | Sim, fallback |
| views/details_view.py | UI Flet de detalhes/consumo e ferramentas locais | parcialmente migrado | Details Compose/player/consumption nativos; serviço de discovery criado | Fluxos nativos já migrados; Flet continua próprio caminho | Sim, fallback e funcionalidades legadas |
| views/home_view.py | Home Flet e ferramentas auxiliares de biblioteca | parcialmente migrado | Home/Library Compose e serviço discovery criado | Catálogo nativo e graph de discovery | Sim, smart tools Flet ainda usam Python |
| views/organize_view.py | UI Flet de organização | parcialmente migrado | Organize Compose e serviços nativos | Organize Compose | Sim, fallback |
| views/recovery_view.py | UI Flet de recuperação | parcialmente migrado | Recovery route/bootstrap Kotlin | Recovery Compose | Sim, fallback |
| views/settings_view.py | UI Flet de settings, foco e conta | parcialmente migrado | Settings Compose/repository/ViewModel/Google Identity | Settings Compose | Sim, fallback |
| core/dialogs.py, core/ui.py, core/settings_focus.py | Funções auxiliares exclusivas de interface Flet | ainda dependente de Python | Sem substituição separada necessária fora das telas Compose existentes | Nenhum fluxo de negócio nativo depende destas APIs | Sim, enquanto as views legadas estão empacotadas |
| scripts/analyze_android_runtime_log.py, scripts/audit_release.py | Análise de log e auditoria de release | ferramenta de desenvolvimento | Ferramentas Python de validação | Maintainer/CI | Python preservado como tooling |
| scripts/create_library_fixture.py, scripts/create_player_fixture.py, scripts/generate_native_sqlite_test_fixture.py | Geração de fixtures de teste e contrato de banco | ferramenta de desenvolvimento | Consumidos por testes e workflow | Build/testes | Python preservado como tooling |
| scripts/generate_build_identity.py | Gerar identidade do build e arquivos associados | ferramenta de desenvolvimento | Produz metadados usados por Kotlin/build | Workflow Android | Python preservado como tooling |
| scripts/prepare_flet_template.py | Configurar template Android/Flet gerado no build | ferramenta de build necessária à aplicação híbrida atual | Integração de overlay nativo existente | Workflow do APK híbrido | Python necessário até o runtime/packaging ser substituído |
| scripts/reianix_auto_repair.py, scripts/release_certification.py | Reparação/auditoria e certificação | ferramenta de desenvolvimento | Verificações do pipeline | Maintainer/CI | Python preservado como tooling |
| scripts/validate_android_device.py, scripts/verify_android_host.py, scripts/verify_apk_manifest.py, scripts/verify_compose_packaging.py, scripts/verify_python_bundle.py | Verificação de host, device e artefatos | ferramenta de desenvolvimento | Validam pacote Kotlin/Android construído | Workflow/manual | Python preservado como tooling |
| tests/*.py | Regressão dos caminhos híbridos, contratos, scripts e fixtures | testes | Mantidos e executados conforme workflow oficial | CI Python | Python de teste não é runtime do app |
| android/app/src/test/kotlin/**/*.kt | Contratos e regras Kotlin/JVM | testes com execução manual | Testes Kotlin/JVM existentes mais o teste de discovery desta etapa | Não executados manualmente por esta tarefa | Não é dependência operacional do app |
| android/app/src/androidTest/kotlin/**/*.kt | Testes instrumentados legados existentes | testes | Preservados sem adicionar novos | Não executados nesta etapa | Nenhuma infraestrutura de teste de emulador foi adicionada |
| requirements.txt e pyproject.toml | Dependências de build/runtime Flet e ferramentas | configuração híbrida | Nenhuma remoção global de runtime nesta etapa | Workflow de build híbrido | Python/Flet continuam empacotados até etapas futuras |

## Dependências preservadas e limites

- AniList matching já é implementado em ReiAnixAniListMatcher.kt e chamado pelo ReiAnixAniListIntegrationService; não foi reescrito.
- Parser/identidade de mídia, consumo, classifier/registry, pesquisa, artwork, backup/recovery e jornada têm serviços nativos existentes, embora módulos Python ainda possam ter consumidores Flet.
- A paleta Pillow, o foco de Settings, os diálogos e os componentes genéricos de UI encontrados permanecem dependências da UI legada, não novas APIs de domínio Kotlin.
- Os scripts em scripts/ são tooling para build, fixtures, auditoria e CI, e não foram convertidos indiscriminadamente.
- A verificação estática não comprova ausência absoluta de referências dinâmicas em runtime. Nenhuma função foi apagada apenas porque não apareceu em busca direta.
- Esta etapa não comprova 100% Kotlin: main.py, Flet, Flutter host, bridges e diversos fluxos Flet permanecem intencionais para migração posterior.

## Testes e validação

- Novo contrato Python: tests/test_prompt22_remaining_business_logic_migration.py.
- Novo teste Kotlin/JVM manual: android/app/src/test/kotlin/com/reiflix/reiflix_local/data/library/ReiAnixLibraryDiscoveryTest.kt. Não executado manualmente.
- A suíte Python e a compilação de release serão verificadas pelos workflows oficiais do commit resultante. O status do commit-base não é evidência de sucesso para esta etapa.
- Não foi criado workflow instrumentado, não foram adicionados testes de emulador e não se alterou a política de execução manual dos testes Kotlin/JVM.

## Limitações reais ao final da etapa

Embora o cálculo e a consulta nativos estejam implementados e registrados no grafo Kotlin, as telas Flet existentes ainda chamam as funções Python. Esse caminho não foi removido nem falsamente declarado substituído. A etapa fornece o port Kotlin e contratos de rastreabilidade; a retirada definitiva das funções Python ligadas a Timeline/Duração/Maratona depende da migração do respectivo UI/consumidores Flet. O runtime global Python/Flet/Flutter permanece fora do escopo deste prompt.
