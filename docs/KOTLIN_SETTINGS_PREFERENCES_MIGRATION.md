# Prompt 08/18 — migração de configurações e preferências

## A. Identificação e estado inicial

- Repositório auditado: `reinansantos9090-debug/ReiAnix`.
- Branch criada para esta etapa: `migration/prompt-08-native-settings-20261019`.
- Base de trabalho observada remotamente: `main` em `7db521307bc96c365806ccd8b71a22ed4b5dacfd`, merge da PR #203 (Prompt 07/18).
- A PR #203 foi confirmada como merged. A etapa anterior não foi presumida como pendente nem reexecutada sobre a branch antiga.
- Fonte persistente canônica: `filesDir/data/library.sqlite3`, tabela `preferences(key,value,updated_at)`, schema v29. A aplicação nativa abre o banco existente; esta etapa não cria banco/tabela nem altera o schema.
- Persistência de preferências continua textual por compatibilidade com `LibraryStore.preferences` e `core/settings.py`; normalização tipada ocorre no limite Kotlin.
- `DataStore` não foi introduzido, pois criaria uma migração e uma segunda fonte sem benefício demonstrado para os contratos atuais.
- A auditoria foi feita por referências/arquivos do GitHub remoto. Um checkout local e seu diretório de trabalho não são expostos pela conexão GitHub usada nesta execução; portanto não há declaração de estado de um worktree local.

## B. Arquitetura efetivamente encontrada

| Componente | Estado inicial e consumidores | Papel após esta etapa | Compatibilidade e risco |
|---|---|---|---|
| `core/settings.py` / `SettingsStore` | Define os valores padrão, validação, chaves legadas e import/export usados pelo runtime Flet e por alguns serviços Python. Armazena no SQLite já existente por meio de `LibraryStore`. | Mantido para consumidores Flet/desktop e runtime híbrido. Não é usado como gravador secundário pela tela Compose quando `nativePersisted=true`. | Removê-lo agora quebraria consumidores legados. Aliases `resume_playback`, `autoplay_next` e `gesture_*` continuam sendo reconciliados pela migração Python existente. |
| `ReiAnixSettingsContract.kt` | Espelha os 38 contratos conhecidos de `core/settings.py`: tipo, padrão, choices e normalização. | Reutilizado como autoridade de validação no limite nativo e ao resolver opções do player. | Chaves desconhecidas permanecem preservadas pelo repositório SQLite. Não renomear nem descartar valores legados silenciosamente. |
| `ReiAnixSqliteSettingsRepository.kt` | Já lia e escrevia a tabela canônica, em IO, com transações e confirmação de leitura. | Continua sendo o único escritor canônico do fluxo Compose. | Falha de gravação não é sucesso; não há fallback para uma segunda base. |
| `ReiAnixSettingsRepository.kt` / `ReiAnixSettingsViewModel.kt` | StateFlow, estado de operação, serialização por chave, leitura canônica e notificações de compatibilidade já existiam. | Reutilizados. Efeitos de `artwork.enabled` e `artwork.cache_limit_mb` são solicitados somente depois da confirmação do valor no SQLite. | `NativeMailbox` permanece como notificação pós-commit para consumidores Python, não como fonte persistente concorrente. |
| `ui/settings/ReiAnixSettings.kt` / host Compose | A tela Settings Compose já está na rota nativa ativa e apresenta as categorias configuradas. | Mantida; os controles continuam ligados ao ViewModel e ao contrato real de chaves. | A tela Settings do Flet continua existindo para os consumidores legados/desktop, não como segunda tela ativa da rota Android Compose. |
| `ReiAnixComposeTheme.kt`, tokens e host | O host observa `themeMode`, tamanho de cards, thumbnails e preferências de biblioteca usando `collectAsStateWithLifecycle`. | Preservada a implementação de tema e tokens existente, sem criar outro design system. | Os tokens escuros existentes continuam sendo a referência: #050505, #0B0B0B, #111111, #171717, diálogo #141414 e azul de destaque #3D8BFF. |
| `NativeCommandDispatcher.kt` / `NativePlayerRequest.kt` | O comando de player recebia opções serializadas no handoff híbrido Python → URI → Android. | O dispatcher consulta agora as chaves `player.*`, `gestures.*` e `audio.*` no SQLite canônico antes de criar o Intent. O request normaliza os valores usando o contrato Kotlin. | Quando a leitura do banco falha, o handoff anterior é mantido. Identidade da mídia, URI, posição e token de sessão não são substituídos pela leitura de configuração. |
| `NativePlayerActivity.kt` | Já persistia diversas mudanças originadas nos próprios controles nativos por meio do repositório SQLite. | Permanece utilizando os valores validados do request e seus mecanismos Media3 existentes. | A lógica de progresso/retomada e o handoff que escolhe a posição inicial ainda têm dependência do runtime híbrido; ver pendências. |
| `ReiAnixAniListIntegrationService.kt` | Já lê `metadata.anilist_enabled` e `metadata.auto_match` na base canônica. | Nenhuma segunda implementação adicionada; o serviço existente continua aplicando essas opções. | Consumidores Python legados continuam mantidos. |
| `ReiAnixArtworkService.kt` | Tinha comportamento nativo de download/cache, mas o limite efetivo era fixo e o serviço não consultava `artwork.enabled`. | Agora lê ambas as preferências da tabela canônica, pausa novos downloads remotos se desabilitados, cancela trabalhos remotos ativos ao desabilitar e aplica o limite configurado apenas removendo arquivos sem referências persistidas. | Imagens locais/manuais não são apagadas por desabilitar arte remota. Falhas de leitura são fail-closed para novos downloads e não apagam capas existentes. |
| `MainActivity.kt`, SAF e serviços nativos | Seleção de pastas, concessões Android, backup/import/export e algumas ações Settings já tinham launchers e serviços Kotlin. | Reutilizados; não foi criado um campo de preferência fictício para substituir uma URI de pasta ou um grant SAF real. | Uma URI gravada não é considerada permissão. Grants persistentes e APIs Android existentes continuam sendo a autoridade de acesso a conteúdo. |
| `core/compose_settings_bridge.py`, snapshot `settings.json` e `main.py` | O snapshot é uma projeção de estado de conta/armazenamento e capacidades; a mailbox comunica notificações de mutação nativa a serviços Python. | Continuam como ponte temporária e para consumidores não migrados. A tela Compose não lê o JSON como fonte principal de preferências. | A notificação Python pós-commit atualiza o cache/efeito legado; para a mutação marcada `nativePersisted=true`, o caminho de compatibilidade não grava a mesma preferência outra vez. |

