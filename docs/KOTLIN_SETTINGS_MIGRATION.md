# Migração das configurações para Kotlin nativo

## Estado e escopo

Esta documentação descreve a migração de Settings implementada no Prompt 08/29. O banco canônico continua sendo `filesDir/data/library.sqlite3`, tabela `preferences`, schema v29. Os valores continuam armazenados como texto para compatibilidade com `core/settings.py`; não foi criado banco, arquivo JSON permanente nem repositório concorrente para preferências.

A tela de configurações continua em Jetpack Compose (`ui/settings/ReiAnixSettings.kt`) e observa `ReiAnixSettingsViewModel` / `ReiAnixSettingsRepository` por `StateFlow`. O snapshot `data/reianix-compose/settings.json` é somente uma projeção de estado de conta, armazenamento e capacidades; não é a fonte de verdade das preferências.

## Implementação Kotlin

- `data/settings/ReiAnixSettingsContract.kt`: chaves, tipos, valores padrão e validação compatíveis com o contrato legado.
- `data/sqlite/ReiAnixSqliteSettingsRepository.kt`: leitura, escrita e remoção explícita na tabela canônica `preferences`, com transação e confirmação pela leitura da linha persistida.
- `data/settings/ReiAnixSettingsRepository.kt`: lê primeiro o SQLite canônico; grava diretamente nele; atualiza o estado Compose somente depois da confirmação da gravação; em falha mantém/restaura o valor confirmado. As gravações da mesma chave são serializadas e uma solicitação antiga que ainda não começou a escrever é cancelada quando já existe uma solicitação mais recente.
- `viewmodel/ReiAnixSettingsViewModel.kt`: expõe o estado e projeções reativas.
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
- **Armazenamento/SAF:** permissões Android e URIs autorizadas continuam sendo estado do sistema de armazenamento, não simples preferências. A seleção SAF, permissões, varredura e verificação de acesso preservam os fluxos nativos existentes.
- **Limpeza do cache de artwork/AniList:** o botão Compose executa `ReiAnixArtworkService.clearManagedCache()` diretamente no Kotlin e no SQLite canônico. A operação expira `metadata_updated_at`, remove apenas registros externos não manuais, limpa ponteiros de capa e só exclui arquivos dentro dos diretórios de cache quando não há referência local/manual persistida. Uma notificação pós-commit pede atualização da projeção Python, mas o worker recebe `nativeHandled=true` e não repete a limpeza. O serviço Python continua apenas para a tela Flet legada, que ainda é consumidora legítima.
- **Importação/exportação de configurações, backup, verificação/reconciliação de backup e diagnóstico:** permanecem encaminhados ao backend Flet/Python nesta etapa; essas operações envolvem contratos de arquivos, validação e integridade e ainda não foram substituídas por implementações Kotlin equivalentes. Não são declaradas nativas até que as operações e seus consumidores sejam migrados.
- **Conta:** operações de login/logout continuam no fluxo de conta existente.

A ponte Python `core/compose_settings_bridge.py`, o snapshot e o processamento de ações em `main.py` foram preservados porque ainda servem operações de conta, armazenamento, backup, diagnóstico e consumidores de runtime legados. Não foram removidos arquivos mistos que ainda têm consumidores legítimos. A compatibilidade `settings.set` no worker permanece apenas para hosts antigos que não enviam `nativePersisted=true`; para o host atualizado, o ramo nativo só invalida cache, lê o valor já persistido e aplica o runtime.

## Testes e validação

Os testes de contrato existentes em `tests/test_compose_settings_preferences.py` foram atualizados para verificar que a gravação Compose usa o repositório SQLite nativo, envia `nativePersisted` e que o backend distingue o caminho pós-commit do caminho de compatibilidade. Os testes Kotlin/JVM existentes permanecem para execução manual, conforme a política do projeto; não foram adicionados testes instrumentados ou de emulador.

A validação final de build, regressão e artefatos deve ser lida do workflow GitHub Actions associado ao último commit relevante. Esta documentação não presume que testes Kotlin/JVM foram executados nem que APK/ZIP foram produzidos sem evidência do workflow.
