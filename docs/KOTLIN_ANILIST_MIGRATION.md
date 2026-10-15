# ReiAnix — migração AniList para Kotlin nativo

## Escopo

Este documento registra o fluxo AniList utilizado pelos consumidores nativos do ReiAnix. A implementação de produção para Compose está composta por android/app/src/main/kotlin/com/reiflix/reiflix_local/data/anilist/ReiAnixAniListClient.kt, ReiAnixAniListIntegrationService.kt, ReiAnixAniListDescriptionLocalizer.kt, o ReiAnixSqliteLibraryRepository canônico e ReiAnixAppDependencies.

A migração não remove o runtime híbrido nem o ArtworkEngine neste pacote. core/anilist.py permanece disponível para consumidores Flet/legados. No modo Compose, `main.py` chama `hydrate_catalog_metadata(..., allow_anilist_metadata=False)`: o worker Python não pesquisa AniList, não faz matching nem localiza/grava sinopses; ele reconcilia somente a materialização de imagens já persistidas pelo Kotlin. No modo Flet legado, o parâmetro continua no padrão verdadeiro e preserva o fluxo existente. Após o commit SQLite, Kotlin publica um evento mínimo ao NativeMailbox e o ArtworkEngine existente materializa as URLs. O evento não executa AniList nem grava metadata editorial.

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

`ReiAnixLibraryViewModel` agenda `reconcileUnmatchedLibraryMetadata()` fora do mutex da projeção visual. O serviço lê o catálogo canônico e consulta sequencialmente apenas registros elegíveis, sem ID AniList, sem associação manual e sem fonte/estado manual. Estados terminais (`not_found`, `ambiguous`, `network_error`, `rate_limited`, `invalid_response`) são persistidos por `recordAniListAutoMatchOutcome` na tabela `anime` e impedem consultas repetidas em cada refresh. O escritor checa novamente a associação/flag manual dentro da transação para não substituir seleção concorrente. Falhas de persistência são propagadas, não convertidas em sucesso.

## Persistência e atualizações parciais

ReiAnixAnimeMetadataPatch agora transporta separadamente description e descriptionOriginal. ReiAnixSqliteLibraryRepository.updateAnimeMetadata e updateAniListMetadataAndAssociation gravam a sinopse original somente quando há texto presente e o campo de descrição não pertence a uma edição manual protegida.

Não houve alteração no schema SQLite v29, criação de outro banco ou adição de um segundo repositório. O repositório usado por ReiAnixAppDependencies permanece a autoridade dos metadados locais. Campos remotos opcionais vazios/nulos não são usados para apagar o catálogo local; IDs de arquivo, progresso e campos pessoais continuam fora do patch editorial.

## Integração com imagens

Depois que a transação SQLite retorna com a metadata/associação atualizada, o serviço Kotlin sempre publica anilist_metadata_committed pelo NativeMailbox, fora da transação e em Dispatchers.IO. O payload inclui somente o ID local e a opção requestArtwork. O consumidor em main.py lê os campos já persistidos e sempre solicita a atualização da projeção Compose para a metadata; apenas quando requestArtwork está habilitado e há URLs de imagem chama o ArtworkEngine existente para sincronizar URLs e materializar poster/backdrop.

O evento contém somente um ID local e um request ID; não carrega sinopse, credenciais ou a resposta GraphQL. A falha de publicação não desfaz uma metadata já confirmada. O renderer Compose ainda pode usar a URL externa como fallback enquanto o ArtworkEngine materializa a cópia local. O motor produtor Python permanece uma dependência conhecida e não é considerado migrado por este prompt.

## Limites de migração mantidos explicitamente

core/anilist.py e core/library_service.py continuam sendo usados por caminhos legados/Flet e não foram excluídos para evitar regressão de fallback enquanto o runtime híbrido existe. A implementação nativa substitui operações AniList chamadas pelo fluxo de Compose; o cleanup final dessas rotas antigas depende da remoção dos consumidores híbridos nos pacotes posteriores. core/artwork.py continua responsável pela materialização persistente conforme o escopo que proíbe reescrevê-lo nesta etapa.

Não foram introduzidas bibliotecas HTTP/GraphQL adicionais, mecanismos de autenticação novos, esquema de banco, caches de pesquisa paralelos, testes de emulador ou mudança no workflow para executar testes Kotlin/JVM manualmente.

## Verificações e evidências

A suíte de regressão Python já contém contratos estáticos para cliente, ViewModel, associação transacional e preservação de metadata manual. Foram adicionadas as verificações estáticas do localizer/cache, do payload de sinopse original, da validação das respostas GraphQL e do evento de handoff para o ArtworkEngine. Os testes Kotlin/JVM do cliente e do localizer foram ampliados, mas continuam manuais por regra do projeto e não são executados manualmente como parte desta alteração.

O resultado final de build, regressão Python, workflows, SHA e artefatos APK/ZIP deve ser lido no resultado do GitHub Actions do commit final desta alteração; este documento não presume sucesso de um commit ou execução anterior.
