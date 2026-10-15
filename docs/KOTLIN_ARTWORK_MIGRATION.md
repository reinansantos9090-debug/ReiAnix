# Migração do subsistema de artwork para Kotlin — Prompt 07/29

## Arquitetura implementada

`ReiAnixArtworkService` é o produtor persistente do caminho Compose. A instância é criada de forma lazy por `ReiAnixAppDependencies` e recebe a mesma `ReiAnixSqliteDatabase` usada pela biblioteca. A implementação utiliza a tabela existente `artwork` e os campos de `anime`; não cria outra base, repositório de cache ou tabela, nem altera o schema v29.

O serviço cobre materialização de poster e backdrop a partir de URLs já persistidas, consulta dos registros locais, gravação de resultados, tentativas de recuperação e publicação de eventos. O backfill é iniciado pelo ViewModel nativo em lotes pequenos e os downloads têm concorrência limitada. A varredura e o player não aguardam pela conclusão do pipeline.

## Download e cache de imagens

O download usa `HttpURLConnection` com as verificações TLS padrão do Android. O fluxo rejeita códigos HTTP não bem-sucedidos, conteúdo declarado como não-imagem, respostas vazias, assinaturas não reconhecidas, redirecionamento de HTTPS para HTTP e payload acima de 32 MiB. Os bytes são copiados em streaming para arquivo temporário, o arquivo é sincronizado, a imagem é validada por assinatura/decodificação e o temporário é promovido para o destino por movimentação atômica quando o filesystem oferece suporte.

A escrita persistente acontece depois da promoção e em transação na tabela `artwork`. O ponteiro `anime.cover_cache` só é atualizado se a URL atual ainda corresponder ao registro e não houver propriedade manual do campo. Uma capa local/manual é preservada e uma falha remota não limpa `cover_cache`. Falhas de rede e HTTP 429/5xx recebem `retry_wait` com horário persistido; falhas definitivas ficam `failed`, sem loop infinito de tentativas.

As chaves de cache nativas preservam o formato SHA-256 previamente utilizado pelo ArtworkEngine: provedor, identidade do item/URL, tipo e variante. Os arquivos válidos existentes e caminhos legados são consultados antes de baixar novamente. A limpeza do diretório de cache só remove arquivos de imagem não referenciados por `artwork.local_path` ou `anime.cover_cache`; ela não limpa globalmente o cache.

## Integração com AniList e Compose

Depois que os metadados são persistidos, o graph nativo pode agendar `requestAnimeArtwork`. O evento `anilist_metadata_committed` agora informa que o artwork não deve ser solicitado pelo Python; seu consumidor em `main.py` atualiza apenas a projeção. A persistência de capas é responsabilidade de `ReiAnixArtworkService`, e não há delegação para `library.artwork.sync_anime_metadata` ou `library.artwork.request` nesse fluxo.

Depois de persistir um resultado válido, o serviço envia `artwork_published` com `event=ARTWORK_PUBLISHED`, tipo/ID da entidade, tipo de imagem, caminho local, chave e referência. O handler atualiza os consumidores incrementais Flet que estiverem ativos e pede a renovação da projeção Compose por `artwork_ready`. A interface usa o banco canônico como fonte dos caminhos; Coil é renderer e pode atuar como fallback visual remoto enquanto o arquivo local é materializado.

## Thumbnails de vídeo

`VideoThumbnailExtractor` já realiza extração em background por `MediaMetadataRetriever`, valida os resultados do seu cache e dá suporte aos caminhos URI tratados pela implementação Android existente. Agora, tanto os resultados recém-extraídos quanto os cache hits tentam registrar o arquivo no mesmo catálogo `artwork`, com `entity_type=episode`, `artwork_type=episode_thumbnail`, `source=generated` e variante `small`. A identidade da origem inclui identidade de mídia, tamanho e data de modificação. A duração extraída só preenche `episodes.duration` quando o valor canônico ainda não é positivo.

O evento `thumbnail_ready` transporta `canonicalArtworkRegistered`. No modo Compose, Python não tenta registrar novamente o thumbnail já persistido em Kotlin. O escritor Python continua apenas como fallback do runtime Flet legado, quando Compose não está ativo. O extrator mantém uma chave de solicitação recente por URI para evitar registrar um resultado atrasado se uma revisão mais nova da mesma mídia já foi solicitada.

## Limite deliberado da migração Python

`core/artwork.py` e seus consumidores permanecem no repositório porque o runtime Flet ainda possui caminhos legítimos que chamam diretamente o ArtworkEngine. Eles não são removidos globalmente nesta etapa. Para o caminho de metadados e projeção Compose, porém, o Python deixou de executar download, sincronização de cache ou gravação canônica de thumbnails. A remoção total do ArtworkEngine será segura depois de migrados os consumidores Flet restantes e seus contratos de interface/testes.

## Testes e validação

Foi adicionado `tests/test_kotlin_artwork_migration_contract.py` com verificações de integração e contratos de fonte para a base SQLite compartilhada, download/validação, publicação de eventos e remoção da delegação Python no caminho Compose. Os testes Python existentes de metadados, artwork e thumbnails foram ajustados para refletir a nova fronteira de execução. Nenhum teste de emulador/instrumentado foi adicionado e testes Kotlin/JVM não são executados manualmente por este prompt.

A validação definitiva da compilação, testes de regressão e artefatos APK/ZIP pertence ao workflow do último commit desta etapa. O status do workflow e os artefatos devem ser verificados na execução do GitHub Actions; não podem ser inferidos a partir da presença dos arquivos neste documento.
