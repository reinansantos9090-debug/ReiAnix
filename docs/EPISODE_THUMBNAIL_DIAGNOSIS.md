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
