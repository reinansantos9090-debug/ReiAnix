# MIG-16 / Prompt 16/29 — Diagnósticos nativos em Kotlin

## Escopo e referência

Esta documentação descreve as alterações do Prompt 16 na branch `implementation/prompt-16-kotlin-diagnostics-20261016`, criada a partir da `main` observada no commit `f226eddb674325aca254af802c5770cbb5d575c7` (migração nativa de backup/recuperação do Prompt 15).

O objetivo desta etapa é fazer os fluxos de diagnóstico originados em componentes Android/Kotlin registrarem eventos diretamente em Kotlin, exibir o histórico nativo na seção Compose **Configurações → Diagnóstico** e incluir esses registros no relatório exportado. Esta etapa não remove Python/Flet globalmente: telas e serviços legados ainda têm consumidores reais.

## Arquitetura encontrada

- `PerformanceDiagnostics.kt` já coletava métricas de frame, amostras de memória e marcos de inicialização do player. Antes desta etapa, esses registros eram enviados pela `NativeMailbox` em `performance_metrics` e `performance_player`, para serem recebidos por `main.py` e agregados por `core/performance.py`.
- `core/diagnostics.py` mantém uma `DiagnosticTimeline` Python em memória, limitada a 300 eventos, com campos de correlação de request/scan/refresh e logging estruturado. Ela permanece necessária aos caminhos Flet/híbridos que ainda produzem/consomem esses eventos.
- `core/diagnostic_service.py` constrói um relatório técnico amplo a partir do SQLite canônico, timeline, performance e snapshots do runtime Python. Ele continua existindo para os consumidores legados; este prompt não o apaga nem declara que todos os diagnósticos do runtime Python foram removidos.
- `ReiAnixDiagnosticsService.kt` já exportava um resumo local privacy-first com versão, plataforma, integridade do SQLite, contagens da biblioteca e estado resumido de artwork. Esse relatório não incluía a timeline ou as métricas de performance agregadas.
- A seção Compose Diagnóstico já acionava a verificação de integridade e a exportação nativa via `MainActivity` e o seletor Android `CreateDocument`.
- `NativeScanController`, `NativePlayerActivity`, o serviço nativo de artwork, o serviço AniList e o serviço de backup/recuperação são responsáveis pelos estados operacionais reais. O diagnóstico observa seus resultados; não assume essas responsabilidades.

## Implementação Kotlin

### Timeline e contrato do evento

Novo arquivo: `android/app/src/main/kotlin/com/reiflix/reiflix_local/data/diagnostics/ReiAnixDiagnosticTimeline.kt`.

O contrato nativo registra:

- `id` UUID e `sequence` monotônica por processo, para identificar e desempatar eventos;
- `timestampMs` para horário civil e `elapsedRealtimeNs` para ordenação/duração monotônica;
- `name`, `severity`, `source` e `result`;
- `requestId`, `scanId`, `refreshId` e `sessionId` quando fornecidos pelo produtor;
- exceção resumida e contexto escalar limitado, sem serializar objetos arbitrários.

A timeline é observacional e **process-local/em memória**: não é uma tabela, repositório do domínio nem segunda fonte de verdade. Seu histórico mantém no máximo 300 eventos e o relatório exporta no máximo os 100 mais recentes. Os consumidores de Compose consultam um `StateFlow` de snapshots imutáveis; a UI exibe até 20 eventos para evitar carregar a história toda.

A coleta defensiva não deixa falhas de diagnóstico escapar para a operação observada. Níveis desconhecidos são normalizados para `INFO`. Campos de contexto cujas chaves indiquem caminhos, URIs/URLs, títulos, conteúdo de arquivos, credenciais ou dados de conta são descartados; strings que aparentem incluir caminhos, URLs ou segredos são ocultadas. Erros preservam o tipo de exceção e uma mensagem curta apenas quando ela não parece conter dados sensíveis.

### Fontes nativas conectadas

