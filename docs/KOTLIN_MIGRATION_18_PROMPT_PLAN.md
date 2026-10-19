# Plano executável da migração Kotlin nativa do ReiAnix — 18 prompts

**Baseline histórico da auditoria:** `80fd95acd0d569d3013ac2d71fcf4cd166ae7844`. O Prompt 01 entregou a documentação e o Prompt 02 migra os contratos de domínio nesta branch. Os Prompts 03–18 continuam sujeitos à autorização do usuário.

## Sequência e gates

| Prompt | Objetivo | Escopo controlado | Gate obrigatório |
|---|---|---|---|
| 02 | Contratos de domínio, modelos de mídia e identidade | Consolidar os contratos Kotlin existentes para anime, episódio, mídia local, metadados e IDs persistidos; alinhar parsing decimal, tipos de URI/SAF e classificação/normalização de gêneros. | Gate: IDs/schema v29 preservados; identidade não é inferida de títulos ambíguos; Python fica até paridade e troca dos consumidores. |
| 03 | Bootstrap SQLite v29 | Resolver FLET_APP_STORAGE_DATA versus filesDir/data e abrir a mesma DB existente; tratar ausência, corrupção, lock e schema inválido. | Gate: arquivo/caminho canônicos provados, dados preservados e sem DB paralelo. |
| 04 | Contratos IPC/bridge | Mapear ações/eventos/payload/requestId/ack/falha/recovery e consumidores em AndroidBridge, Compose bridges e NativeMailbox. | Gate: cada mensagem tem produtor/consumidor/resultado terminal; sem stubs. |
| 05 | SAF e permissões | Migrar grants persistidos, URI, identidade SAF/volume, revogação/reseleção e retorno Android Settings. | Gate: pastas/grants persistem; Back/cancel/error deixam estado consistente. |
| 06 | Scanner Kotlin único | Unificar SAF/MediaStore/broad storage, ownership/generation, progresso, cancelamento, dedup e recovery. | Gate: um único coordenador operacional; scans terminam com estado correto. |
| 07 | LibraryService e merge | Completar regras create/update/merge/dedup no repositório Kotlin, preservando IDs e schema v29. | Gate: resultados equivalentes, sem duplicatas, regras de merge documentadas. |
| 08 | Configurações | Migrar defaults, validação, persistência, codec/snapshot, reload e compatibilidade. | Gate: dados de preferências preservados sem reset silencioso. |
| 09 | AniList/metadados pt-BR | Migrar client/API, rate limit, cache, association por ID, tradução em chunks, erros e prioridade de metadata manual. | Gate: IDs/campos manuais preservados e falhas offline recuperáveis. |
| 10 | Artwork e thumbnails | Migrar cache, modifiedAt, priorização da arte local, eventos e refresh Compose. | Gate: arte local protegida, cache consistente e UI atualizada. |
| 11 | Integração nativa de discovery e identidade de episódios | Conectar os contratos/parser de domínio já existentes ao scanner e ao fluxo ativo de importação Kotlin; migrar orquestração de observations, missing, matching e deduplicação entre scans. | Gate: fluxo nativo ativo, scans idempotentes e IDs estáveis; Python só sai após não restarem consumidores. |
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

P02 estabelece os contratos de domínio e a camada pura de parsing/identidade. P03 estabelece acesso ao SQLite v29; P04 fecha IPC; P05–P06 fecham permissões e scanner; P07–P10 migram serviços e integrações; P11 liga discovery/identidade ao fluxo nativo ativo; P12–P15 cobrem recovery, UI e player. A verificação do grafo boot/runtime, prevista no plano original de base nativa, permanece gate de P16; o build APK nativo permanece no P17. P18 fecha o programa. Não paralelizar mudanças que alterem o dono de DB, scanner, IPC, entrypoint ou build.

## Política de remoção

- UI/entrypoint Flet: só após todas as rotas/ações/estados estarem ativos em Compose e cold start nativo comprovado.
- Serviço Python: só após paridade de regras/efeitos/erros e dados, consumidores rastreados e caminho Kotlin ativo.
- Bridge/IPC: só após provar ausência de consumidores legados e equivalência de ack/recovery.
- Scripts de build: manter enquanto usados; remover somente depois de build nativo provar que não precisa deles e dentro do escopo explícito.
- Testes e scripts operacionais existentes: preservar; a meta é runtime/package Android nativo, não apagar tooling Python.

## Definition of Done do Prompt 18

Entry point e Application/Activity nativos; build Gradle e APK sem dependência/runtime Python/Flet/Flutter; biblioteca, SAF, MediaStore, scanner, metadados, artwork, settings, backup/recovery, navegação/lifecycle e Media3 equivalentes; SQLite v29/dados preservados; sem DB ou gravador paralelo; diff revisado; artefatos inspecionados; workflows oficiais aplicáveis em success no SHA final.

## Registro factual da PR #199 — Prompt 02

A regra universal acima continua proibindo adicionar ou alterar testes sem autorização e proíbe despacho manual da suíte Kotlin/JVM. Para não mascarar o histórico, registre-se que o HEAD de partida da PR #199 já contém métodos de regressão acrescidos a três arquivos de teste existentes no incremento Prompt 02.2; nenhum novo arquivo de teste ou workflow foi criado. Essa alteração anterior diverge da regra de preservação estrita dos testes e é reportada como desvio, não como cumprimento da regra. Nesta reconciliação, os arquivos de teste não foram alterados novamente. A suíte JVM permanece pendente de compilação/execução autorizada; build Release de produção não equivale a teste JVM aprovado.


## Registro do fechamento do Prompt 02

