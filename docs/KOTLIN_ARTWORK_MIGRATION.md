# Migração do subsistema de artwork para Kotlin — Prompt 07/29

## Arquitetura implementada

`ReiAnixArtworkService` é o produtor persistente do caminho Compose. A instância é criada de forma lazy por `ReiAnixAppDependencies` e recebe a mesma `ReiAnixSqliteDatabase` usada pela biblioteca. A implementação utiliza a tabela existente `artwork` e os campos de `anime`; não cria outra base, repositório de cache ou tabela, nem altera o schema v29.

O serviço cobre materialização de poster e backdrop a partir de URLs já persistidas, consulta dos registros locais, gravação de resultados, tentativas de recuperação e publicação de eventos. O backfill é iniciado no host Compose quando o catálogo SQLite está saudável e também após a conclusão de uma varredura; portanto, reabrir o app não depende de um novo scan para recuperar posters pendentes. O backfill só processa URLs persistidas e não inicia matching de títulos nem consultas AniList adicionais. Os downloads têm concorrência limitada. A varredura e o player não aguardam pela conclusão do pipeline.

## Download e cache de imagens

O download usa `HttpURLConnection` com as verificações TLS padrão do Android. O fluxo rejeita códigos HTTP não bem-sucedidos, conteúdo declarado como não-imagem, respostas vazias, assinaturas não reconhecidas, redirecionamento de HTTPS para HTTP e payload acima de 32 MiB. Os bytes são copiados em streaming para arquivo temporário, o arquivo é sincronizado e a imagem é validada por assinatura/decodificação. O destino usa nome baseado no checksum para não sobrescrever capas existentes. A promoção tenta um rename no mesmo diretório (atômico nos filesystems compatíveis); em API 24–25 ou quando rename falha, copia para o destino único, valida a cópia e só então persiste seu caminho no SQLite.

A escrita persistente acontece depois da promoção e em transação na tabela `artwork`. O ponteiro `anime.cover_cache` só é atualizado se a URL atual ainda corresponder ao registro e não houver propriedade manual do campo. Uma capa local/manual é preservada e uma falha remota não limpa `cover_cache`. Falhas de transporte, timeout e HTTP 408/429/5xx recebem `retry_wait` com horário persistido; respostas permanentes (por exemplo HTTP 404 ou conteúdo inválido) ficam `failed` com cooldown persistido de reavaliação. Nenhuma falha necessária é bloqueada por um limite total de tentativas.

As chaves de cache nativas preservam o formato SHA-256 previamente utilizado pelo ArtworkEngine: provedor, identidade do item/URL, tipo e variante. Arquivos válidos já associados são mantidos sem download; arquivos completos com nome baseado no checksum, promovidos antes de uma interrupção e ainda sem associação SQLite, são recuperados e associados sem nova requisição HTTP. Falhas transitórias usam backoff exponencial de 60 segundos até 6 horas; HTTP 429 e respostas que enviam `Retry-After` respeitam o maior entre o backoff local e o intervalo informado pelo servidor. Respostas permanentes recebem cooldown de 24 horas e são reavaliadas indefinidamente enquanto a URL continuar necessária; não existe limite finito de tentativas. O horário `next_retry_at` é a fonte de verdade: depois do backfill no bootstrap do processo, o serviço restaura o agendamento em memória a partir do timestamp persistido; uma reinicialização seguinte reconstrói novamente os agendamentos. O backfill também remove arquivos `.tmp` abandonados sem tocar nos temporários pertencentes a downloads ativos. O cache continua limitado a 128 MiB e não remove arquivos referenciados por `artwork.local_path` ou `anime.cover_cache`.

## Contrato canônico de imagens (Prompt 2/10)

A classificação interna usa exclusivamente os tipos **poster**, **backdrop** e **banner**, persistidos no campo `artwork.artwork_type`. Cada candidato conserva provedor, ID externo, campo original da resposta, URL, variante/resolução e prioridade; dimensões conhecidas podem gerar um aviso sem rejeitar nem reclassificar a imagem. A chave de identidade inclui o tipo, e as consultas/atualizações do escritor continuam limitadas a `entity_type + entity_id + artwork_type`.

Mapeamento explícito dos adapters atuais:

- **AniList:** `coverImage` → Poster; `bannerImage` → Banner. A API descreve `bannerImage` como banner, portanto ele não é encaminhado como Backdrop. O candidato Banner é gravado na tabela `artwork`.
- **Kitsu:** `posterImage` → Poster. `coverImage` é mantido no DTO como campo original não classificado: a documentação pública consultada enumera a propriedade, mas não define com precisão que ela seja Backdrop ou Banner. Não se infere seu tipo pela proporção.
- **AniPub:** `ImagePath` → Poster; `Cover` → Banner, conforme a referência do cliente/API mantido pelo projeto AniPub. Nenhum dos dois é promovido a Backdrop.
- **Jikan:** as variantes WebP/JPG em `images` fornecem Poster. O adapter não cria Backdrop ou Banner.
- **AniMap:** permanece exclusivamente um resolvedor de identificadores e não produz candidatos de imagem.

Compatibilidade legada: `anime.cover_url` continua sendo a URL de Poster; `anime.banner_url` continua sendo o ponteiro legado de Backdrop. O nome histórico não é reinterpretado como Banner. Novos Banner são registros independentes na tabela `artwork`; nenhuma migração, limpeza ou reprocessamento de imagens é necessária para este contrato.

Referências de API consultadas: [AniList Media](https://docs.anilist.co/reference/object/media), [Kitsu API](https://kitsu.docs.apiary.io/reference/posts), [AniPub API/client reference](https://github.com/AnimePub/AniPub-API).
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
