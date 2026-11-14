# Relatório técnico — recuperação de posters e preservação de thumbnails

**Projeto:** ReiAnix  
**Repositório:** https://github.com/reinansantos9090-debug/ReiAnix  
**Branch examinada:** `main`  
**Commit-base:** `ab4d668b1c05c938344e76fa433e7cadada3e240`  
**Último commit incluído neste relatório:** `ef6e65718a9d39887c5130ef661e5aa3044f37c0`  
**Data:** 25/10/2026  
**Estado:** alterações publicadas; validação de build/execução ainda pendente.

## 1. Resumo executivo

Foram publicados cinco commits depois do commit-base. Quatro tratam da recuperação de artwork e dos testes/documentação relacionados; o quinto amplia o workflow de certificação para compilar e gerar o APK de release e guardar os resultados como artefatos.

A implementação procura recuperar posters/backdrops cujas URLs já estão persistidas, inclusive registros legados marcados como falhos, sem criar outra base de dados e sem alterar o esquema SQLite v29. A mudança no host Compose solicita backfill quando o catálogo está pronto e saudável, além do caminho já existente após a varredura.

**Não é possível declarar a correção definitivamente concluída:** não foi obtida uma execução confirmada do GitHub Actions, os testes/build não foram executados localmente nesta sessão e não houve validação visual no dispositivo. Os testes adicionados são código de teste publicado, não evidência de que passaram.

## 2. Commits publicados

A comparação de GitHub entre o commit-base e o HEAD observado retornou cinco commits à frente e nenhum atrás.

