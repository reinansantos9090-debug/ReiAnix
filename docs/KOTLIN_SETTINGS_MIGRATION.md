# Migração das configurações para Kotlin nativo

## Estado e escopo

Esta documentação descreve a migração de Settings implementada no Prompt 08/29. O banco canônico continua sendo `filesDir/data/library.sqlite3`, tabela `preferences`, schema v29. Os valores continuam armazenados como texto para compatibilidade com `core/settings.py`; não foi criado banco, arquivo JSON permanente nem repositório concorrente para preferências.

A tela de configurações continua em Jetpack Compose (`ui/settings/ReiAnixSettings.kt`) e observa `ReiAnixSettingsViewModel` / `ReiAnixSettingsRepository` por `StateFlow`. O snapshot `data/reianix-compose/settings.json` é somente uma projeção de estado de conta, armazenamento e capacidades; não é a fonte de verdade das preferências.

## Implementação Kotlin

- `data/settings/ReiAnixSettingsContract.kt`: chaves, tipos, valores padrão e validação compatíveis com o contrato legado.
- `data/settings/ReiAnixSettingsFileService.kt`: exporta e importa o formato JSON versionado `reiflix-settings`, valida todos os campos suportados antes da gravação e persiste o conjunto importado em uma transação SQLite única.
- `data/sqlite/ReiAnixSqliteSettingsRepository.kt`: leitura, escrita e remoção explícita na tabela canônica `preferences`, com transação e confirmação pela leitura da linha persistida.
- `data/settings/ReiAnixSettingsRepository.kt`: lê primeiro o SQLite canônico; grava diretamente nele; atualiza o estado Compose somente depois da confirmação da gravação; em falha mantém/restaura o valor confirmado. As gravações da mesma chave são serializadas e uma solicitação antiga que ainda não começou a escrever é cancelada quando já existe uma solicitação mais recente.
- `viewmodel/ReiAnixSettingsViewModel.kt`: expõe o estado e projeções reativas.
- `MainActivity.kt`: usa `CreateDocument` e `OpenDocument` para escolher arquivos com APIs Android nativas; lê/escreve por `ContentResolver`, registra resultados correlacionados por `requestId` e notifica consumidores Python somente após o commit da importação.
- `data/artwork/ReiAnixArtworkService.kt`: a limpeza de cache solicitada pela tela Compose também é executada nativamente, com proteção para arquivos ainda referenciados por fontes locais/manuais; solicitações de download em andamento são canceladas antes da limpeza.
- `ui/settings/ReiAnixSettings.kt`: interface Compose, usando os componentes e tokens existentes.
- `NativePlayerActivity.kt`: as opções que o próprio player altera (autoplay, gestos, velocidade, proporção e bloqueio) também são gravadas no SQLite nativo, em vez de solicitar que Python as grave.
- As ações `reset_player` e `reset_all_settings` agora restauram os padrões pelo repositório Kotlin, em uma transação SQLite única. Depois da confirmação, o estado Compose é atualizado e uma notificação em lote atualiza os consumidores legados sem repetir as gravações.

O SQLite não é recriado, limpo ou migrado por esta alteração. Chaves desconhecidas são preservadas pelo contrato Kotlin; chaves conhecidas são validadas antes da gravação. Uma gravação só é considerada persistida depois que `setPreference` retorna o registro confirmado.

## Inventário das preferências conhecidas

Todas as chaves abaixo são persistidas em `preferences(key, value, updated_at)`, como strings, e são definidas em `core/settings.py` e espelhadas em `ReiAnixSettingsContract`. Os valores padrão só são usados quando a chave não tem valor salvo; não são gravados automaticamente como substituição dos valores personalizados.

| Grupo | Chaves | Padrões |
|---|---|---|
| Geral | `app.confirm_destructive` | `true` |
| Aparência | `appearance.theme`, `appearance.card_size`, `appearance.show_thumbnails` | `dark`, `medium`, `true` |
| Biblioteca | `library.sort_default`, `library.grid_density`, `library.page_size`, `library.continue_watching`, `library.continue_watching_limit` | `added_desc`, `medium`, `36`, `true`, `10` |
| Player | `player.autoplay_next`, `player.resume`, `player.default_speed`, `player.aspect_ratio`, `player.zoom_enabled`, `player.immersive`, `player.rotation`, `player.pip`, `player.lock_mode`, `player.auto_hide_seconds`, `player.double_tap_seek_seconds`, `player.long_press_speed`, `player.max_video_resolution`, `player.max_video_frame_rate`, `player.max_audio_channels` | `true`, `true`, `1.0`, `fit`, `false`, `always`, `auto`, `true`, `false`, `5`, `10`, `2.0`, `auto`, `0`, `0` |
| Gestos | `gestures.volume`, `gestures.brightness`, `gestures.double_tap`, `gestures.long_press` | todos `false` |
| Áudio e legendas | `audio.preferred_language`, `audio.preferred_subtitle_language`, `audio.subtitles`, `audio.subtitle_scale`, `audio.subtitle_bottom_padding`, `audio.subtitle_embedded_style` | `""`, `""`, `auto`, `1.0`, `8`, `true` |
| Metadados | `metadata.anilist_enabled`, `metadata.auto_match` | ambos `true` |
| Artwork | `artwork.enabled`, `artwork.cache_limit_mb` | `true`, `128` |

