# ReiAnix: auditoria de desempenho da biblioteca (2026-11-23)

Escopo aprovado: aproveitar as técnicas de Mihon, Kotatsu, CloudStream,
Fossify Gallery e Aves, **exceto** a opção 4, de cancelamento de tarefas
obsoletas. Nenhuma alteração deste trabalho cancela carregamentos com base
na posição do usuário na rolagem.

## Inventário das dez técnicas aprovadas

| Técnica | Estado neste trabalho |
| --- | --- |
| 1. Evitar solicitações duplicadas de capas | Novo `ReiAnixArtworkRequestInterceptor` coordena requests HTTP idênticos por chave de cache e tamanho, sem atrasar o primeiro. |
| 2. Evitar recarregar imagens idênticas | Preservados `ImageRequest` memorizado e chaves estáveis; o mesmo loader agora atende Home, Biblioteca e Detalhes. |
| 3. Cache persistente | Preservados o cache canônico `ReiAnixArtworkService` e o disco Coil `image_cache`, com configuração e limites anteriores. |
| 5. Controle da decodificação | O limite Coil/BitmapFactory de quatro operações está configurado explicitamente no único loader, sem redução da concorrência inicial. |
| 6. Atualizações parciais dos cards | Mantidos `LazyVerticalGrid`, `stableKey`, `contentType` e `LibraryCardRenderData` imutável. Não foi substituído por `notifyDataSetChanged` ou RecyclerView. |
| 7. Páginas vizinhas preparadas | O fluxo de Biblioteca é uma única grade Compose paginada, não um ViewPager de categorias. Não foi criado um pager paralelo porque isso adicionaria custo e mudaria a interface. |
| 8. Dimensionar imagens por card | Mantido `BoxWithConstraints`, com dimensões medidas, `Size(Pixels)` e limite por tipo de imagem. |
| 9. Reaproveitamento de componentes | Mantidos a identidade de itens/slot no Compose e callbacks por card; não foram transportados pools de RecyclerView, incompatíveis com Compose. |
| 10. Cache adaptativo ao aparelho | Limite de memória de 15% em aparelhos low-RAM, 20% em outros, agora em único cache de bitmaps, em vez de dois loaders separados. |
| 11. Baseline Profiles | AGP 8.9.1 já pode consumir profiles de dependências. Perfil **específico do ReiAnix não foi gerado**: exige captura real de trajetos num dispositivo, sem inventar regras ou adicionar testes/emuladores. |

## Limites e validação

A velocidade inicial das capas depende de rede, resolução e aparelho. A
deduplicação não faz novas pesquisas, não muda ordem de fontes, nem altera
filtros/correções automáticas. Uma nova PR exige validação visual para afirmar
redução de piscadas ou ganhos reais de FPS.

Para um Baseline Profile próprio, executar **manualmente em um aparelho físico
autorizado** os fluxos de inicialização, Biblioteca, rolagem longa e abrir/voltar
de Detalhes; coletar e converter as regras com o procedimento oficial do Android:
https://developer.android.com/topic/performance/baselineprofiles/manually-create-measure
As regras só devem ser adicionadas após coleta e medição, sem workflow de emulador.
Não executar Kotlin/JVM automaticamente por este planejamento.
