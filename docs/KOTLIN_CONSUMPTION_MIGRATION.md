# Migração Kotlin de histórico e progresso — Prompt 09/29

## Fonte de verdade e compatibilidade

O histórico e o progresso continuam na base SQLite canônica `filesDir/data/library.sqlite3`, schema v29. A implementação não cria tabelas, bases, identificadores ou estado persistente paralelo. As colunas existentes `episodes.progress`, `episodes.duration`, `episodes.watched` e `episodes.last_played_at` permanecem a fonte de verdade. O serviço usa milissegundos na fronteira com Media3 e mantém segundos no armazenamento legado.

## Ciclo de reprodução e retomada

`NativePlayerActivity` continua proprietária da reprodução Media3/ExoPlayer e captura a identidade do episódio persistido, URI, `playerSessionId`, `playerGeneration`, `transitionGeneration`, `requestId` e timestamp do evento. Quando não existe uma posição explícita no intent nem estado salvo da Activity, a preparação consulta `ReiAnixConsumptionService.getProgress` no SQLite canônico. A posição recuperada é validada como não negativa e limitada à duração positiva conhecida. Quando a duração é desconhecida, não é inventada nem usada para calcular um percentual; um progresso válido é preservado.

As atualizações observadas pela UI permanecem separadas da persistência. O relógio do player continua atualizando a interface a cada 250 ms, enquanto os checkpoints periódicos persistidos permanecem em 15 segundos, além de pausa, busca, erro, conclusão e outras transições já tratadas pelo player. O SQLite não é gravado a cada tick visual e as operações de banco não bloqueiam a thread principal.

## Sessões e escritas concorrentes

Os checkpoints capturam os dados do player na hora do evento e são serializados pelo executor de progresso dedicado. O repositório atualiza a linha de episódio em transação e rejeita timestamps recebidos iguais ou anteriores ao `last_played_at` já persistido. Assim, uma escrita atrasada não faz regredir uma posição mais recente. O identificador canônico do episódio continua sendo a chave de gravação; os campos de sessão/geração preservam correlação e diagnóstico. Não foi criado um histórico de sessões separado porque o schema existente já mantém a última reprodução por episódio.

O evento terminal `player_exited` também é enfileirado no executor de progresso. Kotlin tenta persistir a posição/duração capturadas antes de publicar o evento no NativeMailbox. O payload recebe `nativeProgressPersisted=true` somente depois da confirmação da gravação Kotlin. Quando falta um ID numérico canônico ou a persistência nativa falha, o payload não recebe essa marca e o fallback legado permanece habilitado. Quando a gravação nativa é bem-sucedida, o Python não repete `LibraryStore.save_progress`.

## Histórico e estado de consumo

`ReiAnixConsumptionService` fornece leitura de progresso, escrita, marcação assistido/não assistido, “Continuar assistindo”, itens assistidos recentemente e consulta do histórico recente. O histórico é uma projeção dos episódios que já possuem `last_played_at`, ordenada pelo repositório canônico por data decrescente e ID para desempate. Nenhuma tabela ou armazenamento paralelo foi introduzido.

A regra de conclusão de 90% é mantida conforme a política atual do produto quando a duração é conhecida, juntamente com a conclusão explícita de Media3. A projeção Kotlin reconhece também linhas antigas que chegaram ao limite de conclusão sem o sinalizador `watched` consistente. Itens ausentes são excluídos de “Continuar assistindo”; não são apagados seus dados ou histórico.

## Atualização de Compose sem ownership Python

Depois de um commit nativo bem-sucedido, `ReiAnixConsumptionService.playbackChanges` emite um evento em memória que inclui episódio, sessão, gerações e timestamp. A Home, Details e “Continuar assistindo” são atualizadas por `ReiAnixLibraryViewModel`, que coleta e coalesce eventos e relê a projeção diretamente do SQLite canônico. Os checkpoints periódicos emitem no máximo uma invalidação a cada intervalo já existente de 15 segundos; a atualização visual de 250 ms não desencadeia consultas globais. Pausa, conclusão e saída também emitem eventos de atualização.

O evento é uma notificação de invalidação em memória, não outra fonte de verdade. A publicação Python para progresso só é solicitada quando a escrita de compatibilidade foi responsável pelo update. A ponte continua processando validação de sessão, eventos de ciclo de vida, diagnósticos e transições legadas; ela não é a proprietária da persistência nativa bem-sucedida nem da atualização Compose nativa.

## Dependências Python restantes

- `main.py`, `core/library_store.py` e `core/consumption.py` ainda possuem consumidores legítimos em fluxos Flet/legados e no caminho de compatibilidade usado quando a gravação nativa não pode ser concluída.
- Para eventos com `nativeProgressPersisted=true`, o handler Python não chama `LibraryStore.save_progress` e não solicita publicação de snapshot por aquele checkpoint nativo.
- O consumo Python não foi removido globalmente porque também é usado em outros fluxos que ainda não foram migrados neste prompt.
- SQLite v29, identidade de episódio e os dados existentes permanecem intactos.

## Testes, validação e limitações

Foram adicionados contratos de regressão à suíte Python existente para verificar estaticamente que a saída é gravada pelo serviço Kotlin antes de publicar `player_exited`, e que os checkpoints nativos invalidam a projeção Compose sem uma publicação Python redundante. O workflow regular continua responsável pela suíte de regressão e pelo build do APK/ZIP conforme sua configuração. Não foram adicionados testes de emulador, não foram criados gatilhos automáticos para testes Kotlin/JVM e testes Kotlin/JVM não foram executados manualmente.

Android não garante callback de saída sob morte abrupta de processo; nesse caso, a retomada usa o último checkpoint realmente persistido. O fluxo não introduz sleep, esperas fixas ou bloqueios da thread principal.