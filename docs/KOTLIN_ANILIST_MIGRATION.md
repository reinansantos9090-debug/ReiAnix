# ReiAnix — migração AniList para Kotlin nativo

## Escopo

Este documento registra o fluxo AniList utilizado pelos consumidores nativos do ReiAnix. A implementação de produção para Compose está composta por android/app/src/main/kotlin/com/reiflix/reiflix_local/data/anilist/ReiAnixAniListClient.kt, ReiAnixAniListIntegrationService.kt, ReiAnixAniListDescriptionLocalizer.kt, o ReiAnixSqliteLibraryRepository canônico e ReiAnixAppDependencies.

A migração não remove o runtime híbrido nem os consumidores Python legados. core/anilist.py e core/artwork.py permanecem disponíveis para jornadas Flet ainda ativas. No modo Compose, main.py chama hydrate_catalog_metadata(..., allow_anilist_metadata=False): o worker Python não pesquisa AniList, não faz matching nem materializa imagens. O evento anilist_metadata_committed apenas atualiza a projeção, enquanto ReiAnixArtworkService em Kotlin materializa as URLs persistidas usando o mesmo SQLite v29. No modo Flet legado, o parâmetro continua verdadeiro por padrão e mantém a compatibilidade até esses consumidores serem migrados.

## Cliente GraphQL

- Endpoint: https://graphql.anilist.co.
- Transporte: HttpURLConnection por meio de AniListTransport, executado em Dispatchers.IO, com timeouts de conexão/leitura configurados e desconexão quando a coroutine é cancelada.
- Operações públicas usadas pelos consumidores nativos: pesquisa de anime via Page(page, perPage).media(search, type: ANIME, sort: SEARCH_MATCH) e busca de detalhes via Media(id, type: ANIME).
- Variáveis GraphQL são enviadas separadamente no objeto variables; não são interpoladas dentro do texto de consulta.
- Intervalo mínimo de requisição: 700 ms por instância do cliente. HTTP, Retry-After, erros GraphQL, JSON inválido e respostas malformadas têm resultados tipados em AniListResult/AniListError.
- Uma lista media ausente, um item sem objeto JSON ou sem ID válido não é representado como pesquisa vazia. Em detalhes, um campo Media explicitamente nulo significa “sem correspondência”; o campo ausente ou de tipo inválido é erro de resposta.
- Erros GraphQL não são convertidos em metadados de sucesso, mesmo quando o HTTP retorna 200. Uma resposta inválida não é utilizada para limpar campos locais.

Os campos de mídia consultados são: id; títulos romaji, english e native; synonyms; descrição; formato; episódios; duração; status; pontuação média; temporada e ano; gêneros; capas large/extraLarge; imagem de fundo; e estúdios principais. A estrutura AniListAnime conserva os campos opcionais como valores nulos quando não existem. A busca mantém os critérios atuais do AniList (SEARCH_MATCH) e a paginação existente. O cliente não adiciona um segundo cache de pesquisa: não havia um contrato de cache de busca Kotlin canônico existente para reutilizar, e o catálogo SQLite já materializa metadados por obra.

## Pesquisa, identidade e associação

ReiAnixAniListIntegrationService é o ponto de entrada para os consumidores nativos. Ele implementa pesquisa, atualização explícita por ID, associação manual e associação automática conservadora. O matching é executado por `ReiAnixAniListMatcher`, portado de `core/organizer_ai.py`: normalização NFKD/diacríticos, limpeza de release tags e marcadores de episódio/temporada/ano, comparação por igualdade/contém, razão de blocos de sequência e Jaccard de tokens, ajustes de formato/temporada/ano e ordenação determinística. Os limites usados são `MINIMUM_SCORE=0.48`, `AUTO_CONFIRM_SCORE=0.86` e `AUTO_CONFIRM_MARGIN=0.05`, alinhados ao contrato Python. Associação manual e IDs existentes têm prioridade; candidatos abaixo dos limites ficam ambíguos e não são persistidos como associação.

ReiAnixAniListViewModel cancela uma pesquisa substituída e usa identidade de requisição para impedir que uma resposta antiga sobrescreva os resultados da busca atual. Os estados de carregamento, ausência de resultados, falha e associação ficam separados.

