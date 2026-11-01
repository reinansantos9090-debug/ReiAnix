# Auditoria de confiabilidade dos providers externos — Prompt 4/10

## Escopo e estado de referência

Auditoria da branch `main` do repositório oficial `reinansantos9090-debug/ReiAnix`. O contrato interno existente é `ReiAnixAnimeCatalogProvider`, `ReiAnixCatalogAnime`, `ReiAnixExternalCatalogIds`, `ReiAnixCatalogArtworkCandidate` e `ReiAnixCatalogResult`. Não foi criado um segundo sistema de providers.

A integração suplementar continua usando o registro único em `ReiAnixAppDependencies`, o coordenador compartilhado, os mesmos serviços SQLite e a tabela `artwork` do schema v29. O composition root mantém somente Kitsu registrado enquanto não estiverem confirmadas as condições de uso do Tenrai e a autorização escrita prévia do AniPub; os adapters e testes JVM das duas fontes permanecem no projeto.

## Matriz de decisão

| Fonte | Papel no ReiAnix | Metadados e IDs | Artwork aceito | Decisão |
|---|---|---|---|---|
| AniList | Fonte primária já existente | AniList ID, títulos, sinônimos, ano/sazonalidade, formato, episódios, duração, status, score e estúdios | `coverImage` → Poster; `bannerImage` → Banner | Manter |
| Kitsu | Complemento por REST | Kitsu ID, títulos alternativos, ano, subtype/formato, episódios, duração, status e score | `posterImage` → Poster; `coverImage` → Banner | Manter |
| AniPub | Complemento por REST | ID AniPub; título, sinônimos, ano, episódios, duração, status e score. A documentação consultada não define tipo/formato nem classificação etária; esses campos permanecem nulos | `ImagePath` → Poster; `Cover` → Banner | Manter com isolamento |
| Tenrai | Fonte suplementar para IDs MAL | MAL ID preservado em `myAnimeListId`, títulos, sinônimos, ano, tipo, episódios, duração, status, score e rating | Variantes de `images.webp`/`images.jpg` → Poster | Aprovado como substituto operacional complementar, com ressalvas beta |
| Jikan público | Nenhum papel ativo | IDs MAL históricos continuam preservados | Candidatos antigos já persistidos não são apagados | Desativado/removido do runtime |

Nenhuma destas quatro integrações promove imagem a Backdrop. O tipo Backdrop continua independente no contrato e permanece disponível para fontes futuras cuja documentação declare esse tipo expressamente. Proporção não altera semântica.

## AniList — GraphQL

Endpoint de produção: `POST https://graphql.anilist.co`. O cliente envia query e variables no mesmo corpo JSON. A operação de busca e a consulta por ID usam o conjunto compartilhado de campos `MEDIA_FIELDS`; as variantes de lote existentes também são mantidas, sem uma consulta separada redundante para artwork.

Campos preservados:
- ID AniList e ID MAL opcional `idMal`, preservado em `myAnimeListId` quando presente;
- `title.romaji`, `title.english`, `title.native` e `synonyms`;
- `season`, `seasonYear`, `format`, `episodes`, `duration`, `status`, `averageScore`, `description`, `genres` e estúdios;
- `coverImage.large/extraLarge` e `bannerImage`.

O adapter mantém `coverImage` como Poster e `bannerImage` como Banner. Nenhum deles é gravado como Backdrop. A limitação de requisições continua centralizada em `AniListGraphqlRequestController`, incluindo HTTP 429, `Retry-After` e os cabeçalhos `X-RateLimit`.

Respostas GraphQL parciais agora mantêm os registros que puderam ser decodificados, mas carregam `incomplete=true`. O adapter do contrato compartilhado rejeita esse conjunto incompleto para matching/persistência automática: dados úteis não são descartados no cliente, mas uma busca parcial não se transforma em evidência completa de identidade. Erros GraphQL sem `data` continuam sendo falhas identificáveis.

## Kitsu — REST JSON:API

Base: `https://kitsu.io/api/edge`.

Rotas existentes:
- busca: `GET /anime?filter[text]=...&page[limit]=...`;
- detalhe: `GET /anime/{id}`.

