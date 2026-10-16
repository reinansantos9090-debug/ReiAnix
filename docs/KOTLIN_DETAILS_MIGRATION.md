# Migração de Details para Kotlin nativo — Prompt 12/29

## Escopo implementado

A tela Details de itens locais já era construída em Jetpack Compose e usa o modelo `ReiAnixDetailsUiState`, projetado do snapshot de biblioteca produzido pelo `ReiAnixLibraryViewModel`. Esse snapshot lê a biblioteca pela camada Kotlin de SQLite canônico; a projeção usa os IDs persistidos de anime e episódio, sem identificar itens por título.

Esta etapa fecha o caminho entre a Details remota do AniList e os detalhes locais sem converter indevidamente um resultado de catálogo em registro local. O repositório consulta `anime.anilist_id` por igualdade de ID, parametriza a consulta e limita o resultado a dois IDs para detectar associações ambíguas. Não usa título, cria registros nem altera associações. A integração AniList reutiliza o repositório existente.

Quando uma resposta remota tem exatamente uma associação local explícita, a tela oferece “Abrir detalhes da biblioteca” e navega com o ID interno persistido. Quando não há associação, a página continua somente leitura; quando há mais de uma, não escolhe uma delas automaticamente. Falhas na verificação da associação não escondem os metadados remotos. O `origin` da rota é preservado na navegação.

## Arquitetura existente reutilizada

- `ReiAnixDetails.kt` e `ReiAnixDetailsUiModels.kt`: UI Compose local, estados de carregamento/erro, sinopse, detalhes disponíveis, listas lazy de temporadas/episódios e chaves estáveis.
- `ReiAnixLibraryViewModel`, `ReiAnixLibraryQueryService` e `ReiAnixSqliteLibraryRepository`: projeções e consultas da biblioteca local pela base canônica.
- `ReiAnixAniListClient`, `ReiAnixAniListIntegrationService` e `ReiAnixAniListViewModel`: acesso remoto AniList, associação persistida explícita e proteção contra respostas obsoletas.
- `ReiAnixArtworkService` e os componentes de arte Compose existentes: cache/atualização de artwork sem um segundo downloader.
- `ReiAnixConsumptionService`: progresso persistido e invalidação reativa após checkpoints do player.
- `ReiAnixPersonalListService`: estado canônico de Minha Lista/favorito; a ação não cria outro armazenamento.
- `NativePlayerActivity` com Media3/ExoPlayer: reprodução dos episódios locais pelo ID persistido do episódio.
- `ReiAnixNavigation`: rotas únicas para Details local/remota; a origem do destino remoto é encaminhada para o botão de navegação local.

## Identidade, dados e estados

O ID AniList é usado somente para consultar uma associação explícita; ele não é usado como ID local de anime/episódio. Não há fallback por título aproximado. Resultados remotos sem uma associação local continuam sendo itens remotos e não podem ser reproduzidos nem adicionados à base por abrir Details.

A UI local mantém os dados opcionais que já vêm do catálogo persistido, diferencia quantidade de episódios anunciados e quantidade de mídia local e preserva episódios por chaves persistentes. As respostas obsoletas da busca de detalhes continuam protegidas pelo contador de solicitação do ViewModel. Falhas da nova consulta de associação são mostradas separadamente e não descartam o detalhe remoto já carregado.

## Python ainda presente

Esta etapa não remove arquivos Python globalmente. `views/details_view.py`, `core/compose_library_bridge.py`, `core/library_store.py`, `core/anilist.py` e `core/consumption.py` permanecem no repositório híbrido e têm consumidores, contratos de compatibilidade ou testes de outros fluxos. A navegação Compose local/remota adicionada aqui não delega suas novas operações de associação para Python, mas isso, por si só, não prova que os módulos legados são globalmente obsoletos. A remoção dos módulos compartilhados exige migrar e verificar seus consumidores restantes em etapas futuras.

## Testes e validação

Foram acrescentados testes contratuais Python para verificar que a consulta usa somente o ID AniList persistido, que o botão de rota local aparece somente para uma associação inequívoca, que a origem da rota é preservada e que a Details remota não cria um registro. Esses testes serão executados pelos workflows normais do repositório. Os testes Kotlin/JVM permanecem reservados para execução manual ao final da migração e não são executados manualmente nesta etapa. Nenhum teste de emulador ou instrumentado novo é adicionado por este prompt.

## Persistência e limitações

Não foi alterado o schema; a base canônica `library.sqlite3` e o schema v29 são preservados. Nenhum dado, caminho local, progresso, histórico, favorito, cache ou associação foi apagado. A seleção direta local a partir de Details remota é intencionalmente desabilitada quando há zero ou mais de uma associação exata. A tela local mantém o comportamento atual dos serviços Compose/Kotlin; a retirada total do runtime híbrido do aplicativo permanece fora deste prompt e não é alegada como concluída.
