# ReiAnix — baseline de contratos e paridade do domínio Kotlin

**Etapa:** Prompt 03/29 — MIG-02, contratos de domínio e baseline de paridade  
**Referência de código:** branch do Prompt 02, commit de base 57de2021ca658d5c739196d796c8ce8f2d786e7e  
**Objetivo:** documentar o que as implementações Python e Kotlin realmente fazem hoje, fornecer um corpus comum de comparação e registrar explicitamente o trabalho ainda não migrado.

## Conclusão executiva

O repositório já contém implementações Kotlin reais para modelos de domínio, leitura de várias tabelas, identidade física, parsing de nomes, patch parcial de metadados e progresso de reprodução. Isso não equivale a paridade geral com LibraryStore e LibraryService. Python ainda é proprietário de várias mutações, reconciliação, ciclo de varredura, associação manual, organização, backup/recovery, projeções da biblioteca e sessões de reprodução.

Este baseline é deliberadamente uma etapa de contrato, não uma alteração do writer nem um cutover de runtime. Nenhuma operação é redirecionada para Kotlin apenas porque existe uma classe semelhante. O schema canônico permanece v29 e o arquivo SQLite existente permanece a única fonte física de dados.

## Fontes verificadas

- Python: core/library_store.py, core/library_service.py, core/library_parser.py e core/media_identity.py.
- Kotlin: data/media/MediaDomainModels.kt, MediaFilenameParser.kt, MediaIdentity.kt, data/sqlite/ReiAnixSqliteModels.kt, ReiAnixSqliteLibraryRepository.kt e SqliteDomainMappers.kt.
- Fixtures comuns: android/app/src/test/resources/media_parser_parity.tsv e media_identity_parity.tsv.
- Execução de referência Python: tests/test_media_domain_parity_baseline.py, incluída no conjunto Python do workflow APK.
- Teste Kotlin/JVM correspondente: MediaDomainParityBaselineTest.kt. Ele foi adicionado para futura execução manual/autorizada e não é disparado pelo workflow APK, nem foi executado nesta etapa.

## Matriz de operações e efeito atual

| Domínio | Operações Python ativas/relevantes | Implementação Kotlin atual | Situação da paridade |
|---|---|---|---|
| Identidade de registros | IDs SQLite de anime/episódio e chaves lookup persistidas | AnimeRecordId, EpisodeRecordId, AniListCatalogId | Contrato de ID explícito; preserva o ID persistido. Não gera chave substituta. |
| Identidade física de arquivo | identity_from_document, identity_from_file_uri, identity_from_relative_path; observações de múltiplas fontes e merge de duplicados | LocalMediaIdentity e CanonicalMediaIdentity | Parcial; o corpus compara localização em armazenamento compartilhado, SAF, MediaStore e provider cloud. Rename/move e todas as regras de reconciliação continuam sob o owner Python. |
| Parsing de nomes | parse_video_path, VIDEO_EXTENSIONS e identificação usada na ingestão | MediaFilenameParser, MediaTitleNormalizer, ParsedMediaName | Parcial; existe corpus comum para marcadores explícitos, temporada inferida por pasta, OVA, filme, arquivo numérico insuficiente e extensão não suportada. Não existe prova de igualdade para todas as combinações do parser. |
| Projeção de anime | catalog, catalog_page, home_sections e metadados de anime do LibraryStore | getAnime, listAnime, searchAnimeIds e Anime.toDomain | Parcial; leituras cobrem apenas parte das projeções. Não substituem a forma completa da API de catálogo Python. |
| Projeção de episódios | episode_by_id, catalog, continue_watching, playback_history e player_navigation | getEpisode, listEpisodes, listRecentlyPlayed e Episode.toDomain | Parcial; a projeção preserva path/URI, progress/duration, watched, missing e media_identity, mas não replica todas as consultas e ordenações de domínio Python. |
| Escrita de metadados | upsert_anime, set_manual_metadata, clear_manual_metadata, set_metadata_status, set_anilist_match e set_pending_match | updateAnimeMetadata com ReiAnixAnimeMetadataPatch | Parcial; patch nativo existe. Ainda não representa todas as transições de estado, matching/associações nem efeitos de eventos/genres do LibraryService. |
| Dados pessoais | toggle_favorite, toggle_pinned, set_personal_note e set_user_tags | Campos representados em ReiAnixAnimeRecord/Anime | Sem paridade de escrita geral; mapear os campos não implementa suas operações. |
| Episódios e classificação | upsert_episode, set_episode_identification e apply_episode_identification | indexDiscoveredMedia e modelos de Episode | Parcial e restrito ao caminho de indexação já integrado; a identificação manual, atualização de episódios existentes e preservação de todas as regras do upsert ainda requerem migração própria. |
| Pastas e fontes | add_folder, update_folder_status, update_saf_identity, remove_folder, volume/source unavailable/restore | listFolders e fluxo nativo de descoberta/SAF | Leitura parcial. Mutação completa, grants e política de origem continuam no fluxo híbrido. |
| Observações e reconciliação | record_observation, reconcile_scope_generation, reconcile_scope, reconcile_missing, apply_library_reconciliation, merge_duplicate_media_identities, recover_interrupted_scans | NativeScanRunner/NativeScanPublisher/NativeIndex com indexDiscoveredMedia | Parcial; os controles de scan e recuperação existentes são preservados, mas não representam toda a semântica de LibraryStore/ScanCoordinator. Resultado parcial ou cancelado não pode autorizar exclusão. |
| Gerações e eventos de scan | begin_scan, update_scan_progress, finish_scan, interrupted_scans, recover_interrupted_scans, claim_native_event/request | NativeScanRunner e publisher | Ainda híbrido; idempotência, persistência de todos os recibos e ownership integral não são transferidos neste baseline. |
| Playback | save_progress, set_watched, activate_playback_session, invalidate_playback_session, adjacent_episode, playback_target, next_episode e previous_episode | updatePlaybackProgress e ReiAnixConsumptionService integrados ao player nativo | Parcial; progresso está conectado, mas as regras de sessão, seleção e navegação do serviço Python não estão todas substituídas. |
| Pesquisa e descoberta de catálogo | search_options, random_catalog_item, timeline_items, duration_observations, marathon_episodes, collector_snapshot, organize_summary | searchAnimeIds e consultas Kotlin existentes | Sem paridade geral; as consultas especializadas e a lógica de descoberta permanecem em Python. |
| Preferências e conta | get_preference/set_preference/remove_preference, account/save_account/clear_account | ReiAnixSqliteSettingsRepository e projeções nativas de Settings | Parcial; leitura nativa existe para consumidores conectados, mas não representa todas as mutações e fluxos de conta. |
| Backup e recovery | create_backup_snapshot, create_backup, latest_backup, restore_backup_transaction e restore_backup | Sem substituto nativo equivalente completo verificado | Python continua owner. Não alterar formato, backup, recovery ou banco existente nesta etapa. |
| Artwork e AniList | LibraryService coordena metadata, artwork, cache, tradução, associação e efeitos derivados | Cliente/serviço AniList e leitura nativa de Artwork | Parcial; o modelo e serviços nativos existentes não substituem o producer, o cache e toda a semântica Python. |

