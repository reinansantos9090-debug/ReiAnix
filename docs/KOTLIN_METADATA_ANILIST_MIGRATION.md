# Migração de metadados, pesquisa e AniList para Kotlin nativo

> **Classificação de etapa:** este documento descreve AniList, pesquisa remota e metadados PT-BR e pertence ao escopo documental do Prompt 09/18. Foi criado no commit histórico `c563be50f9acd2106af944fd2a57292cc3d53eb9` e não implementou nem concluiu o scanner Kotlin do Prompt 06/18. A sequência histórica permanece preservada; esta nota apenas esclarece o escopo para a migração em andamento.


## Estado e escopo desta etapa

- Repositório: `reinansantos9090-debug/ReiAnix`.
- Branch de destino solicitada: `main`. Esta documentação foi preparada para registrar o estado do código encontrado, não para alegar que uma migração previamente existente começou do zero.
- Base de referência consultada antes desta alteração documental: `4349f7da54df48aaa64161d19fae70e8b1197184` (commit de documentação recente do repositório).
- A integração de metadados nativa já existe no snapshot consultado. Este Prompt 06 completa a documentação obrigatória e explicita fronteiras e limitações verificadas; ele não equivale à remoção integral do runtime Python/Flet.
- As declarações de compilação/testes abaixo distinguem testes presentes de testes realmente executados nesta alteração.

## Matriz de implementação

| Fonte / operação | Implementação atual | Consumidores principais | Contrato e persistência | Estado / pendência |
|---|---|---|---|---|
| Busca remota de anime | `ReiAnixAniListClient.searchAnime` | `ReiAnixAniListIntegrationService.search`, `ReiAnixAniListViewModel`, fluxo de associação em Details | `AniListResult<List<AniListAnime>>`; GraphQL com variáveis separadas; não grava linhas por conta própria | Implementada no fluxo Compose; clientes legados permanecem |
| Detalhe por ID externo | `ReiAnixAniListClient.getAnime` | atualização, associação manual e consulta por ID no serviço | `AniListResult<AniListAnime?>`; Media explicitamente nulo = sem resultado, campo ausente/inválido = falha | Implementada |
| Conversão de metadados | `AniListAnime` e parser `parseAnime` no cliente | serviço de integração e ViewModel | Campos tipados/opcionais: títulos, aliases, descrição, formato, estado, episódio, duração, ano/temporada, nota, gêneros, estúdios, capa/banner | Implementada; campos remotos inválidos são rejeitados |
| Correspondência título/aliases | `ReiAnixAniListMatcher` | `ReiAnixAniListIntegrationService.autoMatch` e reconciliação do catálogo | Pontuação ordenada de candidatos; alias/títulos alternativos; contexto de formato, temporada, ano e episódio | Implementada com limiar e margem; correspondências ambíguas não são confirmadas automaticamente |
| Associação manual/automática | `ReiAnixAniListIntegrationService` + `ReiAnixSqliteAniListAssociation.kt` | `ReiAnixAniListViewModel`, Details e reconciliação após scan | Escrita transacional na base canônica; IDs/flags da associação mantidos no contrato SQLite | Implementada no fluxo Compose; jornadas Flet continuam compatíveis |
| Localização de sinopse PT-BR | `ReiAnixAniListDescriptionLocalizer` | serviço de integração | Conserva `description_original`; texto localizado só é aceito após validações; falha de tradução usa o texto original disponível | Implementada com serviço MyMemory e cache compartilhado legado; disponibilidade PT-BR não é garantida para toda obra |
| Gêneros | resposta AniList + `ReiAnixGenreRegistry` | serviço de metadados, filtros e organização | Identidades e relações de gênero persistidas no mesmo SQLite canônico | Integrado; lista remota vazia não deve limpar relações já existentes |
| Capa/banner | URLs produzidas pelo AniList, `ReiAnixArtworkService` | componentes de arte Compose e serviço de artwork | Caminhos/cache persistidos na base canônica; evento `anilist_metadata_committed` não transporta resposta remota | A materialização do artwork tem serviço Kotlin no caminho nativo; `core/artwork.py` permanece para consumidores Flet legados |
| Preferências e configuração | `ReiAnixSqliteSettingsRepository` | serviço AniList | Chaves existentes `metadata.anilist_enabled`, `metadata.auto_match` e `artwork.enabled` | Reutilizadas; nenhuma autenticação nova é introduzida |
| Legado Python/Flet | `core/anilist.py`, `core/organizer_ai.py`, `core/library_service.py`, `core/artwork.py` | jornadas Flet/desktop e compatibilidade | Mantém os contratos Python enquanto ainda houver consumidores | Não removido nesta etapa; a migração total permanece parcial |

## Transporte HTTP e contrato GraphQL