1. [c5f0a4e — Retry failed anime artwork with bounded recovery](https://github.com/reinansantos9090-debug/ReiAnix/commit/c5f0a4eab7e81005af38025ac778eaefa5e0f4c4)  
   Introduz a política de recuperação de artwork falho com limites de retry.

2. [1bdfa30 — Recover pending posters when the native catalog opens](https://github.com/reinansantos9090-debug/ReiAnix/commit/1bdfa306d017942ec20cb565dacef8663116ddd3)  
   Liga o backfill dos posters pendentes à abertura da biblioteca nativa quando o bootstrap e o catálogo estão saudáveis.

3. [bfd2a28 — Test bounded recovery for failed artwork rows](https://github.com/reinansantos9090-debug/ReiAnix/commit/bfd2a281d0c0322d6310ceafa33e0a9e8639cbd4)  
   Adiciona testes unitários para os estados de retry e recuperação.

4. [3984b01 — Document persistent poster recovery and retry limits](https://github.com/reinansantos9090-debug/ReiAnix/commit/3984b01db43e995009df08f0757c4e3925b68078)  
   Atualiza a documentação do subsistema de artwork.

5. [ef6e657 — Build and retain release APK during certification](https://github.com/reinansantos9090-debug/ReiAnix/commit/ef6e65718a9d39887c5130ef661e5aa3044f37c0)  
   Atualiza o workflow para executar `:app:assembleRelease` e incluir o APK de release nos artefatos da certificação.

## 3. Arquivos alterados

A comparação entre o commit-base e o commit `ef6e657` identificou cinco arquivos modificados:

| Arquivo | Alteração observada | Finalidade |
|---|---:|---|
| `.github/workflows/release_certification.yml` | +5 / -3 linhas | Executar montagem do APK de release e guardar o APK/log/resultados de teste como artefatos. |
| `android/app/src/main/kotlin/com/reiflix/reiflix_local/data/artwork/ReiAnixArtworkService.kt` | +100 / -13 linhas | Retry de posters, backoff, fila de pedidos forçados e regras de elegibilidade. |
| `android/app/src/main/kotlin/com/reiflix/reiflix_local/ui/host/ReiAnixComposeLibraryHost.kt` | +27 linhas | Solicitar backfill na abertura quando o bootstrap está pronto e o SQLite está saudável. |
| `android/app/src/test/kotlin/com/reiflix/reiflix_local/data/artwork/ReiAnixArtworkPublicationSignalTest.kt` | +79 linhas | Testar elegibilidade de retry e contratos auxiliares de artwork. |
| `docs/KOTLIN_ARTWORK_MIGRATION.md` | +2 / -2 linhas | Atualizar a descrição de retry/backfill e os limites da migração. |

O diff agregado desses cinco arquivos foi de 213 adições e 18 remoções. Essa contagem é a soma dos números reportados pela comparação de commits; não equivale a linhas de código executadas ou aprovadas por teste.

## 4. Alterações funcionais no serviço de artwork

### 4.1 Política de retry

O serviço agora declara `MAX_AUTOMATIC_ARTWORK_FAILURES = 5` e `PERMANENT_ARTWORK_RETRY_DELAY_SECONDS = 24 horas`.

A função `shouldRetryArtworkRequest` implementa as seguintes regras:

- Um pedido explicitamente forçado pode tentar novamente mesmo quando o limite automático foi atingido.
- Um registro legado com status `failed` e sem `next_retry_at` pode voltar à fila.
- Um registro `retry_wait` aguarda a data persistida antes de nova tentativa automática.
- O retry automático é bloqueado quando a contagem de falhas alcança cinco.
- A regra de elegibilidade considera o status, a data de retry, a contagem de falhas e o parâmetro `force`.

### 4.2 Tentativas transitórias e permanentes

Falhas de rede e respostas HTTP 429/5xx usam `retry_wait`, backoff exponencial limitado a seis horas e agendamento no processo atual enquanto ainda houver tentativas automáticas disponíveis.

Falhas classificadas como permanentes ficam com status `failed` e recebem cooldown de 24 horas para permitir uma tentativa em uma abertura posterior do aplicativo. O agendamento no processo atual é explicitamente aplicado ao caminho transitório; a recuperação de falhas permanentes depende do backfill posterior após o cooldown ou de um pedido forçado.

### 4.3 Pedidos concorrentes e limpeza de cache

O serviço mantém jobs por anime, controla concorrência de downloads com `Semaphore(2)`, mantém jobs de retry e registra IDs para pedidos forçados de seguimento. Se chegar um pedido forçado enquanto outro estiver ativo para o mesmo anime, ele é marcado para nova tentativa após o job ativo.

A limpeza do cache cancela os retries pendentes e limpa os pedidos forçados pendentes antes de remover os dados gerenciados. A limpeza não deve remover arquivos referenciados pela tabela `artwork` ou por `anime.cover_cache`.

### 4.4 Persistência e integridade de imagem

A documentação e a implementação preexistentes descrevem validação de conteúdo de imagem, download para arquivo temporário, promoção para caminho local e associação persistida no catálogo. A implementação mantém a tabela canônica `artwork` e os campos existentes de `anime`; não foi identificado um pedido de criação de banco/tabela ou migração de schema nos cinco commits examinados.

## 5. Integração com a biblioteca Compose

O arquivo `ReiAnixComposeLibraryHost.kt` adiciona um `LaunchedEffect` condicionado a:

- fase do bootstrap igual a `READY`;
- status de persistência igual a `HEALTHY`;
- biblioteca com animes carregados.

Quando as condições são atendidas, chama `ReiAnixAppDependencies.from(activity.applicationContext).artworkService.requestPendingArtwork()`. Erros são registrados no log, sem derrubar explicitamente a composição.

O caminho de pós-varredura já solicita backfill; o novo caminho procura impedir que o retry dependa exclusivamente de uma nova varredura. O backfill descrito não faz matching de títulos nem consulta AniList por conta própria: processa URLs já persistidas.

## 6. Testes adicionados

O arquivo `ReiAnixArtworkPublicationSignalTest.kt` contém testes para:

- permitir retry de registro legado `failed` sem timestamp;
- adiar `retry_wait` até o horário persistido;
- impedir retry automático após cinco falhas e permitir pedido forçado;
- permitir nova elegibilidade após o cooldown de falha permanente;
- criar o diretório de cache quando ausente;
- rejeitar publicação de artwork sem associação persistida ou arquivo existente;
- preservar caminhos de thumbnail utilizáveis ao selecionar caminhos inválidos;
- observar um evento de publicação de artwork após o consumidor iniciar.

**Resultado de execução:** não confirmado. Os casos acima foram verificados por inspeção do arquivo de teste; não há resultado de execução disponível nesta investigação.

## 7. Preservação do SQLite v29 e thumbnails de episódios

Os commits examinados não alteram diretamente o extrator de thumbnails de episódios. A comparação de arquivos mostra alterações no serviço de artwork, no host Compose, em um arquivo de teste, na documentação e no workflow.

A documentação do projeto descreve o uso do catálogo SQLite v29 já existente, a associação de thumbnails geradas à tabela `artwork` e a preservação de duração canônica positiva. Contudo, como a suíte de regressão ainda não foi executada, a preservação do comportamento precisa ser confirmada pelos testes de SQLite, `VideoThumbnailExtractorTest`, `VideoThumbnailFileValidationTest` e testes de UI/modelos incluídos no workflow.

## 8. Comparação de código com projetos de referência

Foram inspecionados arquivos dos repositórios públicos abaixo. A comparação foi limitada aos arquivos acessíveis nesta sessão; não constitui auditoria exaustiva de cada projeto.

- **CloudStream:** [ImageModuleCoil.kt](https://github.com/recloudstream/cloudstream/blob/master/app/src/main/java/com/lagradost/cloudstream3/utils/ImageModuleCoil.kt). Referência para configuração compartilhada de loader e cache de imagens.
- **Aniyomi:** [AnimeImageFetcher.kt](https://github.com/aniyomiorg/aniyomi/blob/main/app/src/main/java/eu/kanade/tachiyomi/data/coil/AnimeImageFetcher.kt), [AnimeCoverKeyer.kt](https://github.com/aniyomiorg/aniyomi/blob/main/app/src/main/java/eu/kanade/tachiyomi/data/coil/AnimeCoverKeyer.kt) e [AnimeCoverCache.kt](https://github.com/aniyomiorg/aniyomi/blob/main/app/src/main/java/eu/kanade/tachiyomi/data/cache/AnimeCoverCache.kt). O código separa covers personalizados, arquivos locais, cache específico da biblioteca e rede; as chaves de cache refletem a identidade da URL/entidade.
- **Animiru:** [AnimeImageFetcher.kt](https://github.com/animiruorg/Animiru/blob/animiru-new-main/app/src/main/java/eu/kanade/tachiyomi/data/coil/AnimeImageFetcher.kt), [AnimeCoverKeyer.kt](https://github.com/animiruorg/Animiru/blob/animiru-new-main/app/src/main/java/eu/kanade/tachiyomi/data/coil/AnimeCoverKeyer.kt) e [CoverCache.kt](https://github.com/animiruorg/Animiru/blob/animiru-new-main/app/src/main/java/eu/kanade/tachiyomi/data/cache/CoverCache.kt). O código consultado inclui cache específico de covers e deduplicação de requisições concorrentes.
- **Anikku:** [MangaCoverKeyer.kt](https://github.com/komikku-app/anikku/blob/master/app/src/main/java/eu/kanade/tachiyomi/data/coil/MangaCoverKeyer.kt). A chave de fallback usa uma identidade por item quando a URL está ausente, evitando que vários itens sem capa compartilhem a mesma chave de cache.

### Conclusão comparativa

As referências reforçam que o resultado visual depende não apenas do download, mas também da chave de cache, da origem, do caminho local e da invalidação correta. A mudança do ReiAnix abordada aqui concentra-se na recuperação persistente de registros pendentes/falhos. Ela não prova, por si só, que toda causa possível de poster preto — por exemplo, URL inválida, imagem remota indisponível ou erro de renderização — foi eliminada.

## 9. Workflow de certificação

O workflow [release_certification.yml](https://github.com/reinansantos9090-debug/ReiAnix/blob/main/.github/workflows/release_certification.yml) é acionado por push em `main` e por `workflow_dispatch`. Ele configura Java 17, Android SDK API 36 e Gradle 8.11.1, cria uma fixture temporária do contrato SQLite v29 e seleciona testes de regressão.

A lista selecionada inclui testes de compatibilidade/migração do SQLite, `VideoThumbnailExtractorTest`, `VideoThumbnailFileValidationTest`, `ReiAnixLocalArtworkTest`, `ReiAnixArtworkPublicationSignalTest`, testes de mapeamento de duração/artwork e projeção de detalhes. O comando também executa `:app:compileReleaseKotlin` e, após a alteração do quinto commit, `:app:assembleRelease`.

O passo de upload procura guardar o log de certificação, os resultados XML/HTML dos testes e `android/app/build/outputs/apk/release/*.apk`, com retenção de sete dias.

**Estado da execução:** as consultas de status de commit não retornaram verificações e a consulta de execuções não forneceu uma execução verificável para o commit mais recente. Isso não demonstra sucesso nem falha. A execução e seus artefatos precisam ser confirmados no [GitHub Actions](https://github.com/reinansantos9090-debug/ReiAnix/actions/workflows/release_certification.yml).

## 10. Riscos e pendências

1. **Compilação não confirmada:** o novo uso de coroutines, tipos e integração do host ainda precisa passar pelo compilador Kotlin/Android.
2. **Testes não executados nesta sessão:** os testes adicionados não podem ser tratados como aprovados.
3. **Backfill em aparelho não observado:** ainda não há evidência de que posters existentes sejam baixados, persistidos e renderizados corretamente no dispositivo.
4. **Causas fora do alcance do backfill:** registros sem URL de origem persistida não podem ser recuperados apenas repetindo a fila.
5. **Falhas permanentes:** a reavaliação após o cooldown depende de um novo backfill; deve ser exercitada na validação funcional.
6. **Regressão de thumbnails:** a ausência de alterações diretas no extrator reduz o escopo do diff, mas a regressão só fica excluída após os testes correspondentes e a verificação visual.
7. **Comparação parcial:** os arquivos de referência consultados mostram padrões de implementação, mas não foi feita uma equivalência funcional completa entre os quatro aplicativos.

## 11. Plano de validação e critérios de aceite

A correção só deve ser marcada como concluída após:

1. confirmar uma execução verde do workflow no commit atual;
2. verificar os resultados XML dos testes e o log de Gradle;
3. confirmar a existência do APK de release nos artefatos;
4. instalar o APK em um dispositivo com a biblioteca já existente, sem apagar os dados do app;
5. verificar que posters previamente ausentes são recuperados após abrir a biblioteca;
6. confirmar que URLs indisponíveis/falhas não geram loop ilimitado de rede;
7. conferir que thumbnails de episódios continuam presentes e válidos, sem regressão de duração, catálogo SQLite ou schema v29;
8. coletar logs/contagens antes e depois da recuperação para demonstrar quantos registros pendentes foram resolvidos e quantos permaneceram falhos.

## 12. Veredito

**Parcialmente implementado, ainda não validado de ponta a ponta.** As alterações e o workflow foram publicados na branch `main`. Há testes novos no repositório e documentação técnica atualizada, mas não existe, no momento deste relatório, evidência verificável de build/testes aprovados nem validação visual no aparelho. Não se declara que todas as capas foram recuperadas nem que a regressão foi definitivamente eliminada.
