# Migração do subsistema de artwork para Kotlin — Prompt 07/18

## Arquitetura implementada

`ReiAnixArtworkService` é o produtor persistente do caminho Compose. A instância é criada de forma lazy por `ReiAnixAppDependencies` e recebe a mesma `ReiAnixSqliteDatabase` usada pela biblioteca. A implementação utiliza a tabela existente `artwork` e os campos de `anime`; não cria outra base, repositório de cache ou tabela, nem altera o schema v29.

O serviço cobre materialização de poster e backdrop a partir de URLs já persistidas, consulta dos registros locais, gravação de resultados, tentativas de recuperação e publicação de eventos. O backfill é iniciado pelo ViewModel nativo em lotes pequenos e os downloads têm concorrência limitada. A varredura e o player não aguardam pela conclusão do pipeline.

## Download e cache de imagens

O download usa `HttpURLConnection` com as verificações TLS padrão do Android. O fluxo rejeita códigos HTTP não bem-sucedidos, conteúdo declarado como não-imagem, respostas vazias, assinaturas não reconhecidas, redirecionamento de HTTPS para HTTP e payload acima de 32 MiB. Os bytes são copiados em streaming para arquivo temporário, o arquivo é sincronizado e a imagem é validada por assinatura/decodificação. O destino usa nome baseado no checksum para não sobrescrever capas existentes. A promoção tenta um rename no mesmo diretório (atômico nos filesystems compatíveis); em API 24–25 ou quando rename falha, copia para o destino único, valida a cópia e só então persiste seu caminho no SQLite.

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

---

## Revisão do Prompt 07/18 — auditoria e ajustes de segurança (2026-10-19)

### Estado de referência

- Repositório: `reinansantos9090-debug/ReiAnix`.
- Branch de trabalho: `migration/prompt-07-native-artwork-20261019`.
- Base observada: `main`, com o merge `4afa6c7699458d9c856becb7b4a0f559cec22946` (Prompt 06.1/18).
- SQLite canônico: `library.sqlite3`, schema v29, validado por `ReiAnixSqliteDatabase.EXPECTED_SCHEMA_VERSION`. Esta etapa não cria banco, tabela nem migração de schema.
- Biblioteca de imagens: Coil já está presente no caminho Compose; esta etapa não introduz outra biblioteca.
- Limite da migração: o fluxo Compose já materializa capas e publica atualizações pelo Kotlin. `core/artwork.py`, `main.py` e telas Flet continuam presentes para consumidores legados legítimos; o runtime global, portanto, não é 100% Kotlin.

### Matriz de componentes

| Componente | Implementação e consumidores observados | Armazenamento / identidade | Cache / dependência Python | Comportamento preservado e alteração desta revisão | Risco / evidência |
|---|---|---|---|---|---|
| Posters e capas remotas | `ReiAnixArtworkService.kt`; renderização por `ReiAnixLocalArtwork.kt`, `ReiAnixPoster` e consumidores Compose (Home, Library, Details, Search e My List conforme o mapeamento existente). | Tabela canônica `artwork`, campos `anime.cover_url` e `anime.cover_cache`; chave SHA-256 com provedor/identidade/URL/tipo/variante; arquivos em `filesDir/data/covers/artwork`. | Download Kotlin limitado e validado; o ArtworkEngine Python permanece disponível para telas Flet legadas. | Mantidos chave estável, validação de bytes, gravação antes da publicação, proteção de campos manuais e preservação da capa válida durante falha remota. A limpeza agora só limpa um ponteiro de capa quando ele corresponde a um caminho de arte remota removido; campos manuais e referências locais/manual são preservados. | Fonte: `ReiAnixArtworkService.kt`, `ReiAnixLocalArtwork.kt`, `ReiAnixSqliteDatabase.kt`; contratos em `tests/test_kotlin_artwork_migration_contract.py`. |
| Banners e backdrops | `ReiAnixArtworkService.requestAnimeArtwork`; componentes de arte Compose existentes. | `artwork` e URL canônica `anime.banner_url`; cache sob o diretório gerenciado existente. | Mesmo serviço e limite de concorrência do poster; não há downloader Compose adicional. | Mantidos checagem de URL atual, gravação validada, preservação de imagem anterior e evento pós-persistência. | Falha de evento não deve ser confundida com falha de download; a verificação final depende do workflow e dos testes executados. |
| Thumbnails de vídeo | `VideoThumbnailExtractor.kt` via comandos nativos existentes em `MainActivity.kt`/dispatcher; registro por `ReiAnixArtworkService.registerGeneratedThumbnail`; eventos `thumbnail_ready` e `ARTWORK_PUBLISHED`. | Linhas `artwork(entity_type='episode', artwork_type='episode_thumbnail')`; identidade baseada em media identity/URI, tamanho, modificação e variante `small`. | Extração Kotlin por `MediaMetadataRetriever`; o fallback Python permanece somente quando o runtime Flet legado é o consumidor. | O destino passou de `cacheDir/reiflix/thumbnails` (descartável pelo Android) para `filesDir/data/covers/artwork/thumbnails`. Cache hits antigos são promovidos por arquivo temporário, `fsync`, validação e rename; o limitador de cache não exclui imagem ainda referenciada no SQLite. | O limite de tamanho é conservador: quando todos os arquivos restantes têm referências persistidas, o trim não os remove e pode exceder o orçamento nominal. Isso é preferível a deixar linhas SQLite apontando para arquivos apagados. |
| Persistência e integridade | `ReiAnixSqliteDatabase` e métodos de transação do serviço nativo. | Banco e schema v29 já existentes; não há base alternativa. | `cover_cache` é ponteiro compatível legado, não uma nova fonte de dados. | Atualizações de arte não reescrevem progresso, listas pessoais, caminhos, IDs ou os demais campos da biblioteca. A consulta `isPathReferenced` verifica caminhos absolutos/canônicos em `artwork.local_path` e `anime.cover_cache`. | Arquivos locais inacessíveis continuam sujeitos a fallback/placeholder visual; falhas de storage não devem apagar metadados principais. |
| Publicação e Compose | `NativeMailbox`, evento `artwork_published`, evento `thumbnail_ready`, `ReiAnixLocalArtwork` e mapeadores SQLite. | Evento contém ID/tipo, chave e caminho da imagem já persistida; a projeção consome a base canônica. | Coil usa chaves de memória/disco estáveis; nenhuma segunda biblioteca de imagens foi adicionada. | O evento de sucesso ocorre depois de persistir a imagem; arte local tem prioridade sobre URL externa; erro de candidato permite avançar para fallback/placeholder. | Consumidores Flet recebem compatibilidade enquanto ainda existirem; não foram removidos bridges nem Python globalmente. |
| Artwork legado Python | `core/artwork.py`, listeners em `main.py` e telas Flet, incluindo consumidores em Home/Details. | Usa o contrato de cache existente da implementação legada e a base da biblioteca. | Dependência Python/Pillow mantida para rotas Flet; no caminho Compose, a materialização canônica ocorre em Kotlin. | O código legado não foi apagado nem apresentado como totalmente migrado. | Migração global continua parcial até os consumidores Flet remanescentes e seus contratos serem removidos ou portados em fases posteriores. |