- Endpoint AniList usado: `https://graphql.anilist.co`.
- O cliente Kotlin usa o transporte `AniListTransport` (implementação HTTP com `HttpURLConnection`) e executa I/O em `Dispatchers.IO`.
- As operações principais são busca paginada `Page(...).media(search, type: ANIME, sort: SEARCH_MATCH)` e consulta por ID `Media(id, type: ANIME)`.
- Os parâmetros são enviados em `variables`, sem interpolar o texto de pesquisa/ID no documento GraphQL.
- Os campos selecionados são os que a implementação existente consome: ID; títulos romaji/english/native; synonyms; descrição; formato; episódios; status; nota média; duração; temporada/ano; gêneros; coverImage large/extraLarge; bannerImage; e estúdios principais.
- O cliente separa falhas de transporte, HTTP, rate limit (incluindo Retry-After disponível), JSON malformado, payload GraphQL com `errors` e respostas de formato inesperado. Um HTTP 200 não torna uma resposta GraphQL com erros um sucesso.
- A resposta remota é convertida a `AniListAnime`; JSON cru não atravessa a fronteira até os ViewModels.
- Há serialização de requisições por instância e um intervalo mínimo configurado entre inícios de chamadas. Não há cache de pesquisa remoto separado: o catálogo materializado em SQLite é a persistência de metadados, enquanto o cache de tradução é apenas cache auxiliar.

## Regras de títulos, identificação e desambiguação

O matcher Kotlin existente porta regras do fluxo Python `core/organizer_ai.py`, em vez de comparar somente títulos por igualdade. A normalização usa NFKD/diacríticos, remove marcas comuns de release e contexto de episódio/temporada/ano antes do cálculo; o ranking também considera aliases e sinais de formato/temporada/ano.

Limites documentados no código:
- `MINIMUM_SCORE = 0.48`: limite inferior para candidatos relevantes.
- `AUTO_CONFIRM_SCORE = 0.86`: pontuação mínima para confirmação automática.
- `AUTO_CONFIRM_MARGIN = 0.05`: margem mínima entre o candidato superior e o próximo.

Uma associação existente ou manual tem precedência. A seleção automática exige pontuação e margem suficientes; do contrário, o resultado é apresentado como ambíguo/sem correspondência sem gravar um ID especulativo. A identidade local do anime/arquivo permanece separada do ID AniList. A numeração de episódio/temporada e os tokens de release são contexto para matching, não instrução para fundir registros locais.

## Idioma e precedência da sinopse

1. A sinopse remota original é preservada separadamente em `description_original`.
2. A descrição localizada em PT-BR só é aceita se não estiver vazia, passar pela validação de tradução e for reconhecida como PT-BR pelo validador existente.
3. Se não houver tradução válida ou o serviço externo falhar, a descrição original disponível continua sendo o fallback; o serviço não fabrica resumo/tradução.
4. Se a API não fornecer descrição, o update não usa ausência como ordem para apagar uma descrição local válida.
5. Os títulos romaji, inglês, japonês/nativo e synonyms permanecem disponíveis para pesquisa e correspondência, sem substituir os títulos originais por uma tradução presumida.

O localizer usa `https://api.mymemory.translated.net/get` para a localização legada, com timeouts, cancelamento e limite configurado de 480 bytes UTF-8 por trecho; o cache em `filesDir/data/covers/anilist_description_ptbr.json` é compartilhado com o caminho compatível existente, limitado a 4096 entradas. Esse arquivo não é banco canônico nem autoridade para identidade local.

## Persistência e proteção do catálogo

- Banco de metadados: a base SQLite canônica reutilizada por `ReiAnixSqliteLibraryRepository` e `ReiAnixAppDependencies`; a implementação/documentação existente identifica o contrato de schema como v29.
- Não foi adicionado segundo banco, repositório de metadados concorrente nem migração de schema por esta etapa.
- `updateAnimeMetadata` e `updateAniListMetadataAndAssociation` são os limites de escrita existentes. A atualização de associação e os campos editoriais elegíveis é transacional.
- Campos remotos ausentes/nulos/vazios não devem apagar automaticamente campos locais. Campos marcados como manuais e a associação manual são protegidos pelo contrato existente.
- O patch de metadata não assume propriedade sobre caminho/local URI, progresso, estado assistido, favorito, listas pessoais, notas ou identidade interna do arquivo.
- Estados de matching estáveis como `not_found`/`ambiguous` podem ser persistidos para evitar consultas repetidas; falhas transitórias de rede/rate limit/resposta inválida permanecem elegíveis a uma tentativa futura segundo o cooldown do serviço.
- O serviço verifica novamente a associação e os flags manuais na fronteira transacional antes de efetuar a escrita. Uma falha remota não é sucesso vazio nem motivo para limpar dados locais.

## Integração com consumidores Compose