As opções numéricas e enumeradas mantêm as escolhas permitidas definidas no contrato, em vez de aceitar valores arbitrários. Os aliases booleanos antigos são normalizados para `true`/`false`; os formatos válidos existentes são mantidos. Chaves legadas desconhecidas não são apagadas.

## Fluxo de gravação

1. Compose envia a alteração ao ViewModel.
2. `ReiAnixSettingsRepository` valida a chave e o valor por `ReiAnixSettingsContract`.
3. O repositório serializa gravações da mesma chave e chama `ReiAnixSqliteSettingsRepository.setPreference`.
4. O repositório SQL grava em transação e retorna a linha persistida.
5. Só então o `StateFlow` é atualizado e a operação passa a `SUCCESS`. Se houver erro, o valor confirmado anterior é mantido e a operação passa a `ERROR`.
6. Após o commit nativo, o evento de mailbox inclui `nativePersisted=true`. O backend Python invalida o cache de leitura e consulta o valor já gravado para atualizar os consumidores legados; nesse caminho não chama `settings.set` e não grava a preferência novamente.
7. As ações de restauração gravam seus padrões em uma transação única e emitem `compose_settings_bulk_applied`; o backend atualiza os consumidores e publica um snapshot sem emitir resultados de comando que possam sobrescrever o sucesso de persistência nativa.
8. A Activity do player usa o mesmo contrato e repositório SQLite ao alterar preferências durante a reprodução; a notificação Python acontece somente após a confirmação nativa.

A mailbox permanece necessária para atualizar serviços legados e para ações que não são uma simples gravação de preferência. Ela não é mais o mecanismo primário de persistência para as alterações realizadas pela tela Compose ou pelos controles de configuração do player.

## Aplicação por subsistema e dependências restantes