## C. Inventário das preferências persistidas

Todas as chaves abaixo são linhas em `preferences`, guardadas como texto. Os valores padrão são usados quando não existe linha ou quando um consumidor aplica o fallback tipado; a migração não grava padrões sobre valores personalizados já existentes. O contrato completo e as choices ficam em `ReiAnixSettingsContract.kt` e em `SettingsDefaults.DEFINITIONS` no Python.

| Grupo / chave | Tipo | Padrão real | Consumidores e aplicação observada | Compatibilidade / risco |
|---|---|---|---|---|
| `app.confirm_destructive` | boolean | `true` | Settings Compose e confirmações de ações destrutivas já implementadas; contrato persistido é nativo. | Efeitos de confirmações no runtime Flet permanecem via notificação de compatibilidade. |
| `appearance.theme` | enum: system/light/dark | `dark` | Host Compose → SettingsViewModel.themeMode → ReiAnixComposeTheme; também aplicado por Python na UI Flet/desktop. | Sem renomear chave nem duplicar tokens. O fluxo nativo é reativo por StateFlow. |
| `appearance.card_size` | enum: small/medium/large | `medium` | Projeção do host Compose e componentes de biblioteca; Flet continua usando SettingsStore. | Valores fora das choices são normalizados/rejeitados conforme o contrato. |
| `appearance.show_thumbnails` | boolean | `true` | Preferência visual consumida pelo host/telas Compose e Flet. | A configuração não cria um novo cache de imagens. |
| `library.sort_default` | enum: added_desc/title_asc/title_desc/recently_watched | `added_desc` | SettingsViewModel/host e ViewModels de biblioteca já recebem a ordenação por projeção. | Fonte única permanece a linha em `preferences`; alguns filtros Flet ainda têm consumidor Python. |
| `library.grid_density` | enum: small/medium/large | `medium` | SettingsViewModel/host e tela Compose da biblioteca. | Sem segundo modelo persistente. |
| `library.page_size` | inteiro: 24/36/48/72 | `36` | SettingsViewModel e consulta/apresentação Compose da biblioteca. | Valor validado antes de ser aplicado; registros de biblioteca não são reescritos. |
| `library.continue_watching` | boolean | `true` | Projeções Compose e runtime Flet que exibem a linha “continuar assistindo”. | Ainda há efeitos legados em Python; não alterar progresso salvo. |
| `library.continue_watching_limit` | inteiro: 5/10/15/20 | `10` | Host e Home/Library Compose limitam a lista exibida. | Muda apresentação, não elimina itens nem progresso do banco. |
| `player.autoplay_next` | boolean | `true` | NativePlayerRequest passa a usar o valor canônico antes de abrir o Media3; controles nativos também persistem mudanças no SQLite. | Mantém o nome atual da chave e alias legado `autoplay_next`. |
| `player.resume` | boolean | `true` | SettingsStore Python usa a preferência ao escolher a posição de retomada; os registros de progresso continuam no banco canônico. | A decisão sobre a posição inicial ainda nasce no handoff híbrido; não foi sobrescrito um offset explícito no dispatcher. |
| `player.default_speed` | decimal: 0.5/0.75/1.0/1.25/1.5/1.75/2.0 | `1.0` | NativePlayerRequest usa valor validado de SQLite para o Media3; controles de velocidade mantêm persistência nativa. | Valores inválidos caem no padrão documentado sem modificar a identidade/posição da mídia. |
| `player.aspect_ratio` | enum: fit/fill | `fit` | NativePlayerRequest e NativePlayerActivity/resize mode. | Aliases históricos são reconciliados pelo SettingsStore; a nova leitura nativa usa o contrato atual. |
| `player.zoom_enabled` | boolean | `false` | NativePlayerRequest e camada de gestos do player. | Sem alterar implementação interna do ExoPlayer/Media3. |
| `player.immersive` | enum: always/landscape/never | `always` | NativePlayerRequest e política da janela do player. | Defaults/choices vêm do contrato canônico. |
| `player.rotation` | enum: auto/portrait/landscape | `auto` | Request e política de rotação do player. | Mudança não grava progresso nem troca mídia. |
| `player.pip` | boolean | `true` | Request e configuração Picture-in-Picture. | Mantém os limites e permissões Android existentes. |
| `player.lock_mode` | boolean | `false` | Request/controles do player. | Preferência separada do estado transitório de uma sessão. |
| `player.auto_hide_seconds` | inteiro: 5/10/15/30/0 | `5` | Request e política de ocultação dos controles. | Valor 0 mantém a semântica atual “nunca”; normalização evita valores incompatíveis. |
| `player.double_tap_seek_seconds` | inteiro: 5/10/15/30 | `10` | Request e controles de seek. | Não muda progresso canônico sem ação do usuário. |
| `player.long_press_speed` | decimal: 1.5/1.75/2.0 | `2.0` | Request e política de long press. | Validado na fronteira de configuração. |
| `player.max_video_resolution` | enum: auto/480p/720p/1080p/1440p/2160p | `auto` | Request e restrições avançadas de seleção Media3 já existentes. | Não promete que a fonte tenha uma faixa com a resolução escolhida. |
| `player.max_video_frame_rate` | inteiro: 0/24/30/60 | `0` | Request e restrições avançadas de faixas. | `0` preserva automático. |
| `player.max_audio_channels` | inteiro: 0/2/6/8 | `0` | Request e restrições de áudio já existentes. | `0` preserva automático. |
| `gestures.volume` | boolean | `false` | NativePlayerRequest e camada de gestos. | Alias histórico `gesture_volume` segue reconhecido por SettingsStore. |
| `gestures.brightness` | boolean | `false` | Request e gestos de brilho do player. | Alias legado preservado. |
| `gestures.double_tap` | boolean | `false` | Request e controles de toque. | Alias legado preservado. |
| `gestures.long_press` | boolean | `false` | Request e controle temporário de velocidade. | Alias legado preservado. |
| `audio.preferred_language` | language tag | `""` | Request, seleção de áudio e políticas Media3 nativas existentes. | Tag validada; a preferência não garante que uma faixa exista na mídia. |
| `audio.preferred_subtitle_language` | language tag | `""` | Request e seleção de legendas. | Sem apagar faixas ou arquivos locais de legenda. |
| `audio.subtitles` | enum: auto/always/never | `auto` | Request e applySubtitlePreferences. | Preserva semântica do player existente. |
| `audio.subtitle_scale` | decimal: 0.75/1.0/1.25/1.5 | `1.0` | Request e estilo/renderização de legenda nativa. | Valor validado; não se criou um controle de atraso inexistente. |
| `audio.subtitle_bottom_padding` | inteiro: 4/8/12/16 | `8` | Request e apresentação das legendas. | Só altera layout de legenda. |
| `audio.subtitle_embedded_style` | boolean | `true` | Request e aplicação de estilo das legendas. | Sem alteração do progresso de reprodução. |
| `metadata.anilist_enabled` | boolean | `true` | ReiAnixAniListIntegrationService já lê SQLite antes de busca/lookup/match. | Consumidores Python seguem compatíveis. Desabilitar não elimina metadados existentes. |
| `metadata.auto_match` | boolean | `true` | Serviço AniList nativo verifica a preferência antes de auto-match/reconciliação. | Não remove associações manuais existentes. |
| `artwork.enabled` | boolean | `true` | Agora `ReiAnixArtworkService` consulta SQLite antes de solicitações/backfill remotos. A mudança persistida solicita cancelamento/retomada de trabalhos nativos. | Desabilitar arte remota não desabilita imagens locais/manuais nem apaga capas persistidas. Falha ao ler settings impede novos downloads e registra log. |
| `artwork.cache_limit_mb` | inteiro: 64/128/256/512 | `128` | Agora o trim nativo lê o limite do SQLite e remove apenas arquivos sem referências persistidas. A mudança de valor dispara um trim pós-commit. | Banco e chaves não mudam; erros usam 128 MB somente como fallback de aplicação e não regravam a preferência. |

