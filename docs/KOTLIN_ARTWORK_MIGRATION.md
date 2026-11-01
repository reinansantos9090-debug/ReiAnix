# Contrato nativo de artwork para Android/Kotlin — Prompt 2/10 e 2.1/10

## Arquitetura implementada

`ReiAnixArtworkService` é o produtor persistente do caminho Compose. A instância é criada de forma lazy por `ReiAnixAppDependencies` e recebe a mesma `ReiAnixSqliteDatabase` usada pela biblioteca. A implementação utiliza a tabela existente `artwork` e os campos de `anime`; não cria outra base, repositório de cache ou tabela, nem altera o schema v29.

O serviço cobre materialização de Poster, Backdrop e Banner a partir de URLs já persistidas, consulta dos registros locais, gravação de resultados, tentativas de recuperação e publicação de eventos. O backfill é iniciado no host Compose quando o catálogo SQLite está saudável e também após a conclusão de uma varredura; portanto, reabrir o app não depende de um novo scan para recuperar posters pendentes. O backfill só processa URLs persistidas e não inicia matching de títulos nem consultas AniList adicionais. Os downloads têm concorrência limitada. A varredura e o player não aguardam pela conclusão do pipeline.

## Download e cache de imagens

O download usa `HttpURLConnection` com as verificações TLS padrão do Android. O fluxo rejeita códigos HTTP não bem-sucedidos, conteúdo declarado como não-imagem, respostas vazias, assinaturas não reconhecidas, redirecionamento de HTTPS para HTTP e payload acima de 32 MiB. Os bytes são copiados em streaming para arquivo temporário, o arquivo é sincronizado e a imagem é validada por assinatura/decodificação. O destino usa nome baseado no checksum para não sobrescrever capas existentes. A promoção tenta um rename no mesmo diretório (atômico nos filesystems compatíveis); em API 24–25 ou quando rename falha, copia para o destino único, valida a cópia e só então persiste seu caminho no SQLite.

A escrita persistente acontece depois da promoção e em transação na tabela `artwork`. O ponteiro `anime.cover_cache` só é atualizado se a URL atual ainda corresponder ao registro e não houver propriedade manual do campo. Uma capa local/manual é preservada e uma falha remota não limpa `cover_cache`. Falhas de transporte, timeout e HTTP 408/429/5xx recebem `retry_wait` com horário persistido; respostas permanentes (por exemplo HTTP 404 ou conteúdo inválido) ficam `failed` com cooldown persistido de reavaliação. Nenhuma falha necessária é bloqueada por um limite total de tentativas.

As chaves de cache nativas preservam o formato SHA-256 previamente utilizado pelo ArtworkEngine: provedor, identidade do item/URL, tipo e variante. Arquivos válidos já associados são mantidos sem download; arquivos completos com nome baseado no checksum, promovidos antes de uma interrupção e ainda sem associação SQLite, são recuperados e associados sem nova requisição HTTP. Falhas transitórias usam backoff exponencial de 60 segundos até 6 horas; HTTP 429 e respostas que enviam `Retry-After` respeitam o maior entre o backoff local e o intervalo informado pelo servidor. Respostas permanentes recebem cooldown de 24 horas e são reavaliadas indefinidamente enquanto a URL continuar necessária; não existe limite finito de tentativas. O horário `next_retry_at` é a fonte de verdade: depois do backfill no bootstrap do processo, o serviço restaura o agendamento em memória a partir do timestamp persistido; uma reinicialização seguinte reconstrói novamente os agendamentos. O backfill também remove arquivos `.tmp` abandonados sem tocar nos temporários pertencentes a downloads ativos. O cache continua limitado a 128 MiB e não remove arquivos referenciados por `artwork.local_path` ou `anime.cover_cache`.

## Contrato canônico de imagens

O contrato interno usa três tipos independentes: **Poster**, **Backdrop** e **Banner**, identificados por `ReiAnixCatalogArtworkType` e persistidos em `artwork.artwork_type`. A semântica vem do campo declarado pelo provider, nunca de proporção, resolução, dimensões ou aparência. Dimensões podem emitir diagnóstico de proporção, mas não rejeitam nem reclassificam um candidato.

Cada `ReiAnixCatalogArtworkCandidate` preserva provider, ID externo, tipo, URL, nome do campo original, variante/resolução, prioridade e tipo de entidade. O vínculo com o ID local do anime só é aplicado depois da verificação da identidade externa. Campos cuja semântica não seja comprovada permanecem em `unclassifiedArtworkFields` e não chegam à persistência tipada.

### Mapeamento dos providers

- **AniList:** `coverImage` → Poster; `bannerImage` → Banner. Nenhum dos campos é tratado como Backdrop.
- **Kitsu:** `posterImage` → Poster; `coverImage` → Banner. A especificação REST oficial descreve `coverImage` como o banner exibido no topo da página da mídia; o contrato GraphQL oficial expõe o mesmo atributo como `banner_image`, resolvido por `cover_image`, com a descrição “A large banner image for this media”. O adapter preserva os nomes originais dos dois campos. `coverImage` não é Backdrop.
- **AniPub:** `ImagePath` → Poster; `Cover` → Banner, conforme a referência da API/cliente mantida pelo projeto AniPub.
- **Jikan:** as variantes escolhidas em `images.webp`/`images.jpg` → Poster. O adapter não emite Backdrop nem Banner.
- **AniMap:** resolvedor de identificadores somente; não produz artwork.

