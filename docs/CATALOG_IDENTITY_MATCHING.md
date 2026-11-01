# Identidade de catálogo externo — Prompt 3/10

## Estado e responsabilidades existentes

O fluxo ativo é nativo em Kotlin e continua usando os componentes existentes:

- `ReiAnixCatalogIdentityMatcher`: valida identidade de um resultado de provider e de um crosswalk.
- `ReiAnixExternalCatalogIntegrationService`: só persiste IDs/metadados e encaminha artwork depois de receber uma correspondência confirmada.
- `ReiAnixAniMapCatalogIdResolver`: resolvedor somente de identificadores; não é provider de metadados ou imagens.
- `ReiAnixExternalCatalogIds`: IDs externos com merge que rejeita valores conflitantes.
- `ReiAnixSqliteLibraryRepository`: persiste IDs suplementares em chaves namespaced da tabela `preferences` já existente, com revalidação da impressão digital da identidade na mesma transação.

Os thresholds `0.48`, `0.86` e `0.05` pertencem ao `ReiAnixAniListMatcher`, usado para classificar candidatos da integração principal do AniList. Eles não estavam no `ReiAnixCatalogIdentityMatcher` e não foram copiados nem alterados nesta etapa. O matcher de catálogo suplementar não usa score fuzzy para autorizar identidade.

## Política de decisão

A comparação que autoriza persistência permanece exata depois da normalização. Não há aceitação por similaridade parcial, prefixo, substring ou distância de edição.

1. O título principal ou um título alternativo explicitamente fornecido pelo catálogo precisa coincidir exatamente após normalização e ser discriminativo. Abreviações/romanizações só são aceitas quando aparecem como título/alias conhecido em pelo menos um dos lados; não são geradas heurísticas de abreviação.
2. Um campo ausente em um dos lados é desconhecido, não uma divergência. Quando ambos os lados possuem ano, formato, tipo, temporada/número ou episódios, divergências relevantes rejeitam o candidato.
3. Uma busca por título sem ID previamente confirmado exige duas dimensões independentes que concordem. Formato e tipo de mídia contam como uma dimensão porque descrevem essencialmente a mesma classificação. Ano, classificação, temporada/sequência e contagem de episódios são dimensões separadas.
4. Uma consulta feita por um ID já confirmado para aquele provider pode tolerar campos remotos ausentes, mas ainda exige título exato e rejeita qualquer metadado contraditório ou ID conflitante.
5. Resultados apenas prováveis não produzem `ReiAnixCatalogIdentityMatch`, não persistem IDs e não liberam artwork. Se um provável concorrente permanecer junto de um candidato confirmado, o resultado é tratado como ambíguo.
6. Títulos genéricos ou puramente numéricos não são evidência suficiente. Números presentes em títulos, partes e temporadas são preservados pela normalização.

A normalização usa NFKD, remove acentos latinos, uniformiza caixa/pontuação/espaços e mantém marcas combinantes de scripts japoneses. Isso evita colapsar kana distintos, como が e か. Não há transliteração automática entre japonês e romanização: o matcher depende dos títulos alternativos que os próprios catálogos fornecem.

## AniMap e crosswalk

AniMap continua sendo consultado exclusivamente a partir de IDs existentes (AniList, MAL, AniDB, Kitsu numérico e ANN). A resposta agora precisa conter o identificador exato usado na consulta. Um payload `data` com mais de um registro é rejeitado, em vez de selecionar arbitrariamente o primeiro. Os IDs da resposta são mesclados com os IDs locais usando a operação de conflito já existente e a integração ainda revalida título e todos os metadados que estiverem disponíveis antes de persistir.

AniMap informa ano, tipo, temporada-calendário, episódios, título e sinônimos para provar identidade. O campo `animeSeason.season` é uma estação do calendário e não é interpretado como número de temporada/sequência.

## Fribb Anime Lists — avaliação, não integração

Fonte examinada: https://github.com/Fribb/anime-lists (branch `master`). O README descreve dados JSON estáticos gerados a partir do Anime Offline Database e do projeto Anime-Lists; os dados completos consolidam IDs por AniDB e incluem, entre outros, MAL, AniList, Kitsu, AniDB e ANN.

Na consulta de 1 de novembro de 2026, a API de metadados do GitHub retornou `license: null` e a árvore do repositório não continha arquivo de licença. Os dois arquivos principais consultados tinham aproximadamente 7,5 MB (`anime-list-full.json`) e 6,3 MB (`anime-offline-database-reduced.json`). O README descreve listas/arquivos e atualizações geradas, não um endpoint operacional de consulta individual.

Decisão: não incorporar os JSONs, não adicionar dependência/download periódico e não tratar o projeto como uma API. Sem licença explícita verificável e sem contrato operacional estável, o custo e o risco de distribuição/atualização não se justificam. O crosswalk AniMap existente é mantido e reforçado; uma futura reavaliação do Fribb exige esclarecimento formal da licença e estratégia explícita de atualização.

## Persistência e pending_matches

Os IDs de catálogo suplementares usam a tabela `preferences` v29 com prefixo próprio, sem DDL. O write relê a identidade local dentro da transação, confere sua impressão digital e combina IDs por `mergeVerified`; IDs conflitantes não substituem valores já confirmados.

A tabela `associations` continua pertencendo à associação canônica AniList (`lookup_title` → `anilist_id`). A tabela `pending_matches` contém candidatos de associação AniList aguardando escolha manual. Ela não é uma fila genérica de crosswalk Kitsu/MAL/AniDB; gravar IDs suplementares nela mudaria seu contrato. Por isso, matches suplementares prováveis/ambíguos são rejeitados para persistência e registrados no diagnóstico existente. Nenhuma linha de anime/episódio é removida ou reconstruída.

## Proteção do artwork e limites

O fluxo só chama `replaceExternalArtworkCandidate` após obter uma correspondência confirmada e salvar os IDs. AniMap não pode gerar candidatos de imagem. A semântica Poster/Backdrop/Banner e o serviço de artwork não são alterados nesta etapa.

Não houve alteração de providers, retry, downloader, biblioteca/Home/Details, Compose, thumbnails, estrutura SQLite v29 ou schema.