### Chaves legadas adicionais

O SettingsStore contém migração idempotente de aliases, copiando apenas quando a chave canônica não tem valor:
- `resume_playback` → `player.resume`
- `autoplay_next` → `player.autoplay_next`
- `gesture_volume`, `gesture_brightness`, `gesture_double_tap`, `gesture_long_press` → as respectivas chaves `gestures.*`

Os aliases não são apagados por esta etapa. O formato JSON exportado continua sendo `reiflix-settings` com schema_version suportado 1/2; import/export do Compose usa `ReiAnixSettingsFileService` e uma transação única da tabela canônica.

## D. Mudanças implementadas nesta etapa

- `NativePlayerRequest.withCanonicalSettings`: converte um conjunto de valores vindos do SQLite em opções de player tipadas/normalizadas; se o mapa não estiver disponível, preserva o request compatível antigo.
- `NativeCommandDispatcher.dispatchPlay`: consulta chaves `player.*`, `gestures.*`, `audio.*` no SQLite canônico em seu executor de comandos dedicado, fora da thread principal, antes de chamar `startActivity`.
- `ReiAnixArtworkService`: verifica `artwork.enabled` nas requisições e no backfill, lê `artwork.cache_limit_mb` para calcular o orçamento de limpeza e aplica os efeitos destas preferências. Limpeza continua protegendo arquivos referenciados/local/manual.
- `ReiAnixSettingsRepository`: depois do commit SQLite e da confirmação de estado, agenda os efeitos nativos de arte; falha de trimming não deve fingir que a gravação persistente foi revertida.
- `NativePlayerRequestTest.kt`: acrescentados testes JVM que descrevem override por preferência canônica, fallback para request legado quando SQLite está indisponível, defaults para chaves ausentes e fallback seguro de valor inválido. Esses testes foram adicionados, mas não foram executados manualmente.
- `tests/test_compose_settings_preferences.py`: contratos de regressão para garantir que o player lê do SQLite e as preferências de arte controlam o download/cache.
- Esta documentação: inventário, fonte de verdade, integração, riscos e pendências.