- **Performance Android:** `PerformanceDiagnostics.kt` publica amostras de memória, janelas agregadas de frames e marcos de performance do player diretamente na timeline nativa. Os buckets de frames e seus limiares permanecem; os contadores são agregados por janela e reiniciados após a emissão. Os eventos de performance já migrados não passam mais pela mailbox apenas para serem gravados por Python.
- **Scanner:** `NativeScanController` registra início, solicitação de cancelamento e resultado terminal. O evento terminal usa o estado efetivamente classificado (`SUCCEEDED`, `PARTIAL`, `CANCELLED` ou `FAILED`) e contagens já calculadas pelo controlador. Não se registra cada lote/progresso como um evento adicional.
- **Player:** `NativePlayerActivity.onPlayerError` registra erro de playback somente para a sessão/atividade atual, usando `playerSessionId`, `playerGeneration` e `transitionGeneration` existentes. Isso não grava progresso nem altera Media3/ExoPlayer.
- **AniList:** o callback pós-commit nativo registra `ANILIST_METADATA_COMMITTED`, reaproveitando o mesmo `requestId` da notificação de projeção já existente. A consulta, matching e escrita de metadados continuam pertencendo ao serviço AniList.
- **Artwork:** o serviço nativo registra falha de download sem incluir ID do anime, URL ou caminho de arquivo, e registra publicação após o resultado de artwork já persistido. A notificação de domínio `ARTWORK_PUBLISHED` pela mailbox permanece, pois ela atualiza consumidores híbridos além do diagnóstico.
- **Backup e recuperação:** wrappers de operações já existentes registram sucesso somente depois que o método original retorna e registram falha antes de propagar a exceção original. O contexto inclui apenas quantidade agregada de linhas/arte reconectada ou tamanho do arquivo de backup; não inclui conteúdo, caminho ou nome do arquivo. Nenhuma regra de validação/restauração foi movida para o logger.
- **Exportação:** `ReiAnixDiagnosticsService.exportJson()` inclui `timeline.recent_events` e `performance` juntamente com o resumo técnico existente. Continua excluindo autenticação, tokens, títulos, paths de vídeo e URIs de documentos.
- **Compose:** Settings coleta o `StateFlow`, mostra estado vazio quando não existem eventos na sessão e apresenta os 20 eventos mais recentes com severidade, origem, horário, resultado e identificadores de correlação disponíveis.

## Persistência, concorrência e desempenho

Não foi criada outra base SQLite, tabela, esquema, fila persistente ou serviço paralelo de domínio. A timeline não sobrevive ao fim do processo e não apaga ou altera registros operacionais. O relatório técnico ainda lê a base canônica somente pelas operações de diagnóstico existentes.

Os eventos são adicionados sob um lock e publicados como snapshot limitado. A UI observa um fluxo de estado, em vez de iniciar consultas durante recomposições. O processamento de frame continua agregado; o player não grava progresso via diagnóstico. Nenhum temporizador, loop de retry, bloqueio síncrono de SQLite ou telemetria remota foi introduzido.

A instrumentação de diagnóstico não substitui as notificações/ações operacionais necessárias à mailbox. O caminho de eventos de performance transferidos ao relatório nativo deixa de usar a mailbox exclusivamente para logging; notificações de domínio ainda consumidas por Python (por exemplo, publicação de artwork e atualização de projeção AniList) são mantidas. Remover essas notificações exigiria a migração dos respectivos consumidores, fora do escopo desta etapa.

## Privacidade

- Sem envio automático/externo de logs, analytics ou relatórios.
- Sem persistência de timeline em SQLite ou retenção permanente.
- Sem incluir em evento contexto arbitrário/objeto completo, conteúdo de arquivo, token, senha, cookie, URI/URL ou caminho bruto.
- A exportação informa explicitamente que não inclui autenticação, tokens, títulos, caminhos de vídeo, URIs de documentos, caminhos brutos de eventos ou telemetria remota.
- Mensagens de exceção são reduzidas ou ocultadas se aparentarem conter caminho/URL/segredo. A classe da exceção continua disponível para triagem.

## Testes e validação

Adicionado `tests/test_prompt16_kotlin_diagnostics_contract.py` ao conjunto Python de regressão executado pelo workflow comum. Os contratos cobrem limite/correlação da timeline, remoção da delegação de eventos de performance migrados, inclusão da timeline/performance no relatório, atualização da interface Compose e integração com scanner, player, artwork, AniList e backup.

Os testes Kotlin/JVM permanecem manuais conforme a política do projeto; nenhum workflow manual foi disparado. Nenhum teste de emulador/instrumentado foi adicionado ou executado. A conclusão da etapa depende do workflow obrigatório de build/validação associado ao SHA final da branch/PR e da verificação de APK/ZIP se publicados por esse workflow.

## Limitações reais restantes

1. `core/diagnostics.py`, `core/diagnostic_service.py` e `core/performance.py` não foram excluídos: ainda existem consumidores Python/Flet e o relatório legado oferece verificações adicionais do catálogo, storage, órfãos, duplicações, AniList e consumo que não foram replicadas integralmente pelo exportador nativo.
2. A timeline Kotlin é local à vida do processo; não é um histórico persistente. Ela serve para inspeção na sessão e exportação do estado disponível naquele momento.
3. A coexistência híbrida continua para eventos de domínio e projeções ainda consumidos por Python. Esta etapa não remove NativeMailbox, Flet, Flutter nem `main.py`.
4. A integração nativa cobre os produtores listados nesta documentação; não reivindica migração completa de todos os logs ou todos os erros de cada subsistema Python.