O adapter limita a busca a uma página bounded, usa as propriedades JSON:API e mantém os títulos alternativos e os campos opcionais nulos quando ausentes. Não há uso de OAuth ou endpoints de escrita. O contrato mapeia `posterImage` para Poster e `coverImage` para Banner, de acordo com o schema REST e a implementação GraphQL do Kitsu (`banner_image` resolvido por `cover_image`). A semântica não vem da proporção.

Falhas HTTP, JSON inválido e HTTP 429 são convertidos em resultados do contrato. O coordenador compartilhado preserva isolamento, cache de sucessos e `Retry-After`.

## AniPub — REST

Base: `https://anipub.org`.

Rotas usadas:
- busca: `GET /api/search/{name}`;
- metadados: `GET /api/info/{id}`.

O adapter deixou de depender de `/anime/api/details/{id}` e do objeto opcional `jikan` que esse payload poderia incluir. A consulta de metadados agora usa diretamente o endpoint de informação pública. Isso evita que uma falha ou descontinuação de Jikan interrompa AniPub.

Campos efetivamente mapeados quando presentes no JSON de informação: `_id` (referência externa AniPub), `Name`, `Synonyms`, `Aired`, `Premiered`, `epCount`, `Duration`, `MALScore`, `Status`, `DescripTion`/`Description`, `ImagePath` e `Cover`. A documentação consultada não declara um campo estável de formato/tipo de mídia nem de classificação etária; por isso, mesmo que respostas não documentadas tragam chaves como `Type`, `Format`, `Rating` ou `AgeRating`, o adapter as ignora e deixa os campos internos correspondentes nulos. Um MAL ID só é preservado quando a API de informação o fornece expressamente; não é inferido de nome, slug ou ID interno AniPub.

O contrato mantém `ImagePath` como Poster e `Cover` como Banner, conforme a tabela de campos do README do cliente mantido por `AnimePub/AniPub-API`. O cliente documenta URLs absolutas; por isso o adapter preserva URLs HTTPS absolutas e rejeita HTTP, caminhos relativos, URL protocol-relative, userinfo e segmentos `.`/`..`, sem fabricar URLs por concatenação. O download e a validação final dos bytes permanecem no pipeline já existente e fora do escopo deste prompt.

**Condição de uso:** os termos do AniPub exigem consentimento de responsável legal para menores e proíbem acesso automatizado sem autorização escrita prévia. Como a autorização escrita não foi verificada nesta etapa, o provider não é registrado para chamadas automáticas no composition root; o adapter e seus testes JVM permanecem preservados até que ela seja obtida.

**Evidência/limitação:** a referência pública do AniPub declara API REST gratuita, sem chave, e descreve `/api/info/:id` e `/api/search/:name`. O README mantido pela organização AnimePub esclarece `ImagePath` como Poster e `Cover` como Banner, ambos URLs absolutas. A página documental foi indexada, mas a consulta direta à origem retornou 403 no ambiente de verificação; isso não prova indisponibilidade. A disponibilidade operacional por chamada ao vivo não é declarada como comprovada. A documentação não publica política numérica de rate limit; não inventamos um valor.

## Jikan e substituição por Tenrai

O endpoint público Jikan foi descontinuado em 1º de outubro de 2026 pelo mantenedor do projeto. Assim, `https://api.jikan.moe/v4` deixa de ser fonte ativa do ReiAnix. O adapter Jikan legado não faz mais parte do registro de providers.

Tenrai foi selecionado por oferecer documentação pública, API REST GET, acesso sem autenticação para o catálogo público de MyAnimeList e endpoints de anime com schema declarado compatível com Jikan v4. O adapter implementado usa:
- `GET https://api.tenrai.org/v1/anime?q={query}&limit={limit}&sfw=true`;
- `GET https://api.tenrai.org/v1/anime/{malId}`.

O adapter preserva o MAL ID no campo interno histórico `myAnimeListId`, com `ReiAnixCatalogSource.TENRAI` como origem ativa. Assim, não há migração ou regravação de IDs existentes. A compatibilidade de formato permite reutilizar os campos de título/aliases, `mal_id`, `aired`, `type`, `episodes`, `duration`, `score`, `synopsis`, `rating` e `images.webp/jpg`. Somente variantes de imagem semanticamente equivalentes ao poster do catálogo são emitidas como Poster; Banner e Backdrop não são inventados.