A inspeção e a correção final do parser ocorreram na PR #199 sem iniciar o Prompt 03. Nomes exclusivamente numéricos com precisão decimal acima do limite conservam a origem diagnóstica `unsupported_numeric_suffix_decimal_precision`, ficam `UNKNOWN`, com confiança baixa e sem número de episódio/absoluto; conflitos de temporada do marcador `NxM` com a pasta também são sinalizados. Não foram modificados testes nem workflows nesta execução. O run `39180837684` concluiu com sucesso somente para o SHA preexistente `5feecb570b9e25e8843d04b57fb9c25dc0a7dd0b`; o resultado final precisa corresponder ao novo HEAD e fica registrado nos metadados atualizados da PR após o término do workflow. Os testes Kotlin/JVM permanecem sem execução autorizada.

## Registro factual — Prompt 06.1/18: scanner Kotlin único

- **HEAD inicial de main consultado:** `c563be50f9acd2106af944fd2a57292cc3d53eb9`; este commit é preservado como histórico, sem reescrita ou reversão.
- **Classificação do commit anterior:** `docs/KOTLIN_METADATA_ANILIST_MIGRATION.md` registra AniList/metadados PT-BR e é contribuição documental pertinente ao Prompt 09/18. Não implementou o scanner e não fecha o Prompt 06/18.
- **Fronteiras da sequência:** Prompt 05/18 cobre SAF/permissões; Prompt 06/18 e esta correção 06.1/18 cobrem o coordenador/runner do scanner, fontes SAF/MediaStore/Broad Storage, cancelamento, gerações e recuperação; Prompt 07/18 permanece reservado ao LibraryService/merge; Prompt 09/18 permanece reservado a AniList/metadados.
- **Fluxo nativo verificado em main:** `MainActivity` valida permissões/fontes e solicita operações a `NativeScanController`; `NativeScanRunner` delega descoberta a `SafScanner`, `MediaStoreScanner` e `BroadStorageScanner`; `NativeIndex` mantém gerações e staging/snapshots de descoberta; indexação SAF passa por `ReiAnixLibraryService.indexDiscoveredMedia` e pelo repositório SQLite canônico. A recuperação SAF inicia no bootstrap nativo e reutiliza receipts/snapshots validados.
- **Correção 06.1 A:** a sonda de estabilidade do MediaStore filtrava a consulta principal pelas árvores SAF configuradas, mas a sonda secundária verificava apenas se havia qualquer vídeo no volume. Vídeos fora do escopo podiam marcar uma árvore sem vídeos como `WAITING_FOR_MEDIASTORE`. A sonda agora consulta os campos de caminho/volume e verifica a mesma regra de escopo.
- **Correção 06.1 B:** o caminho de reutilização de snapshot em cache não consultava o token de cancelamento durante o processamento/publicação dos lotes. A repetição em cache agora verifica cancelamento entre lotes e itens; o cancelamento é propagado como `CancellationException`.
- **Correção 06.1 C:** `NativeScanRunner.runMediaStoreScan` chamava `MediaStoreScanner.scan` sem `libraryScopes`, embora o scanner recuse corretamente listas vazias como `NO_CONFIGURED_LIBRARY_SOURCE`. O runner agora deriva escopos das pastas SAF persistidas, valida URI/identidade/grant persistente e acessibilidade da raiz do provedor, e só usa árvores do provedor Android de armazenamento externo; o resultado conserva estados parciais quando uma raiz configurada é excluída.
- **Correção 06.1 D:** `NativeScanRunner.runBroadScan` chamava `BroadStorageScanner.scan` sem `authorizedRoots`, fazendo a implementação retornar `NO_CONFIGURED_LIBRARY_SOURCE`. O runner agora passa diretórios físicos apenas quando uma URI SAF persistida do provedor de armazenamento externo corresponde ao volume e ao diretório canônicos. URIs de provedores arbitrários nunca são convertidas em caminhos POSIX. Falhas de mapeamento são reportadas como parciais, não como descoberta vazia bem-sucedida.
- **Correção 06.1 E:** a configuração física necessária ao Broad Storage não é mais pré-condição para o scanner MediaStore; ele usa o escopo de conteúdo validado independentemente e só é rebaixado a parcial quando a configuração não pode ser comprovada.
- **Correção 06.1 F:** o MediaStore compara cada volume dos escopos SAF configurados com os volumes realmente visíveis no provider. Uma unidade removível não encontrada não pode virar um scan concluído vazio; o resultado é parcial e as observações antigas não são tratadas como ausência confirmada.
- **Correção 06.1 G:** no Android 24–28, sem `RELATIVE_PATH`/`VOLUME_NAME`, o scanner usa `DATA` apenas como caminho absoluto canônico e o compara ao diretório físico derivado da árvore SAF validada. Caminhos ausentes/inválidos ou impossibilidade de mapear o diretório tornam o resultado parcial. Snapshots antigos não são reutilizados nessa faixa; quando o scan não encontra vídeos, o MediaStore legado não pode afirmar `EMPTY_COMPLETE`, pois não fornece versão/geração confiáveis para provar estabilidade.
- **Preservação:** nenhuma tabela/schema, fonte de verdade ou coordenador paralelo foi criado. Nenhum teste, script ou workflow foi alterado; testes Kotlin/JVM e instrumentados não foram executados manualmente.
- **Validação pendente:** a análise estática e o diff descrevem o código da PR; build/CI apenas serão considerados comprovados quando um workflow oficial aplicável atingir estado terminal para o SHA final da branch. Não foi feita validação física em dispositivo.
- **Estado do Prompt 06.1:** PARCIAL até comprovar o workflow aplicável no SHA final e confirmar que não há falhas adicionais impeditivas. O Prompt 07/18 não foi iniciado.