- **Compose/tema:** o tema é projetado pelo ViewModel a partir do estado nativo. O shell continua usando o tema escuro e os tokens existentes.
- **Player Media3/ExoPlayer:** as alterações iniciadas nos controles nativos do player são gravadas diretamente no SQLite. Os valores enviados na inicialização do player ainda são preparados pelo fluxo híbrido de handoff existente; essa ponte deve ser removida quando o contrato de inicialização do player deixar de depender do runtime antigo.
- **Biblioteca e preferências de apresentação:** a mudança é notificada ao runtime legado para que `library.configure_settings` e telas Flet ainda existentes possam reagir. Enquanto esse consumidor permanecer em Python, a aplicação do efeito na biblioteca não é totalmente independente de Python.
- **AniList e Artwork:** as preferências são persistidas nativamente. A aplicação imediata a qualquer consumidor Python remanescente continua usando a notificação de runtime; não há duplicação de cliente HTTP ou de armazenamento.
- **Armazenamento/SAF na tela Compose:** `select_saf`, `request_media_access`, `check_storage_access` e `open_broad_storage_settings` agora chamam o `MainActivity` nativo com `requestId` correlacionado e recebem resultado terminal por `command-results`; não passam pelo dispatcher `compose_settings_action` do Python. A seleção SAF usa o mesmo `ACTION_OPEN_DOCUMENT_TREE`, valida a árvore, persiste a concessão e registra o diretório em SQLite v29 por `sqliteLibraryRepository`; MediaStore e Broad Storage reutilizam os launchers e scanners Android existentes. Os eventos nativos de mailbox continuam atualizando os consumidores Flet legados, com a marcação `nativeFolderConfigOwner` impedindo que Python repita o registro da pasta.
- **Limpeza do cache de artwork/AniList:** o botão Compose executa `ReiAnixArtworkService.clearManagedCache()` diretamente no Kotlin e no SQLite canônico. A operação expira `metadata_updated_at`, remove apenas registros externos não manuais, limpa ponteiros de capa e só exclui arquivos dentro dos diretórios de cache quando não há referência local/manual persistida. Uma notificação pós-commit pede atualização da projeção Python, mas o worker recebe `nativeHandled=true` e não repete a limpeza. O serviço Python continua apenas para a tela Flet legada, que ainda é consumidora legítima.
- **Importação/exportação de configurações no Compose:** executadas por Kotlin com seletores de arquivo Android e `ReiAnixSettingsFileService`. A importação valida o arquivo inteiro antes de uma transação única no SQLite canônico; a notificação para consumidores legados ocorre após o commit. O codec e os handlers de `core/settings.py` continuam necessários para a tela Flet legada, mas não são usados pelo caminho Compose atual.
- **Backup/restauração/integridade/reconciliação no Compose:** roteadas a `ReiAnixBackupService` e `MainActivity`, usando somente o banco canônico. O ZIP `rei-flix-backup-v1` mantém manifest e SHA-256, exclui conta/autenticação e `scan_runs`, preserva preferências exportáveis e capas manuais portáveis e valida membros, tamanhos, traversal e SQLite v29. A restauração normal aplica as tabelas por transação. Para banco corrompido/incompatível, a inicialização nativa direciona à rota Compose de recuperação; `restoreRecoveryBackup` valida o backup, preserva snapshot bruto do banco/sidecars, promove o candidato e valida de novo integridade, foreign keys e contrato nativo. A reconciliação nativa limita-se a caminhos locais comuns; URIs `content://`/SAF/MediaStore ficam sob os scanners.
- **Exportação de diagnóstico no Compose:** usa `ReiAnixDiagnosticsService` e o seletor nativo `CreateDocument`. Além do resumo privacy-first de versão/plataforma, integridade SQLite, contagens da biblioteca, grants persistidos e uso de cache, o relatório agora inclui os até 100 eventos recentes da timeline Kotlin e o resumo agregado de performance Android. A timeline retém até 300 eventos somente em memória e não exporta títulos, caminhos de vídeos, URIs de documentos, valores da conta ou tokens. `core/diagnostic_service.py`, `core/diagnostics.py` e `core/performance.py` continuam preservados para consumidores Flet e caminhos de runtime ainda híbridos; esta etapa não declara paridade completa de cada diagnóstico legado nem remove Python/Flet globalmente.
- **Conta Compose:** login, troca e saída agora entram pelo host Android e pelo Credential Manager. O perfil reduzido (`id`, `name`, `email`, `picture`) é validado e gravado na tabela canônica `account` por `ReiAnixSqliteAccountRepository`; token ID e credenciais não são persistidos nem enviados pela mailbox. Logout só limpa o perfil local após `clearCredentialState` retornar sucesso; troca encerra a credencial, limpa o perfil e só salva a nova conta após validação bem-sucedida. Um evento mailbox sem dados do perfil pede ao runtime Python apenas uma nova projeção a partir de SQLite. O ID público OAuth Web é injetado como recurso Android pelo hook de build. O fluxo de conta da tela Flet ainda é mantido porque a própria tela legada e o bridge Python continuam sendo consumidores legítimos até a migração de UI/remoção final.

A ponte Python `core/compose_settings_bridge.py`, o snapshot e o processamento de ações em `main.py` continuam para superfícies híbridas/legadas ainda ativas. No Android, as ações Compose de backup/restauração/integridade/reconciliação são tratadas pelo Kotlin e o branch de startup Recovery não chama o writer Python de recuperação. `core/backup.py`, `core/recovery.py` e `views/recovery_view.py` permanecem para consumidores Flet legados e o fallback desktop; não são removidos globalmente enquanto esses consumidores existirem. `core/settings.py` e os handlers Python de importação/exportação/limpeza de artwork também continuam necessários para as telas Flet remanescentes.

## Estado do Prompt 08 — extensão nativa em validação

O fluxo Compose de backup, restauração, verificação de integridade, reconciliação local e exportação de diagnóstico foi redirecionado para Kotlin. Os callbacks usam `requestId` e publicam um resultado terminal em `command-results`; a restauração emite somente uma notificação pós-commit para atualizar projeções Python. Essa notificação não executa um segundo restore nem repete gravações no SQLite.

O formato `rei-flix-backup-v1` é mantido para interoperabilidade. O serviço Kotlin valida manifest e checksums e recusa versões/schema desconhecidos. A validação de `settings.schema_version` permanece separada das chaves de preferências visíveis. O caminho de recuperação para SQLite corrompido é nativo e está descrito em `docs/KOTLIN_BACKUP_RECOVERY_MIGRATION.md`. O workflow obrigatório precisa certificar a compilação desta revisão; a cobertura de contrato estática não substitui uma prova runtime de interoperabilidade Python/Kotlin.

