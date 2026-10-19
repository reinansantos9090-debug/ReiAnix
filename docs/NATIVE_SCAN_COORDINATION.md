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

## Correções funcionais do Prompt 06.1/18

- A sonda de estabilidade de `MediaStoreScanner` verifica agora se existem vídeos dentro das mesmas árvores SAF configuradas que a consulta primária utiliza. A existência de vídeos em outras pastas/volumes não deve, isoladamente, adiar uma conclusão vazia válida para o escopo da biblioteca.
- A reutilização de snapshots MediaStore em cache consulta o token cooperativo de cancelamento entre os lotes e enquanto prepara os documentos de um lote. A solicitação de cancelamento propaga `CancellationException`; não é convertida em resultado vazio nem em sucesso.
- `NativeScanRunner.runMediaStoreScan` agora fornece `libraryScopes` derivados de árvores SAF persistidas, com identidade armazenada, grant persistente e acessibilidade da raiz conferidos. Uma lista vazia permanece bloqueada como `NO_CONFIGURED_LIBRARY_SOURCE`; árvores não verificadas não produzem sucesso vazio.
- `NativeScanRunner.runBroadScan` agora fornece `authorizedRoots` apenas para diretórios físicos cuja identidade corresponde a uma árvore SAF persistida do provedor Android de armazenamento externo. Provedores arbitrários não são convertidos de `content://` para filesystem paths.
- Escopos excluídos por grant/identidade não confirmada rebaixam um resultado que teria sido completo para `PARTIAL`. O MediaStore também verifica se cada volume configurado está visível no provider; volume removível ausente resulta em parcial, não em conclusão vazia.
- Em Android 24–28, `MediaStoreScanner` usa `MediaStore.MediaColumns.DATA` apenas para comparar caminhos absolutos canônicos contra o diretório físico validado da árvore SAF, pois `RELATIVE_PATH`/`VOLUME_NAME` não estão disponíveis. Caminhos não verificáveis deixam o resultado parcial; cache legado não é reutilizado e ausência de vídeos permanece `PARTIAL`, já que essas versões não expõem versão/geração confiáveis para provar estabilidade.
- O serviço central e a reconciliação canônica continuam sem mudança; MediaStore e Broad Storage permanecem diagnósticos, não novos writers do catálogo.
- As correções permanecem em `MediaStoreScanner` e `NativeScanRunner`, reutilizando `NativeScanController`, `NativeIndex` e os contratos de batch existentes. Não criam um segundo scanner, coordenador, cache persistente ou writer do catálogo.
- Esta alteração foi revisada estaticamente. Nenhum teste foi adicionado/alterado ou executado manualmente, e o resultado do workflow oficial no SHA final ainda precisa ser verificado antes de declarar o Prompt 06.1 concluído.