## Contratos que o baseline torna explícitos

1. **Chaves persistidas:** AnimeRecordId e EpisodeRecordId representam os IDs reais existentes. O parser não cria IDs de banco e a identidade física não substitui a chave da linha SQLite.
2. **Identidade lógica versus localização:** URI SAF/content:// e caminho de arquivo são referências de localização, não IDs imutáveis. CanonicalMediaIdentity só é preenchida quando há evidência de localização estável.
3. **Dados opcionais e incompletos:** episódio sem número lógico pode ser representado; não se fabrica identidade de episódio a partir do título ou do nome do arquivo. Valores nulos não significam zero.
4. **Números de episódio:** o domínio mantém Double para episódio/progresso/duração quando o contrato de dados atual permite frações; IDs persistidos são Long positivos. O corpus deste prompt não muda a representação SQLite.
5. **Campos manuais e dados do usuário:** metadata_manual_fields, favorite, user_tags, is_pinned, personal_note, lookup_title, added_at, caminho local, progresso e histórico não devem ser apagados por um patch parcial de metadados.
6. **Incerteza no parsing:** um título ou episódio sem evidência suficiente permanece parcial/desconhecido. A confiança e as ambiguidades são parte do contrato de identificação, não uma razão para criar associações automaticamente.
7. **Schema e storage:** schema v29, tabelas, índices, backup e caminho canônico não são modificados por esta etapa.
8. **Falhas explícitas:** divergência, provider sem evidência local, URI inválida ou leitura sem permissão não pode ser convertida em sucesso vazio silencioso.

## Corpus compartilhado

O fixture media_parser_parity.tsv registra expectativas estáveis de título, temporada, episódio, número absoluto, tipo, confiança e suporte da extensão. O fixture media_identity_parity.tsv registra identidade física para file URI, MediaStore, SAF em armazenamento compartilhado, provider cloud, URI remota inválida e aliases de volume.

O conjunto é intencionalmente pequeno e determinístico, representando casos edge já presentes nos contratos existentes. Ele não substitui testes de integração nem prova toda a paridade de LibraryStore/LibraryService. Python valida ambos os fixtures no workflow habitual. O correspondente Kotlin/JVM lê exatamente os mesmos recursos, mas permanece fora do CI automático para respeitar a regra de execução manual definida no projeto.

## Diferenças e limites identificados

- O parser Kotlin expõe evidência por campo, campos desconhecidos e lista de ambiguidades; o modelo Python atual tem outro formato de retorno. Os dados de domínio não devem depender de serializar um dos objetos como se fossem o outro.
- O corpus compara um subconjunto definido dos resultados finais. Não declara equivalência de todas as regexes, aliases de título, regras de match ou contextos de pastas.
- Python possui operações e efeitos colaterais de domínio que não têm método Kotlin equivalente confirmado. A matriz diferencia projeção/leitura de escrita real.
- LibraryStore e o repositório SQLite Kotlin operam sobre o mesmo arquivo, mas essa coincidência física não resolve concorrência ou ownership lógico. Uma mutação deve ter um proprietário por operação.
- Este prompt não migra DDL, bootstrap, backup/recovery ou reconciliação, nem apaga qualquer módulo Python. Isso fica para as etapas dependentes deste baseline.

## Critérios de aceitação desta etapa

- [x] Matriz de operações Python e nível real de cobertura Kotlin documentados.
- [x] Corpus compartilhado para parser e identidade física, sem dependências externas.
- [x] Teste Python automatizado do corpus, integrado ao conjunto normal de regressão.
- [x] Teste Kotlin/JVM correspondente adicionado, mas não executado nem ligado ao workflow.
- [x] Dados pessoais, campos manuais, IDs e schema v29 explicitamente preservados.
- [ ] Build obrigatório e regressão Python do SHA final deste Prompt 03 concluídos com sucesso.
- [ ] Confirmação dos artefatos APK/ZIP do mesmo run, quando publicados pelo workflow.

A etapa só pode ser declarada concluída após os dois itens finais serem verificados no SHA definitivo do PR.
