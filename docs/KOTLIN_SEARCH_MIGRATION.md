# Migração nativa da busca — Prompt 11/29

## Estado antes desta alteração

- A tela `ReiAnixSearchRoute` já era Compose e usava `ReiAnixLibraryViewModel`.
- A busca local já consultava o SQLite canônico por `ReiAnixLibraryQueryService.searchAnime` e `ReiAnixSqliteLibraryRepository.searchAnimeIds`. A consulta devolve IDs persistidos, aplica filtros/ordenação em SQLite/Kotlin, normaliza acentos e espaços e distingue vazio de falha.
- A tela local já tinha debounce, cancelamento por `transformLatest`, estado de carregamento/erro/vazio e navegação pelo ID interno.
- `ReiAnixAniListClient.searchAnime`, `ReiAnixAniListIntegrationService.search` e `ReiAnixAniListViewModel.searchAniList` já existiam em Kotlin para a associação manual. A tela Search, porém, não apresentava descoberta remota e era limitada ao catálogo local.
- Os modelos AniList já forneciam títulos alternativos, sinopse, gêneros, ano/temporada, formato, estado, nota, estúdios e URLs de capa.

## Alterações implementadas

- A tela Search oferece fontes separadas: **Biblioteca local** e **Descobrir no AniList**. A troca não mistura resultados nem suas identidades.
- A busca local continua utilizando a consulta SQLite e seus filtros locais. Os filtros de biblioteca ficam ocultos no modo AniList porque não são filtros remotos, e não são simulados contra a API.
- A descoberta remota reutiliza o ViewModel, serviço de integração e cliente AniList já existentes. A entrada reativa usa debounce de 300 ms; a solicitação anterior é cancelada e respostas antigas não podem substituir a consulta atual.
- A UI remota diferencia carregamento, resposta vazia e falha de rede/API, com tentativa explícita de repetição. A identidade de cada resultado é `anilist:<id>`; a capa usa o componente de artwork existente e não cria cache persistente paralelo.
- Foi adicionada uma rota de Details remota somente-leitura: consulta os detalhes AniList pelo ID externo e apresenta metadata disponível. Não cria anime SQLite, associação, item de Minha Lista, favorito, arquivo ou histórico.
- O retorno usa a pilha de navegação existente e preserva o estado da rota Search.
- Os testes de contrato estáticos Python foram atualizados para exigir a integração nativa e comprovar a separação dos IDs locais e AniList.

## Persistência e arquitetura

Nenhum banco ou índice persistente foi adicionado e o schema v29 não foi alterado. A busca é leitura; associação/atualização de metadata continua no serviço AniList e alterações pessoais continuam no serviço Kotlin de lista pessoal. A tela não converte resultados remotos em arquivos locais.

## Python que permanece

`core/search_engine.py` continua com consumidores legítimos em `core/library_service.py` e nos fluxos legados de organização. `core/anilist.py` continua com consumidores de enriquecimento de metadata/auto-associação em `core/library_service.py`. Não foi removido nenhum arquivo Python de produção porque esses módulos ainda não são globalmente obsoletos. Nenhuma operação principal da tela Compose Search delega a busca para Python.

## Validação

- Contratos estáticos do fluxo Search atualizados em `tests/test_native_search.py`.
- Nenhum teste Kotlin/JVM foi executado manualmente; nenhum teste ou job de emulador foi adicionado.
- Build Android, regressões automatizadas, artefatos APK/ZIP e workflows devem ser verificados no SHA final do PR. A documentação desta alteração não é, por si só, evidência de build aprovado.
- Limite preservado: a consulta remota usa a ordenação `SEARCH_MATCH` e a página de resultados já suportada pelo cliente nativo. Os filtros de biblioteca permanecem específicos à fonte local.