A documentação do Tenrai declara, para acesso público sem chave, 120 requisições por minuto e 4 por segundo, com HTTP 429 e `Retry-After`. O cliente do ReiAnix mantém limite conservador inferior, 60 por minuto e 3 por segundo, para deixar margem sob uso compartilhado por IP. Não é introduzida uma chave privada no app; a documentação recomenda explicitamente que Server Keys não sejam expostas em clientes móveis.

**Ressalvas de aprovação:** Tenrai está em beta, não pode ser auto-hospedado no estado documental consultado, mantém respostas públicas em cache por algumas horas até o máximo publicado de 12 horas e informa que não garante precisão, completude ou disponibilidade. O painel público estava Operational durante a auditoria, mas não houve resposta HTTP direta bem-sucedida do endpoint API neste ambiente. É um fallback técnico complementar documentado, não uma fonte com SLA. Os termos exigem consentimento e supervisão de responsável legal para usuários abaixo da maioridade; como essa condição não foi verificada nesta etapa, Tenrai permanece fora do composition root padrão até que o consentimento aplicável seja assegurado.

Os IDs MAL existentes continuam disponíveis em `myAnimeListId`. O valor legado `jikan` permanece reconhecível somente para compatibilidade de referências antigas; não é registrado como provider ativo e não aceita novos candidatos de metadados/artwork.

## Outras fontes

Não foi adicionada outra fonte. AniMap continua como resolvedor de identificadores (crosswalk) apenas, conforme a arquitetura já existente; não é usado como origem de artwork. Não há lacuna concreta comprovada que justifique outra API nesta etapa.

## Contrato compartilhado e resiliência

Todos os adapters continuam produzindo `ReiAnixCatalogAnime` e `ReiAnixCatalogResult`, preservando:
- provider de origem e ID externo;
- referência do registro externo;
- título e títulos alternativos;
- ano, formato, tipo de mídia, episódios, duração, status e score quando disponíveis;
- URL original, nome do campo de origem e variante/resolução;
- tipo canônico Poster/Backdrop/Banner apenas quando semanticamente documentado.

Os adapters públicos usam o transporte GET HTTPS bounded já existente, resultados tipados para falhas HTTP/rede/timeout/JSON, limites de resposta, timeout e coordenador compartilhado. Falha de um provider permanece um resultado local daquela fonte e não cancela as outras fontes. AniPub não depende de Jikan. Respostas incompletas ou sem dados suficientes permanecem ausentes/erro; não se fabricam IDs, URLs ou imagens.

## Integridade e limites desta etapa

- SQLite schema v29, tabela `preferences` e tabela `artwork` permanecem sem migração/DDL.
- IDs MAL antigos, referências de artwork e arquivos de imagem locais não são apagados nem reescritos.
- O matcher e o crosswalk não foram reestruturados; apenas o provider usado para metadados MAL foi trocado, mantendo o contrato de IDs existente.
- Não foram alterados scanner, UI Compose, fila da biblioteca, limite de registros por inicialização, política de retry, downloader HTTP, cache persistente, recuperação em massa, Home/Details ou thumbnails de episódios.
- Não foram criados workflows automáticos adicionais. Os workflows manuais permanecem manuais e os cenários de rede ao vivo não são considerados aprovados apenas pela presença de fixtures.

## Referências documentais

- AniList GraphQL: https://docs.anilist.co/guide/graphql/
- AniList Media reference: https://docs.anilist.co/reference/object/media
- AniList rate limiting: https://docs.anilist.co/guide/rate-limiting/
- Kitsu API docs: https://github.com/hummingbird-me/api-docs
- Kitsu Media schema: https://github.com/hummingbird-me/api-docs/blob/c95e259bec851f4bddf13c7bce3dd2f08eba7d19/api/schemas/resources/media.yml
- AniPub API reference: https://api.anipub.org/
- AniPub maintained API client (documents `ImagePath` as Poster and `Cover` as Banner): https://github.com/AnimePub/AniPub-API
- AniPub Terms (guardian consent and prior written permission for automated access): https://anipub.org/terms
- Tenrai API documentation: https://api.tenrai.org/documentation
- Tenrai Terms (guardian consent and supervision for underage users): https://tenrai.org/terms
- Tenrai FAQ: https://api.tenrai.org/faq
- Tenrai changelog: https://api.tenrai.org/changelog
- Jikan public API discontinuation: https://github.com/jikan-me/jikan-rest/issues/613
