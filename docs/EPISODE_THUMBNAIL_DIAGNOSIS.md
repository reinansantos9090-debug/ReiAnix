# Diagnóstico técnico — thumbnails de episódios

## Comparação com CloudStream

O código comparado foi o branch `master` do [CloudStream](https://github.com/recloudstream/cloudstream), árvore `a7ac454af8fbd64574c96847a936905855911720`.

- [`PreviewGenerator.kt`](https://github.com/recloudstream/cloudstream/blob/a7ac454af8fbd64574c96847a936905855911720/app/src/main/java/com/lagradost/cloudstream3/ui/player/PreviewGenerator.kt#L97-L119) usa `MediaMetadataRetriever` e `getScaledFrameAtTime` (API 27+) para gerar imagens pequenas de prévia do player. Em `start`, escolhe vários timestamps ao longo do vídeo e mantém previews em memória para navegação/seek. Isso é comparável à extração local de frames, mas não é um catálogo persistente de thumbnails por episódio.
- [`EpisodeAdapter.kt`](https://github.com/recloudstream/cloudstream/blob/a7ac454af8fbd64574c96847a936905855911720/app/src/main/java/com/lagradost/cloudstream3/ui/result/EpisodeAdapter.kt#L237-L258) carrega `item.poster` quando fornecido e limpa a imagem quando ausente. Esse poster é dado pelo modelo/provedor; o trecho não gera uma thumbnail a partir do arquivo local do episódio.
- [`ImageModuleCoil.kt`](https://github.com/recloudstream/cloudstream/blob/a7ac454af8fbd64574c96847a936905855911720/app/src/main/java/com/lagradost/cloudstream3/utils/ImageModuleCoil.kt) configura cache de memória/disco e logs de erro do Coil, mas não converte posters remotos em frames locais.

Logo, a referência pertinente é a parte de `PreviewGenerator` que abre a mídia com `MediaMetadataRetriever`, usa timestamps múltiplos e reduz a dimensão da imagem. O fluxo de poster do `EpisodeAdapter` não pode ser copiado para cumprir o requisito offline do ReiAnix.

## Caminho atual do ReiAnix

1. [`ReiAnixDetails.kt`](https://github.com/reinansantos9090-debug/ReiAnix/blob/main/android/app/src/main/kotlin/com/reiflix/reiflix_local/ui/details/ReiAnixDetails.kt) chama `requestMissingEpisodeThumbnails(canonicalId)` quando Details está `READY`.
2. [`ReiAnixLibraryViewModel.kt`](https://github.com/reinansantos9090-debug/ReiAnix/blob/main/android/app/src/main/kotlin/com/reiflix/reiflix_local/viewmodel/ReiAnixLibraryViewModel.kt) seleciona episódios locais disponíveis, valida o caminho existente e chama `VideoThumbnailExtractor.extract`.
3. [`VideoThumbnailExtractor.kt`](https://github.com/reinansantos9090-debug/ReiAnix/blob/main/android/app/src/main/kotlin/com/reiflix/reiflix_local/storage/VideoThumbnailExtractor.kt) lê metadados, tenta frames com `MediaMetadataRetriever`, avalia luminância/detalhe, grava um JPEG temporário e valida de novo o arquivo gravado.
4. [`ReiAnixArtworkService.kt`](https://github.com/reinansantos9090-debug/ReiAnix/blob/main/android/app/src/main/kotlin/com/reiflix/reiflix_local/data/artwork/ReiAnixArtworkService.kt) relaciona o JPEG à linha canônica `artwork` com `entity_type='episode'` e `artwork_type='episode_thumbnail'`.
5. [`ReiAnixSqliteLibraryRepository.kt`](https://github.com/reinansantos9090-debug/ReiAnix/blob/main/android/app/src/main/kotlin/com/reiflix/reiflix_local/data/sqlite/ReiAnixSqliteLibraryRepository.kt) lê linhas de artwork não falhadas, ordena por prioridade/data/id e mantém a primeira associação por episódio. [`ReiAnixNativeLibraryCatalogProjector.kt`](https://github.com/reinansantos9090-debug/ReiAnix/blob/main/android/app/src/main/kotlin/com/reiflix/reiflix_local/data/library/ReiAnixNativeLibraryCatalogProjector.kt) passa o caminho associado para o modelo de episódio.
6. [`ReiAnixComponents.kt`](https://github.com/reinansantos9090-debug/ReiAnix/blob/main/android/app/src/main/kotlin/com/reiflix/reiflix_local/ui/ReiAnixComponents.kt) entrega `episode.artwork.localPath` a `ReiAnixEpisodeThumbnail`; [`ReiAnixLocalArtwork.kt`](https://github.com/reinansantos9090-debug/ReiAnix/blob/main/android/app/src/main/kotlin/com/reiflix/reiflix_local/ui/artwork/ReiAnixLocalArtwork.kt) solicita o recurso ao Coil.

## Defeitos de código confirmados

1. **Cobertura temporal insuficiente.** A lista anterior de oito timestamps terminava em 38% da duração. Um vídeo com abertura preta/longa podia não oferecer nenhum frame candidato da parte posterior. A lista agora tem dez candidatos distribuídos de 0,5% a 93% da duração e o algoritmo ganhou uma nova versão de cache para não reutilizar resultados antigos.
2. **Invalidação incompleta de associações duplicadas.** A projeção SQLite escolhe apenas a primeira linha de artwork de cada episódio, enquanto a invalidação anterior alterava somente o caminho selecionado. Se uma segunda linha gerada, mais antiga e também inválida, continuasse registrada, ela poderia tornar-se a linha selecionada no refresh seguinte. Agora o serviço examina os caminhos de todas as linhas geradas daquele episódio e invalida, individualmente, apenas os arquivos cuja validação visual falha; imagens locais válidas e artwork manual não gerado são preservados.
3. **Imagem decodificável não implica imagem útil.** `MediaMetadataRetriever` conseguir ler a duração não demonstra que retornou frames. E Coil pode exibir um JPEG preto decodificável como sucesso, sem chamar `onError`. Por isso a seleção/validação visual tem de acontecer antes de registrar ou reutilizar o arquivo.
4. **Fallbacks de poster não são válidos para thumbnails locais.** O componente genérico aceitava candidatos remotos. `episode_thumbnail` agora usa apenas caminhos locais; poster/backdrop continua com o comportamento existente.

## Evidência e limite da conclusão

Os defeitos acima são confirmados pela inspeção estática dos caminhos descritos. O estado do SQLite privado do aparelho e os vídeos que geraram as imagens pretas não estão disponíveis neste ambiente; portanto, não é possível identificar qual linha/arquivo exato da biblioteca pessoal provocou o sintoma. Não foi detectado dispositivo Android via ADB, e não há vídeo de episódio no repositório. Testes de JPEG/pixels validam a lógica de qualidade de imagem, mas não substituem a execução do extrator Android sobre vídeos reais ou uma inspeção visual da interface no telefone.


## Referências adicionais confirmadas

A pesquisa dos repositórios foi feita em seus branches oficiais e commits identificados, sem presumir que os três projetos implementem a mesma funcionalidade.

### CloudStream

- Repositório oficial: [recloudstream/cloudstream](https://github.com/recloudstream/cloudstream), commit consultado [`a7ac454af8fbd64574c96847a936905855911720`](https://github.com/recloudstream/cloudstream/commit/a7ac454af8fbd64574c96847a936905855911720).
- [`PreviewGenerator.kt`](https://github.com/recloudstream/cloudstream/blob/a7ac454af8fbd64574c96847a936905855911720/app/src/main/java/com/lagradost/cloudstream3/ui/player/PreviewGenerator.kt) faz previews de navegação do player. Para MP4 usa `MediaMetadataRetriever`, gera amostras em vários pontos e, no helper de imagem, usa `getScaledFrameAtTime` em versões Android recentes com `OPTION_CLOSEST_SYNC`; há fallback para `getFrameAtTime` em APIs antigas. O gerador mantém imagens em memória e tenta o gerador alternativo quando o atual não fornece preview. Esse mecanismo é comparável à abertura e amostragem do vídeo local.
- [`EpisodeAdapter.kt`](https://github.com/recloudstream/cloudstream/blob/a7ac454af8fbd64574c96847a936905855911720/app/src/main/java/com/lagradost/cloudstream3/ui/result/EpisodeAdapter.kt) exibe `item.poster`, quando fornecido pelo modelo/provedor. Não é uma prova de geração de thumbnails a partir de vídeos locais.
- [`ImageModuleCoil.kt`](https://github.com/recloudstream/cloudstream/blob/a7ac454af8fbd64574c96847a936905855911720/app/src/main/java/com/lagradost/cloudstream3/utils/ImageModuleCoil.kt) configura cache de Coil e logs de falha de carregamento. Ele não implementa o pipeline persistente de frames locais que o ReiAnix precisa.

**Mecanismo aproveitado:** a seleção de frames locais por múltiplos timestamps e a estratégia de usar frames de sincronização como alternativa quando o modo de frame mais próximo não retorna uma imagem utilizável. Não foi copiado o fluxo de posters dos provedores.

### Aniyomi

- Repositório oficial: [aniyomiorg/aniyomi](https://github.com/aniyomiorg/aniyomi), commit consultado [`97414446b8a95994c72dd33c41c971a89d4d25b8`](https://github.com/aniyomiorg/aniyomi/commit/97414446b8a95994c72dd33c41c971a89d4d25b8).
- [`LocalEpisodeThumbnailManager.kt`](https://github.com/aniyomiorg/aniyomi/blob/97414446b8a95994c72dd33c41c971a89d4d25b8/source-local/src/androidMain/kotlin/tachiyomi/source/local/image/anime/LocalEpisodeThumbnailManager.kt) procura uma imagem já existente na pasta local do anime, valida que o arquivo seja uma imagem, copia um `InputStream` para um arquivo de thumbnail e preenche `episode.preview_url`. O trecho inspecionado não extrai frames do vídeo com `MediaMetadataRetriever`, `ThumbnailUtils` ou Media3.
- **Comparável:** validação de imagem, persistência e associação do arquivo ao episódio. **Não comparável:** extração automática de frames de um vídeo local.

### Anikku

- A identidade foi confirmada: [`komikku-app/anikku`](https://github.com/komikku-app/anikku), repositório público oficial da organização `komikku-app`, descrito como aplicativo Android de anime; branch padrão `master`, commit consultado [`6e35b41f0cc7e375d15527bd61375435f998685a`](https://github.com/komikku-app/anikku/commit/6e35b41f0cc7e375d15527bd61375435f998685a). Não foi assumido que qualquer repositório de nome parecido fosse o oficial.
- [`LocalEpisodeThumbnailManager.kt`](https://github.com/komikku-app/anikku/blob/6e35b41f0cc7e375d15527bd61375435f998685a/source-local/src/androidMain/kotlin/tachiyomi/source/local/image/LocalEpisodeThumbnailManager.kt) também localiza arquivos de imagem pré-existentes, copia o fluxo da imagem e associa sua URI ao episódio. Não é um gerador de frames do arquivo de vídeo.
- **Mecanismo comparável:** persistência/associação de uma thumbnail local fornecida por outro fluxo. **Não comparável:** decodificação de vídeo para criar a thumbnail.

### Animiru

- A identidade foi confirmada: [`animiruorg/Animiru`](https://github.com/animiruorg/Animiru), projeto Android da organização oficial, branch `animiru-new-main`, commit consultado [`5988ac108ad7bcc15df2465599a32c0c5bea5fa4`](https://github.com/animiruorg/Animiru/commit/5988ac108ad7bcc15df2465599a32c0c5bea5fa4).
- [`LocalEpisodeThumbnailManager.kt`](https://github.com/animiruorg/Animiru/blob/5988ac108ad7bcc15df2465599a32c0c5bea5fa4/source-local/src/main/kotlin/tachiyomi/source/local/image/LocalEpisodeThumbnailManager.kt) pesquisa imagens locais existentes com o nome do episódio e copia um fluxo de imagem; o trecho não gera frames do vídeo.
- [`ThumbnailCache.kt`](https://github.com/animiruorg/Animiru/blob/5988ac108ad7bcc15df2465599a32c0c5bea5fa4/app/src/main/java/animiru/core/cache/ThumbnailCache.kt) deriva o nome do cache da URL da thumbnail. [`EpisodeThumbnailKeyer.kt`](https://github.com/animiruorg/Animiru/blob/5988ac108ad7bcc15df2465599a32c0c5bea5fa4/app/src/main/java/animiru/core/coil/EpisodeThumbnailKeyer.kt) inclui URL e `lastModified` na chave do Coil; [`ThumbnailRequestInterceptor.kt`](https://github.com/animiruorg/Animiru/blob/5988ac108ad7bcc15df2465599a32c0c5bea5fa4/app/src/main/java/animiru/core/coil/ThumbnailRequestInterceptor.kt) deduplica requisições concorrentes bem-sucedidas; [`EpisodeThumbnailFetcher.kt`](https://github.com/animiruorg/Animiru/blob/5988ac108ad7bcc15df2465599a32c0c5bea5fa4/app/src/main/java/animiru/core/coil/EpisodeThumbnailFetcher.kt) diferencia caminhos de arquivo, URIs locais e URLs remotas. Esses componentes lidam com carregamento/cache de uma preview já identificada, não com extração de frames do vídeo.
- **Mecanismos úteis:** chave de cache vinculada à identidade/revisão do recurso e deduplicação de carregamentos. Não foi copiado o pipeline de rede/cache do Animiru, pois o requisito do ReiAnix é offline e local.

## Regressão histórica e correção nesta missão

O histórico não prova qual arquivo do aparelho produziu os pixels pretos, mas permite localizar mudanças candidatas sem reverter tudo:

- [`6bab45c808e57e957588e9711f66657b8667613e`](https://github.com/reinansantos9090-debug/ReiAnix/commit/6bab45c808e57e957588e9711f66657b8667613e) introduziu seleção por qualidade em vários timestamps e trocou a extração inicial única em `OPTION_CLOSEST_SYNC` por tentativas em `OPTION_CLOSEST`. Esta alteração ampliou a cobertura temporal, mas mudou o modo de decodificação principal.
- [`032e1adf949f85f3ee84e02dfbed9735fb31d17b`](https://github.com/reinansantos9090-debug/ReiAnix/commit/032e1adf949f85f3ee84e02dfbed9735fb31d17b) introduziu rejeição visual e invalidação de artwork; [`23cf5a8c630ae3303bc0f99872921612ae2e8708`](https://github.com/reinansantos9090-debug/ReiAnix/commit/23cf5a8c630ae3303bc0f99872921612ae2e8708) adicionou regeneração de associações visualmente inválidas; [`426d91bc950fae549c1e067e5d7b2f716d72a2d2`](https://github.com/reinansantos9090-debug/ReiAnix/commit/426d91bc950fae549c1e067e5d7b2f716d72a2d2) versionou a chave para aposentar caches antigos.
- [`c079d8fe2ade55251b9b37b4b78fb0aef0ea694c`](https://github.com/reinansantos9090-debug/ReiAnix/commit/c079d8fe2ade55251b9b37b4b78fb0aef0ea694c) ampliou a faixa de timestamps até 93% e cobriu invalidação de linhas geradas duplicadas. [`5bb48bd1175e210ea311152677932b149f7a6ccf`](https://github.com/reinansantos9090-debug/ReiAnix/commit/5bb48bd1175e210ea311152677932b149f7a6ccf) alterou a configuração de certificação/teste, e [`4580a741e807eea26f682904c5faebe88bbed4ff`](https://github.com/reinansantos9090-debug/ReiAnix/commit/4580a741e807eea26f682904c5faebe88bbed4ff) corrigiu inferência de tipo em leitura de linhas; esses dois commits não mostram uma nova mudança no algoritmo de seleção de frames.

A falha de integração confirmada no código era que `ReiAnixDetails.kt` disparava o processamento só quando `state.status` mudava para `READY`. A projeção canônica pode atualizar a URI/referência local, disponibilidade, duração ou artwork enquanto o status continua `READY`; nesse caso, uma chamada inicial sem fonte resolvível não era repetida. Além disso, uma chamada recebida quando o mesmo episódio estava em processamento era descartada pela trava de deduplicação sem marcar uma nova tentativa com a projeção recente.

Nesta missão, a tela passou a derivar uma revisão de solicitação dos campos relevantes do episódio e a disparar novamente quando essa revisão muda, sem repetir o auto-match do AniList. O ViewModel marca uma única repetição quando a fonte muda durante um lote e a agenda depois que libera as travas atuais. No extrator, `OPTION_CLOSEST` continua sendo a primeira tentativa; `OPTION_CLOSEST_SYNC` é tentado somente quando o primeiro modo não retorna frame ou a imagem falha na validação visual. Os logs registram as tentativas de fallback.

Essa é uma causa confirmada para solicitações não repetidas após mudanças assíncronas na fonte; não é prova de que ela explique todos os JPEGs pretos que já estavam no aparelho. Para estabelecer a causa exata desses arquivos, ainda é necessário observar os vídeos/URIs reais, as linhas SQLite correspondentes e a saída visual em um dispositivo.