| Consumidor | Uso de AniList |
|---|---|
| `ReiAnixAppDependencies` | Grafo lazy compartilhado para o cliente, serviço, localizer e repositório SQLite |
| `ReiAnixAniListViewModel` | Pesquisa remota sob demanda, ação de associação, estado de carregamento/erro e descarte de respostas de pesquisa obsoletas por ID da requisição |
| `ReiAnixDetails.kt` | Pesquisa/associação manual e atualização explícita do metadado associado |
| `ReiAnixComposeLibraryHost.kt` | Reconciliação pós-scan quando o estado de scan passa para concluído; não em cada recomposição ou apenas por abrir a tela |
| `ReiAnixSqliteLibraryRepository` e `ReiAnixSqliteAniListAssociation.kt` | Leitura/escrita canônica e transações de associação/patch |
| `ReiAnixArtworkService` | Baixa/valida/persiste artwork no caminho nativo após o commit do metadado |
| `main.py` + `core/library_service.py` | Camada de compatibilidade/projeção: no modo Compose, a hidratação Python recebe `allow_anilist_metadata=False`; jornadas legadas mantêm a rota Python |

As chamadas de pesquisa são explícitas/sob demanda; o ViewModel cancela busca substituída e verifica a identidade de requisição antes de publicar resultados. A coordenação de hidratação da biblioteca ocorre no host após uma conclusão efetiva de scan, não em cada recomposição. O caminho Python não deve ser considerado removido enquanto `core/anilist.py`, `core/library_service.py` e `core/artwork.py` tiverem consumidores Flet ativos.

## Dependências, testes e workflows

- Gradle existente inclui Coroutines Android e JUnit; a infraestrutura Kotlin usa os frameworks já declarados. Não foi identificada necessidade de adicionar outra biblioteca HTTP/JSON/GraphQL para esta etapa.
- Teste JVM do cliente existente: `android/app/src/test/kotlin/com/reiflix/reiflix_local/data/anilist/ReiAnixAniListClientTest.kt`.
- Contratos Python de integração existentes: `tests/test_native_anilist_integration_contract.py`, além dos testes de metadata legados.
- Os testes existentes cobrem consulta/variables GraphQL, conversão de campos opcionais, respostas vazias versus malformadas, erro GraphQL/HTTP 429, proteção da associação manual, persistência parcial e o handoff de artwork. A presença destes testes não prova execução.
- Esta alteração não adicionou testes instrumentados/de emulador, não removeu testes Python e não alterou workflows.
- Os testes Kotlin/JVM não foram executados manualmente nesta etapa. A compilação/testes automáticos só podem ser afirmados com resultado de uma execução GitHub Actions associada ao commit final; o estado anterior de outro SHA não prova o estado do commit documental atual.

## Arquivos relevantes e fronteira Python remanescente

**Implementação Kotlin já presente no código consultado:**
- `android/app/src/main/kotlin/com/reiflix/reiflix_local/data/anilist/ReiAnixAniListClient.kt`
- `android/app/src/main/kotlin/com/reiflix/reiflix_local/data/anilist/ReiAnixAniListIntegrationService.kt`
- `android/app/src/main/kotlin/com/reiflix/reiflix_local/data/anilist/ReiAnixAniListMatcher.kt`
- `android/app/src/main/kotlin/com/reiflix/reiflix_local/data/anilist/ReiAnixAniListDescriptionLocalizer.kt`
- `android/app/src/main/kotlin/com/reiflix/reiflix_local/data/sqlite/ReiAnixSqliteAniListAssociation.kt`
- `android/app/src/main/kotlin/com/reiflix/reiflix_local/data/sqlite/ReiAnixSqliteLibraryRepository.kt`
- `android/app/src/main/kotlin/com/reiflix/reiflix_local/viewmodel/ReiAnixAniListViewModel.kt`
- `android/app/src/main/kotlin/com/reiflix/reiflix_local/ReiAnixAppDependencies.kt`

**Código legado que continua presente e precisa de consumidores auditados antes de ser removido:**
- `core/anilist.py`
- `core/organizer_ai.py`
- `core/library_service.py`
- `core/artwork.py`

## Limitações e próximos passos

- Esta etapa não migra o runtime inteiro para Kotlin e não remove o Flet nem a ponte `NativeMailbox`.
- O sistema de arte legado ainda tem consumidores Python legítimos; migrar o produtor de artwork é uma frente distinta e não deve ser declarado concluído apenas porque a UI Compose consome URLs/caminhos.
- A disponibilidade de PT-BR depende da fonte remota/serviço de tradução; falha ou texto não validado não pode ser apresentado como tradução confirmada.
- Testes Kotlin/JVM manuais não foram executados; qualquer aprovação de compilação/CI para o SHA final deve ser verificada no GitHub Actions depois do commit.
- O estado dos prompts 01–05 não é inferido somente pelas descrições dos prompts: o estado aqui descrito deriva dos arquivos e contratos encontrados na referência consultada.

## Registro desta alteração

- Arquivo criado por esta etapa: `docs/KOTLIN_METADATA_ANILIST_MIGRATION.md`.
- Nenhum arquivo Kotlin/Python, teste ou workflow foi alterado nesta atualização documental porque os componentes centrais já estavam implementados no código consultado e uma duplicação reduziria a clareza/segurança da arquitetura.
- Não há remoções de arquivos/pastas nem mudança de schema.