`core/backup.py`, `core/recovery.py` e `views/recovery_view.py` ainda existem para o fallback desktop e consumidores Flet legítimos. Em Android, o startup de recuperação solicita a rota Compose nativa e não executa os callbacks Python de snapshot/restauração; a operação crítica usa `restoreRecoveryBackup`. `views/settings_view.py` e os handlers Python de backup permanecem para a tela Flet legada, mas não são o owner do caminho Compose. A exportação Kotlin de diagnóstico continua sendo um resumo redigido, não substitui a timeline forense completa.

## Testes e validação

Os testes de contrato existentes em `tests/test_compose_settings_preferences.py` foram atualizados para verificar que a gravação Compose usa o repositório SQLite nativo, envia `nativePersisted` e que o backend distingue o caminho pós-commit do caminho de compatibilidade. Os testes Kotlin/JVM existentes permanecem para execução manual, conforme a política do projeto; não foram adicionados testes instrumentados ou de emulador.

A validação final de build, regressão e artefatos deve ser lida do workflow GitHub Actions associado ao último commit relevante. Esta documentação não presume que testes Kotlin/JVM foram executados nem que APK/ZIP foram produzidos sem evidência do workflow.

## Prompt 12/18 — verificação das configurações, armazenamento e diálogos Compose

**Base:** `main` no commit `e83fb5320187e6f1376b430cb506e46d53d9ec48`; implementação em `migration/prompt-12-compose-settings-20261020`. Esta seção complementa o inventário anterior, sem criar uma segunda documentação ou alterar contratos legados.

### Componentes já nativos e reutilizados

- `ui/settings/ReiAnixSettings.kt` fornece a tela Compose e as 16 categorias já definidas no projeto. Os controles usam o `ReiAnixSettingsViewModel`, o estado `StateFlow` coletado com `collectAsStateWithLifecycle`, e as chaves/validações de `ReiAnixSettingsContract`.
- `ReiAnixComposeLibraryHost` já integra `ReiAnixSettingsRoute` e `ReiAnixStorageRoute`; nenhuma nova rota paralela foi criada.
- `ReiAnixSettingsRepository` grava em `ReiAnixSqliteSettingsRepository`, na tabela canônica `preferences` da base existente. O schema SQLite não foi alterado e nenhuma base adicional foi criada.
- A tela de armazenamento existente exibe fontes SAF, estados de acesso, registros persistidos e falhas; a seleção continua usando o fluxo Android/SAF e os grants persistentes existentes.
- Os diálogos existentes incluem confirmação de ações destrutivas, escolha de opções e confirmação de remoção de fonte SAF. Cancelar fecha o diálogo sem solicitar a operação correspondente.
- O design system já contém os tokens de referência: fundo `#050505`, superfície `#0B0B0B`, variante `#111111`, elevada `#171717`, diálogo `#141414` e destaque `#3D8BFF`. Nenhum sistema de cores adicional foi introduzido.

### Correção feita neste prompt

`ReiAnixSettingsRepository.setSetting` agora ignora uma chamada se o valor normalizado já for o valor efetivo atual (preferência persistida ou padrão definido no contrato) com estado pronto, ou se o mesmo valor já estiver pendente para essa chave. Isso evita gravação SQLite e notificação de compatibilidade desnecessárias. Uma edição pendente com valor diferente não é ignorada, preservando a possibilidade de o usuário reverter uma alteração ainda não confirmada. A validação do contrato, a gravação canônica, a confirmação após commit e a notificação pós-commit continuam no caminho existente.

### Dependências legadas preservadas

`core/settings.py`, `core/compose_settings_bridge.py`, `views/settings_view.py` e seus consumidores de runtime continuam preservados porque o projeto ainda contém caminhos híbridos Python/Flet. A ponte/mailbox segue sendo notificação de compatibilidade e não uma segunda fonte de persistência. O fluxo de armazenamento continua dependente das APIs Android em `MainActivity`; este prompt não removeu Activity, bridge, Flet ou Flutter.

### Validação deste registro

- Inspeção estática dos componentes, rotas, contratos, tokens, serviços de armazenamento e testes existentes.
- Nenhum teste Kotlin/JVM foi executado manualmente; nenhum teste instrumentado, emulador, novo workflow ou infraestrutura de teste foi executado/criado.
- A correção não altera chaves, tipos, defaults, schema SQLite, rotas ou o contrato do player.
- O estado de compilação/CI deve ser lido nas verificações oficiais da branch deste prompt; esta documentação não trata inspeção estática como prova de build ou funcionamento em dispositivo.