**Backdrop:** permanece um tipo válido e independente no contrato, mas os adapters listados acima não promovem campos a Backdrop sem semântica explícita documentada. O fato de um recurso se chamar “cover” não é suficiente para classificá-lo como Backdrop.

Referências oficiais/técnicas consultadas para o mapeamento Kitsu:
- [Kitsu OpenAPI — schema de Media](https://github.com/hummingbird-me/api-docs/blob/c95e259bec851f4bddf13c7bce3dd2f08eba7d19/api/schemas/resources/media.yml): descreve `coverImage` como “Banner displayed at the top of the media page” e documenta `posterImage` separadamente.
- [Kitsu GraphQL — interface Media](https://github.com/hummingbird-me/kitsu-server/blob/e6575ed9fd73ba8cccb920fe2e3f1ef873a71333/app/graphql/types/interface/media.rb): define `poster_image` como poster e `banner_image` como banner grande, com `method: :cover_image`.
- [Kitsu REST — MediaResource](https://github.com/hummingbird-me/kitsu-server/blob/e6575ed9fd73ba8cccb920fe2e3f1ef873a71333/app/resources/media_resource.rb): expõe `poster_image` e `cover_image` como atributos distintos do mesmo recurso de mídia.

## Persistência e compatibilidade SQLite v29

A integração externa percorre `artworkCandidates()` somente depois de validar a correspondência entre o registro remoto e a entidade local. Cada candidato é persistido pelo `ReiAnixArtworkService` com escopo por `entity_type + entity_id + artwork_type`; origem, ID externo/campo original e variante permanecem na referência/chave do candidato. A chave de identidade inclui também tipo e variante, impedindo colisões entre Poster, Backdrop e Banner.

Os ponteiros legados mantêm a semântica histórica:
- `anime.cover_url` e `anime.cover_cache` representam Poster.
- `anime.banner_url` representa Backdrop, apesar do nome legado da coluna.
- Banner é armazenado como registro independente em `artwork.artwork_type='banner'`; nunca é copiado para `anime.banner_url`.

As proteções de propriedade manual e arte local válida continuam ativas. Atualizar um tipo não substitui registros de outro tipo. O contrato utiliza a tabela `artwork` já existente e mantém a versão `29` em `schema_migrations.version`; o `PRAGMA user_version` permanece no valor canônico `0` definido pelo asset (`sqlite_user_version`). Esta etapa não adiciona tabela, migração, banco paralelo nem rotina de limpeza. Registros, URLs legadas e arquivos de cache existentes são preservados.

## Integração Android/Kotlin

O launcher declarado no Manifest é `MainActivity : ComponentActivity`. O runtime Android usa Kotlin, Jetpack Compose, os serviços/repositórios nativos e a mesma instância de `ReiAnixSqliteDatabase` compartilhada pela biblioteca. `ReiAnixArtworkService` é o escritor canônico dos candidatos externos; Coil atua como renderer, não como persistência alternativa.

A integração AniList encaminha Poster e Banner por campos distintos do patch SQLite. `coverImage` do AniList permanece Poster e `bannerImage` permanece Banner. O patch não usa o campo legado `anime.banner_url` para gravar Banner e respeita os campos protegidos por edição manual.

## Limites deste contrato

Thumbnails de episódios são um recurso separado (`entity_type=episode`, `artwork_type=episode_thumbnail`) e não são Poster, Backdrop ou Banner. O Prompt 2.1 não altera extrator, miniaturas, player, scanner, downloader, política de tentativas, matcher ou interface.

## Histórico da migração — referência histórica, não runtime atual

Versões anteriores desta documentação descreviam uma etapa híbrida em que eventos Compose eram encaminhados por NativeMailbox para `main.py`, havia consumidores Flet e o caminho Python podia atuar como compatibilidade. Esses registros são mantidos aqui para explicar a transição histórica e **não descrevem a arquitetura Android atual**. Não devem ser reintroduzidos como dependências do aplicativo.

No estado atual desta branch, `main.py` e `requirements.txt` não existem na raiz; o Manifest inicia `MainActivity`, cuja classe estende `ComponentActivity`; o módulo Android aplica os plugins nativos de Android e Kotlin e não depende de Flutter/Flet/Python para iniciar ou executar o app. O workflow Kotlin/JVM também valida explicitamente a ausência de `main.py`. A existência de documentos históricos com esses termos não implica runtime híbrido.

## Testes e workflow Kotlin/JVM

O workflow `.github/workflows/kotlin_jvm_tests.yml` é disparado exclusivamente por `workflow_dispatch`; esta documentação não o transforma em execução automática por push ou pull request. A suíte executa testes host-side com Robolectric/JVM, prepara uma fixture temporária do contrato canônico SQLite v29 e remove essa fixture ao final. Não executa testes de emulador nem instrumentados.

A suíte seleciona os testes de adapters AniList/Kitsu/AniPub/Jikan, contratos, coordenador/resolvedor e regressões SQLite de integração/persistência. A presença de seletores no YAML não é prova de execução: o resultado só pode ser informado após uma execução real do workflow chegar a estado terminal, com os relatórios JUnit correspondentes.