## E. Tratamento de falhas e preservação

1. Chaves conhecidas são normalizadas com `ReiAnixSettingsContract`; um valor não permitido não é silenciosamente salvo como valor arbitrário pelo caminho nativo.
2. O repositório grava em `preferences` existente dentro de transação e relê a linha confirmada antes de retornar sucesso.
3. A interface muda para SUCCESS após confirmação da escrita; em falha de persistência, mantém o último valor confirmado. A notificação pela mailbox acontece depois do commit e não volta a gravar a preferência se `nativePersisted=true`.
4. O player usa as preferências canônicas para resolver o request. Se a consulta SQLite falhar, fica com o handoff anterior; mídia, ID de episódio, offset, sessão e dados de transição não são reescritos pela configuração.
5. O artwork service usa valores padrão documentados apenas para decisões de execução quando falta configuração ou ela é inválida. A preferência persistida não é corrigida automaticamente nesse fallback.
6. `artwork.enabled=false` interrompe novos downloads remotos/cancela trabalho ativo, sem apagar arte local, manual ou já referenciada.
7. O limitador de cache consulta referências canônicas antes de remover arquivos. Um orçamento pode ser ultrapassado se não houver arquivos removíveis com segurança.
8. URI de pasta e grants SAF continuam sendo tratados como configuração de armazenamento + autorização Android distinta; esta etapa não converte `content://` em caminho físico nem apaga concessões para “corrigir” preferências.

