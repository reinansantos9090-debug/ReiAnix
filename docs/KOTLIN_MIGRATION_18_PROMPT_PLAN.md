# Plano executável da migração Kotlin nativa do ReiAnix — 18 prompts

**Baseline:** `80fd95acd0d569d3013ac2d71fcf4cd166ae7844`. O Prompt 01 entrega a documentação. Os Prompts 02–18 são plano e exigem autorização do usuário antes da execução.

## Sequência e gates

| Prompt | Objetivo | Escopo controlado | Gate obrigatório |
|---|---|---|---|
| 02 | Linha de base nativa e fronteira de build | Revalidar Activity/Application, template Flet, Gradle e entrypoints; definir cutover mínimo no código atual, sem arquitetura/workflow paralelo. | Gate: grafo de boot comprovado e runtime híbrido preservado até a substituição. |
| 03 | Bootstrap SQLite v29 | Resolver FLET_APP_STORAGE_DATA versus filesDir/data e abrir a mesma DB existente; tratar ausência, corrupção, lock e schema inválido. | Gate: arquivo/caminho canônicos provados, dados preservados e sem DB paralelo. |
| 04 | Contratos IPC/bridge | Mapear ações/eventos/payload/requestId/ack/falha/recovery e consumidores em AndroidBridge, Compose bridges e NativeMailbox. | Gate: cada mensagem tem produtor/consumidor/resultado terminal; sem stubs. |
| 05 | SAF e permissões | Migrar grants persistidos, URI, identidade SAF/volume, revogação/reseleção e retorno Android Settings. | Gate: pastas/grants persistem; Back/cancel/error deixam estado consistente. |
| 06 | Scanner Kotlin único | Unificar SAF/MediaStore/broad storage, ownership/generation, progresso, cancelamento, dedup e recovery. | Gate: um único coordenador operacional; scans terminam com estado correto. |
| 07 | LibraryService e merge | Completar regras create/update/merge/dedup no repositório Kotlin, preservando IDs e schema v29. | Gate: resultados equivalentes, sem duplicatas, regras de merge documentadas. |
| 08 | Configurações | Migrar defaults, validação, persistência, codec/snapshot, reload e compatibilidade. | Gate: dados de preferências preservados sem reset silencioso. |
| 09 | AniList/metadados pt-BR | Migrar client/API, rate limit, cache, association por ID, tradução em chunks, erros e prioridade de metadata manual. | Gate: IDs/campos manuais preservados e falhas offline recuperáveis. |
| 10 | Artwork e thumbnails | Migrar cache, modifiedAt, priorização da arte local, eventos e refresh Compose. | Gate: arte local protegida, cache consistente e UI atualizada. |
| 11 | Discovery e identidade de episódios | Migrar parser, season/episode/absolute number, missing, observations e matching. | Gate: scans repetidos idempotentes e IDs estáveis. |
| 12 | Backup/recovery/diagnósticos | Migrar versões, preview, validação, restore transacional, recovery e logs correlacionados. | Gate: backup inválido não destrói dados; restore preserva biblioteca. |
| 13 | Telas principais Compose | Conectar Home/Library/My List/Search/Details ao repositório Kotlin canônico. | Gate: ações/rotas essenciais sem Flet; estados e filtros equivalentes. |
| 14 | Organize/Storage/Settings/lifecycle | Fechar fluxos secundários, picker, Back, intents, Settings return, foco e cancelamento. | Gate: sem eventos duplicados/seleções pendentes; estado de navegação preservado. |
| 15 | Player Media3/progresso | Conectar URI SAF, handoff, resume/seek, progress/history, autoplay, next/previous e lifecycle a Kotlin. | Gate: progresso durável e comportamento do player preservado. |
| 16 | Cutover do entrypoint | Conectar Activity/Application/AppShell nativos após gates P03–P15; retirar entrypoint Flet só com paridade provada. | Gate: cold start e fluxos críticos funcionam sem Python/Flet runtime. |
| 17 | Build APK nativo | Trocar pipeline Flet/Flutter por Gradle nativo e adaptar checks/release identity após cutover. | Gate: APK sem runtime/bundle Python/Flet/host Flutter; manifest e assinatura válidos. |
| 18 | Auditoria final e poda segura | Reinventariar, validar DB/dados, revisar diff/build e inspecionar artefatos; remover só legado sem consumidores. | Gate: todos os critérios globais e workflows aplicáveis em success no SHA final. |

## Regras universais para os prompts 02–18

1. Reler integralmente `AGENTS.md`, conferir SHA e estado oficial atuais antes de editar.
2. Trabalhar em branch/PR revisável; sem reset, force push, rewrite de histórico ou merge automático.
3. Não adicionar testes, workflows, scripts, jobs ou emuladores. Preservar testes atuais e não disparar testes/workflow Kotlin/JVM manual sem autorização.
4. Manter `library.sqlite3` como fonte de verdade e schema 29; nunca criar Room/DB paralela.
5. Não usar stub, retorno vazio, sucesso falso ou remoção de referências para baixar artificialmente o percentual Python.
6. Não remover bridges/serviços enquanto houver consumidor ativo, inclusive IPC, callback, intent, Flet view ou fluxo de player legado.
7. Preservar IDs, foreign keys, preferences/account, AniList, metadata manual, artwork, grants SAF, scan observations, history/progress, backup e recovery.
8. Distinguir análise estática, testes, build, artefatos e validação em dispositivo; revisar diff integral.
9. Só concluir quando os workflows oficiais aplicáveis tiverem status terminal success no SHA final. Ausente/queued/in_progress/failed/cancelled/skipped não é sucesso.
10. Não iniciar prompt seguinte sem autorização explícita.

## Dependências sequenciais

P02–P04 estabelecem boot, DB e IPC. P05–P06 fecham storage/scanner. P07–P12 migram domínio/infra. P13–P14 conectam UI/lifecycle. P15 fecha player. P16 só acontece após paridade; P17 depende do entrypoint nativo; P18 fecha o programa. Não paralelizar mudanças que alterem o dono de DB, scanner, IPC, entrypoint ou build.

## Política de remoção

- UI/entrypoint Flet: só após todas as rotas/ações/estados estarem ativos em Compose e cold start nativo comprovado.
- Serviço Python: só após paridade de regras/efeitos/erros e dados, consumidores rastreados e caminho Kotlin ativo.
- Bridge/IPC: só após provar ausência de consumidores legados e equivalência de ack/recovery.
- Scripts de build: manter enquanto usados; remover somente depois de build nativo provar que não precisa deles e dentro do escopo explícito.
- Testes e scripts operacionais existentes: preservar; a meta é runtime/package Android nativo, não apagar tooling Python.

## Definition of Done do Prompt 18

Entry point e Application/Activity nativos; build Gradle e APK sem dependência/runtime Python/Flet/Flutter; biblioteca, SAF, MediaStore, scanner, metadados, artwork, settings, backup/recovery, navegação/lifecycle e Media3 equivalentes; SQLite v29/dados preservados; sem DB/gravad or paralelo; diff revisado; artefatos inspecionados; workflows oficiais aplicáveis em success no SHA final.