A gravação de associação usa updateAniListMetadataAndAssociation no repositório SQLite existente. A associação, o ID AniList, o estado de matching e os metadados editoriais elegíveis são persistidos na mesma transação. O método preserva metadados manuais e uma associação manual existente, rejeita matching automático fraco e não grava local_path, progresso, favoritos, listas, notas pessoais ou identidade de arquivo.

## Sinopses, idioma e cache de tradução

ReiAnixAniListDescriptionLocalizer portou a normalização de HTML, a detecção conservadora de idioma, segmentação por parágrafo e limite de 480 bytes UTF-8 por trecho, validação da tradução e cache compatível com as entradas JSON legadas.

- Serviço externo de tradução existente: https://api.mymemory.translated.net/get, com langpair=idioma|pt-BR.
- Endpoint de tradução é chamado fora da thread principal, com timeouts limitados, cancelamento e até três tentativas somente quando uma resposta HTTP 429/5xx traz um Retry-After explícito dentro do limite configurado. Falhas de rede ou respostas inválidas não são convertidas em tradução fictícia.
- A tradução é validada antes de ser aceita; respostas de erro, texto vazio, saída idêntica ao original para idiomas de origem diferentes de português ou conteúdo que não passe pelo detector PT-BR não substituem a descrição.
- O cache é limitado a 4096 entradas. Falhas temporárias recebem retry_after limitado para não repetir a mesma requisição indefinidamente.
- O arquivo compartilhado mantém a localização legada: filesDir/data/covers/anilist_description_ptbr.json, equivalente ao LibraryStore.cache_dir (data/covers). Ele é cache, não fonte de verdade.
- A coluna canônica description_original mantém a sinopse original remota; description guarda a tradução validada, quando disponível, ou o original como fallback. Uma resposta sem descrição não apaga os valores existentes. Quando o original remoto é o mesmo, uma tradução PT-BR válida já persistida é preservada.
- Não há tradução inventada quando o idioma é desconhecido ou o serviço falha. Nesse caso a interface recebe o original, sem perda de metadata.

## Hidratação nativa e estados de associação

A reconciliação `reconcileUnmatchedLibraryMetadata()` é acionada pelo host Compose somente após uma transição real do estado de varredura de ativo para concluído. O fluxo descarta o primeiro snapshot com `drop(1)` e filtra a transição de término, portanto montar o host/abrir o app ou observar um estado ocioso inicial não dispara chamadas remotas. O `ReiAnixLibraryViewModel` continua isolado de AniList; o host faz a coordenação pós-scan em vez de disparar por tamanho do catálogo. O serviço lê o catálogo canônico e consulta sequencialmente apenas registros sem ID AniList e sem associação/metadata manual. Resultados estáveis (`not_found`, `ambiguous`) são persistidos por `recordAniListAutoMatchOutcome` na tabela `anime` e impedem consultas repetidas em cada refresh. Falhas transitórias (`network_error`, `rate_limited`, `invalid_response`) também são registradas para diagnóstico, mas não são tratadas como terminais; um novo ciclo de reconciliação após cooldown/varredura ou reinício pode tentar novamente. O escritor checa novamente a associação/flag manual dentro da transação para não substituir seleção concorrente. Falhas de persistência são propagadas, não convertidas em sucesso.


## Consultas GraphQL em lotes

O cliente centralizado ReiAnixAniListClient agrupa consultas de identidade/metadados em lotes com limite local de 15 itens por operação. Esse limite é intencionalmente menor que o máximo de paginação documentado pela API e evita operações excessivamente grandes.

Para registros com AniList ID válido, getAnimeBatch usa Page.media(id_in: $ids, type: ANIME), com page=1 e perPage igual ao total de IDs únicos do lote. IDs não positivos são descartados, IDs repetidos são consultados uma única vez e cada resposta é indexada pelo ID remoto antes da associação aos IDs locais. Um ID ausente em uma resposta completa pode ser tratado como inexistente; quando há erro GraphQL ou forma incompleta, os resultados válidos são preservados e os IDs ausentes permanecem recuperáveis.

Para registros ainda sem AniList ID, searchAnimeBatch agrupa até 15 títulos primários distintos em aliases GraphQL independentes (term0, term1, ...), cada um usando sua própria variável GraphQL. Os títulos nunca são interpolados no documento da consulta. O matcher existente e seus critérios não foram reescritos: somente a pesquisa primária é agrupada; títulos alternativos continuam sendo consultados pelo caminho atual quando a primeira lista de candidatos não é suficiente para uma associação segura.