## F. Estado da UI e de outros consumidores

- A tela Settings Android ativa é `ui/settings/ReiAnixSettings.kt`, roteada pelo host Compose e ligada ao ViewModel/StateFlow existente.
- Tema/appearance e projeções nativas de biblioteca continuam lendo o estado nativo; não foram criados tokens ou uma segunda UI Compose.
- AniList/metadata usam settings canônicos no serviço Kotlin existente.
- Player aplica as opções canônicas lidas na fronteira nativa do handoff e mantém Media3/ExoPlayer existente.
- Arte remota e limite de cache são aplicados pelo serviço nativo.
- SAF, backup, import/export, limpeza nativa de cache e operações de conta já têm caminhos nativos no fluxo Compose e foram preservados.
- `main.py`, `core/settings.py`, `core/compose_settings_bridge.py` e a UI Flet permanecem para desktop e consumidores Android híbridos ainda ativos. A mailbox é uma notificação/compatibilidade pós-commit, não uma segunda persistência para as preferências da tela Compose.

## G. Diff, validação e workflow

Arquivos modificados/criados pela etapa:
- `android/app/src/main/kotlin/com/reiflix/reiflix_local/player/NativePlayerRequest.kt`
- `android/app/src/main/kotlin/com/reiflix/reiflix_local/bridge/NativeCommandDispatcher.kt`
- `android/app/src/main/kotlin/com/reiflix/reiflix_local/data/artwork/ReiAnixArtworkService.kt`
- `android/app/src/main/kotlin/com/reiflix/reiflix_local/data/settings/ReiAnixSettingsRepository.kt`
- `android/app/src/test/kotlin/com/reiflix/reiflix_local/NativePlayerRequestTest.kt`
- `tests/test_compose_settings_preferences.py`
- `docs/KOTLIN_SETTINGS_PREFERENCES_MIGRATION.md`

Não foram removidos nem movidos arquivos; não foi alterado o schema ou adicionada dependência/workflow. Nenhum teste Kotlin/JVM foi executado manualmente, conforme a restrição; a execução automática de Python/CI e a compilação de release serão registradas a partir do workflow real associado ao último commit da PR. O teste JVM adicionado é cobertura futura/CI manual, não uma execução alegada.

## H. Limitações e pendências

- `player.resume` participa da decisão de retomada/posição inicial no runtime híbrido. A posição é correlacionada com o histórico/progresso e pode ser explícita em requests de transição; esta etapa não a substitui por um boolean no dispatcher para não zerar offsets intencionais. A remoção dessa dependência exige migrar a decisão de retomada e seus contratos de progresso, preservando a semântica de sessão e handoff.
- Flet/desktop e alguns efeitos de runtime ainda dependem de `core/settings.py` e de `apply_settings_runtime`/notificações de mailbox. Não foi removido Python nem simulada uma migração total.
- Não houve validação manual em dispositivo Android nesta etapa.
- Se compilação/CI ou testes de contrato falharem, o registro final deve incluir o erro real e a etapa só pode ser declarada PARCIAL/BLOQUEADA conforme a evidência.

## I. Veredito de referência

**PARCIAL** até que a execução real do CI termine e sejam resolvidos os consumidores/efeitos legados descritos acima. Não há merge automático; a PR é enviada contra `main` e aguarda revisão.
