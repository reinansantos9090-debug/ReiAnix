# Coordenação nativa de varreduras

## Fonte de estado

`NativeScanController` é o único coordenador das execuções nativas já iniciadas por `MainActivity`. `NativeScanRunner` continua delegando a enumeração aos scanners SAF, MediaStore e armazenamento amplo; não há enumeração duplicada no coordenador.

O fluxo `NativeScanController.progress` é um `StateFlow` somente de leitura, indexado pelo ID da operação. Os snapshots terminais permanecem disponíveis para diagnóstico após a liberação do token de execução. Consumidores de UI devem coletar esse fluxo e não iniciar varreduras a partir de recomposições.

## Concorrência

A chave da fonte é reservada atomicamente ao iniciar uma operação. Uma segunda solicitação para a mesma chave é recusada até a liberação do token; fontes distintas continuam podendo operar em paralelo, cada uma com seu próprio estado e geração de indexação. O controle não mantém locks durante acesso a arquivos ou operações do provedor.

## Estados e completude

Os estados terminais são `SUCCEEDED`, `PARTIAL`, `CANCELLED` e `FAILED`. Apenas os resultados explicitamente `COMPLETED` ou `EMPTY_COMPLETE`, sem marcação parcial ou cancelamento solicitado, podem ser expostos como completos. Estados ausentes ou desconhecidos falham de forma conservadora. A porcentagem é nula enquanto não existir um total confiável.

`ScanOperationResult` preserva contagens, erros, localizações não processadas, horários e a classificação final para uma etapa futura de indexação. Este contrato não reconcilia nem exclui registros do catálogo.

## Cancelamento e limites de provedores

O cancelamento define um token cooperativo que os scanners consultam durante a enumeração. O runner mantém a publicação do resultado terminal e a liberação do token, em vez de interromper threads à força. Uma chamada síncrona a `ContentResolver` ou a um provedor SAF pode não retornar imediatamente; nesse caso, o cancelamento só é observado quando a chamada termina e o scanner volta a consultar o token. Os resultados obtidos até então permanecem classificados como cancelados/parciais, nunca como completos.

## Integração legada ainda existente

A publicação de lotes e dos eventos terminais ainda usa `NativeMailbox` para o fluxo Python/Flet existente. Este prompt adiciona coordenação e observabilidade nativas, mas não remove essa ponte nem transfere a autoridade do catálogo SQLite; isso exige substituição e validação em etapas posteriores.