Cada operação usa o mesmo transporte ReiAnixAniListClient e o AniListGraphqlRequestController processShared já instalado no Prompt 1. Não há cliente, transporte, mutex ou controlador paralelo. A serialização, o pacing e a retomada automática de 429 continuam centralizados nesse controlador.

A reconciliação percorre todos os registros elegíveis, agrupando as buscas primárias em chunks de até 15. A recuperação de metadados/capas não possui mais o orçamento fixo de 12 registros por execução: IDs conhecidos são consultados em chunks de até 15 e cada chunk é aplicado ao SQLite antes do envio do próximo. A busca por títulos sem ID também usa os aliases em chunks. A persistência continua usando a tabela anime e as preferências/cursor da base SQLite v29 já existente; as URLs remotas são entregues ao ReiAnixArtworkService por meio do publisher já existente, sem aguardar os downloads das imagens.

Erros de transporte e respostas GraphQL incompletas não são convertidos em not_found. No lote de títulos, um alias com erro é marcado como incompleto sem invalidar aliases independentes que retornaram dados válidos. No lote por ID, dados válidos são preservados e ausências em um lote incompleto são tratadas como falha transitória, não como inexistência.

## Persistência e atualizações parciais

ReiAnixAnimeMetadataPatch agora transporta separadamente description e descriptionOriginal. ReiAnixSqliteLibraryRepository.updateAnimeMetadata e updateAniListMetadataAndAssociation gravam a sinopse original somente quando há texto presente e o campo de descrição não pertence a uma edição manual protegida.

Não houve alteração no schema SQLite v29, criação de outro banco ou adição de um segundo repositório. O repositório usado por ReiAnixAppDependencies permanece a autoridade dos metadados locais. Campos remotos opcionais vazios/nulos não são usados para apagar o catálogo local; IDs de arquivo, progresso e campos pessoais continuam fora do patch editorial.

## Integração com imagens

Depois que a transação SQLite retorna com a metadata/associação atualizada, o serviço Kotlin agenda ReiAnixArtworkService.requestAnimeArtwork quando requestArtwork estiver habilitado e, em seguida, publica anilist_metadata_committed pelo NativeMailbox. A mensagem contém somente o ID local e define requestArtwork=false; seu consumidor em main.py atualiza a projeção Compose, sem delegar downloads nem sincronização de artwork ao Python.

O evento contém somente um ID local e um request ID; não carrega sinopse, credenciais ou a resposta GraphQL. A falha de publicação não desfaz metadata já confirmada. O serviço nativo valida o arquivo, atualiza artwork e anime.cover_cache na base existente e publica ARTWORK_PUBLISHED depois do commit. Coil ainda pode usar a URL remota como fallback visual, mas esse fallback não substitui o cache persistente.

## Limites de migração mantidos explicitamente

core/anilist.py e core/library_service.py continuam presentes para os fluxos Flet/legados. O caminho Compose passou a usar ReiAnixArtworkService para materializar capas e banners; core/artwork.py permanece somente enquanto os consumidores Flet legítimos ainda chamam ArtworkEngine. A remoção integral do runtime híbrido depende da migração dessas telas e rotas legadas em fases posteriores.

Não foram introduzidas bibliotecas HTTP/GraphQL adicionais, mecanismos de autenticação novos, esquema de banco, caches de pesquisa paralelos, testes de emulador ou mudança no workflow para executar testes Kotlin/JVM manualmente.

## Verificações e evidências

A suíte de regressão Python contém contratos do cliente, ViewModel, associação transacional, preservação de metadata manual e validação do pipeline nativo de artwork. A certificação de release executa :app:testDebugUnitTest e :app:compileReleaseKotlin; o workflow dedicado ReiAnix Kotlin/JVM Tests permanece manual (workflow_dispatch) e sua configuração não foi alterada.

O resultado final de build, regressão Python, workflows, SHA e artefatos APK/ZIP deve ser lido no resultado do GitHub Actions do commit final desta alteração; este documento não presume sucesso de um commit ou execução anterior.


A recuperação complementar removeu efeitos remotos dependentes apenas do ciclo de vida da Activity. Após um scan explicitamente iniciado e seu commit terminal, o fluxo nativo atualiza o catálogo, executa matching AniList e solicita o backfill de artwork pendente; isso preserva a cadeia de metadados/capas sem executá-la só por abrir a biblioteca.