### Arquivos alterados nesta revisão

- `android/app/src/main/kotlin/com/reiflix/reiflix_local/data/artwork/ReiAnixArtworkService.kt`: limpeza preserva ponteiros manuais/locais e expõe verificação segura de referências a caminhos.
- `android/app/src/main/kotlin/com/reiflix/reiflix_local/storage/VideoThumbnailExtractor.kt`: armazenamento durável de thumbnails, recuperação por promoção do cache antigo e proteção de arquivos referenciados durante trim.
- `tests/test_kotlin_artwork_migration_contract.py`: contratos para preservação de referências manuais/locais, caminho durável e prevenção de eviction de arquivos referenciados.
- `tests/test_compose_settings_preferences.py`: expectativa antiga atualizada para não exigir a limpeza global de `cover_cache`; verifica a nova invalidação segura e a preservação de campos manuais/locais.
- `docs/KOTLIN_ARTWORK_MIGRATION.md`: esta matriz e registro de escopo.

### Validação desta revisão

- Não foi executado teste Kotlin/JVM manualmente; esta etapa respeita a restrição de testes do prompt.
- Não foram adicionados testes instrumentados/de emulador, workflows novos nem execução automática adicional.
- A primeira execução de CI (`Build ReiAnix Android APK`, run 6191) executou `pytest`: 1.972 passaram, 58 subtests passaram e 1 teste falhou porque ainda exigia a limpeza global destrutiva de `cover_cache`. O contrato do teste foi atualizado sem remover cobertura.
- A execução seguinte (run 6192) estava em andamento no momento desta nota; o resultado final de compilação, testes e workflows deve ser reportado a partir do commit final da PR.
- A migração do caminho Compose é nativa, mas a migração completa de todos os consumidores Python/Flet permanece pendente; a etapa não será declarada integralmente concluída até essa fronteira ser resolvida e os workflows relevantes terminarem.

### Correções adicionais após revisão do Prompt 07

- `VideoThumbnailExtractor.promoteLegacyCache` agora preserva um destino válido e permite substituir um destino vazio/corrompido somente depois de copiar, sincronizar e validar a imagem legada. O destino é revalidado imediatamente antes da substituição; a imagem legada não é apagada.
- `ReiAnixArtworkService.isPathReferenced` canonicaliza tanto o caminho consultado quanto cada referência persistida em `artwork.local_path` e `anime.cover_cache`. Referências que não possam ser interpretadas com segurança fazem a consulta falhar de forma conservadora, impedindo a exclusão do arquivo.
- Os contratos de regressão verificam esses dois comportamentos e a ordem da validação antes da substituição.
- A compilação e regressão do SHA final desta correção devem ser confirmadas pelo workflow oficial. A etapa continua **PARCIAL**; não foram executados testes Kotlin/JVM ou instrumentados manualmente, nem foi feito merge.
