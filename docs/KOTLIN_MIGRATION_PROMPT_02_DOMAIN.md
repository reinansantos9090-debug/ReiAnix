# Prompt 02/18 — Migração de contratos de domínio e identidade de mídia

## Baseline e resultado

- Repositório: `reinansantos9090-debug/ReiAnix`
- Base oficial verificada: `main` em `11eba5aa7b546d80d9d3d8042638931e012e1a4d`
- Branch: `migration/prompt-02-domain-contracts-20261018`
- Escopo: modelos de mídia/domínio, classificação de localização, parser decimal, normalização de gênero e reconciliação documental.
- SQLite: schema v29 preservado; nenhuma tabela, coluna, índice, chave persistida ou banco alternativo foi criado.
- Testes Kotlin/JVM: o HEAD de partida desta reconciliação já contém alterações em três arquivos de teste existentes (`MediaFilenameParserTest.kt`, `ReiAnixGenreClassifierTest.kt` e `MediaDomainAndIdentityTest.kt`), feitas no incremento Prompt 02.2. Nenhum novo arquivo de teste ou workflow foi criado. A suíte Kotlin/JVM não foi executada e seus testes não foram confirmados como compilados. O workflow automático `build_apk.yml` no SHA `69f35f0aa6ac16946b3ff690f0b29e1c7d8eb69c` terminou em `success`, mas a compilação Kotlin de produção não equivale a compilar/executar `:app:testDebugUnitTest`. Esse run antecede a reconciliação documental e não valida o SHA que resultar dela.

## Reaproveitado do Kotlin existente

Os modelos `Anime`, `Episode`, `LogicalEpisodeIdentity`, `LocalMediaReference`, `ArtworkReference` e `PlaybackProgress` em `data/media/MediaDomainModels.kt` já existiam e foram mantidos. Também já existiam `LocalMediaIdentity`, `MediaTitleNormalizer`, `MediaFilenameParser`, `ReiAnixGenreRegistry`, `ReiAnixGenreClassifier`, os records SQLite e `SqliteDomainMappers.kt`. Nenhuma implementação concorrente foi criada.

## Mudanças implementadas nesta etapa

1. `LocalMediaReference` expõe uma classificação derivada de localização: caminho filesystem, string vazia/não resolvida, `file://`, `content://`, SAF tree URI, SAF document URI ou outro esquema. O valor persistido original continua intacto; a classificação não concede acesso nem transforma URI em caminho. A classificação SAF é deliberadamente conservadora: exige uma autoridade DocumentsProvider conhecida e segmentos estruturais exatos; providers desconhecidos permanecem `CONTENT_URI`, e URI malformada nunca vira caminho de filesystem.
2. `MediaFilenameParser` reconhece números de episódio decimais com ponto ou vírgula em marcadores explícitos e sufixo numérico. A guarda conservadora permanece: nomes somente numéricos (incluindo `12.5.mkv`) não são promovidos a identidade de episódio; numeração absoluta continua inteira.
3. `normalizeReiAnixGenre` cobre explicitamente diferenças relevantes para `ß` e sigma final grego após decomposição Unicode. Isto não é uma implementação completa de `str.casefold()` do Python; as diferenças Unicode não cobertas continuam documentadas como limite de paridade.
4. Documentação e roadmap foram alinhados para que o Prompt 02 cubra a camada de domínio e o Prompt 11 conecte esses contratos ao fluxo nativo ativo de discovery.

## Python e consumidores

Os módulos `core/library_parser.py`, `core/library_discovery.py`, `core/media_identity.py`, `core/genre_registry.py` e `core/genre_classifier.py` permanecem. Serviços, bridges e telas Python ainda os consomem; excluí-los agora causaria regressão no runtime híbrido. Arquivos Python removidos: **0**; linhas Python removidas: **0**. Isso não representa eliminação da dependência de runtime.

## Integrações que continuam fora da etapa

- Troca do parser do fluxo Python/LibraryService ativo.
- Cutover integral do coordenador/scanner e observações de episódio.
- Migração integral de LibraryService e regras de merge.
- Bootstrap nativo, remoção de entrypoint híbrido e build APK 100% nativo.
- Paridade funcional completa entre os caminhos legado e Kotlin em runtime.

## Testes existentes e limite de cobertura

- Python preexistente: `tests/test_media_identity.py` e `tests/test_genre_registry.py`, além das suítes de biblioteca/discovery no repositório.
- Kotlin/JVM preexistente: `MediaFilenameParserTest.kt`, `MediaDomainAndIdentityTest.kt`, `ReiAnixGenreClassifierTest.kt` e `ReiAnixSqliteModelsTest.kt`.
- Os três arquivos de teste Kotlin/JVM citados no incremento Prompt 02.2 foram modificados para acrescentar cenários de decimais, classificação URI/SAF e exemplos Unicode selecionados. Não foi criado um novo arquivo de teste. Os asserts dedicados estão presentes no código, mas as fontes de teste não foram comprovadamente compiladas nem executadas como suíte JVM; portanto, os resultados comportamentais continuam pendentes.
- O run automático `39154979582` passou no SHA `69f35f0aa6ac16946b3ff690f0b29e1c7d8eb69c` para regressões Python/build/compilação Kotlin de produção/artefatos. Ele não executou `:app:testDebugUnitTest` e antecede esta reconciliação documental. Nenhum despacho manual de `.github/workflows/kotlin_jvm_tests.yml` foi feito.

## Correções do Prompt 02.1

1. A classificação de localização agora retorna `UNRESOLVED` para strings vazias/compostas por espaços e mantém esquemas reconhecíveis como URI mesmo quando o valor é malformado.
2. Os marcadores `/tree/` e `/document/` deixaram de ser suficientes isoladamente para classificar SAF. A classificação exige uma autoridade DocumentsProvider incluída na lista conservadora e a estrutura exata dos segmentos; URI de provedor não reconhecido permanece genérica `CONTENT_URI`.
3. A documentação restringe explicitamente a alegação de paridade Unicode: os mapeamentos `ß`/`ss` e `ς`/`σ` são tratados, mas isso não representa uma implementação completa de `str.casefold()`.
4. O parser também impede truncar `ABS1122.5` para o absoluto `1122`, interpretar um decimal absoluto como episódio regular ou extrair os dígitos fracionários como candidato separado. Nomes somente numéricos continuam sem identidade de episódio, mesmo quando o decimal excede o limite aceito.

## Integridade e validação

- Não foram adicionadas classes de domínio duplicadas, novas tabelas, banco alternativo, arquivos de teste, workflows ou scripts durante esta reconciliação documental. O HEAD de partida já continha métodos de regressão acrescentados a três arquivos de teste existentes no incremento Prompt 02.2.
- Os três arquivos de teste Kotlin/JVM consultados estão modificados no HEAD de partida: foram acrescentados asserts de decimais/parser em `MediaFilenameParserTest.kt`, exemplos Unicode em `ReiAnixGenreClassifierTest.kt` e classificações/preservação de URI em `MediaDomainAndIdentityTest.kt`. Não foram acrescentados novos arquivos de teste nesta reconciliação. A suíte JVM permanece pendente: não há evidência de compilação ou execução dos testes; a revisão feita aqui é estática.
- O run `39154979582` (`build_apk.yml`) concluiu `success` no SHA anterior `69f35f0aa6ac16946b3ff690f0b29e1c7d8eb69c`; não conta como validação do SHA final desta reconciliação. O schema SQLite v29 e o arquivo canônico não foram alterados.
- Schema SQLite v29, mapeadores de leitura, IDs, referências de mídia e consumidores Python foram preservados.
- Esta implementação não representa a migração integral concluída: a camada de domínio ainda depende de integrações posteriores e validação comportamental autorizada.

## Prompt 02.2/18 — cobertura de regressão e limites de validação

### Regressões acrescentadas nas suítes existentes

- `MediaFilenameParserTest.kt`: decimais com ponto e vírgula em marcadores SxxExx, temporada/EP, NxM e Episode; S00, OVA e SP; decimais absolutos rejeitados; precisão acima de duas casas; separadores repetidos e decimais encadeados; nomes compostos apenas por números decimais e fallback para o título da pasta; exclusão de anos, resoluções e números técnicos.
- `MediaDomainAndIdentityTest.kt`: classificação de caminhos e esquemas URI, URI de documentos/árvore SAF de autoridades reconhecidas, autoridades desconhecidas com segmentos parecidos com SAF, caminhos inválidos de provedores reconhecidos e esquema `content:` malformado. Também confirma que `storedLocation` e os acessores derivados preservam a string original.
- `ReiAnixGenreClassifierTest.kt`: exemplos concretos para `ß → ss`, `ς → σ`, remoção de acentos e normalização de separadores/espaços. A cobertura reflete exemplos específicos compatíveis com `core/genre_registry.py::normalize_genre`; não afirma equivalência Unicode universal com `str.casefold()`.

### Correção estritamente ligada à regressão

O parser rejeita separadores decimais repetidos antes de aceitar um prefixo inteiro em marcadores de episódio e sufixos numéricos (por exemplo, `03..5`, `03,,5` e `03.5.2`). A precisão excessiva em sufixos numéricos é refletida em `ambiguities`, alinhada a `unresolved`, inclusive em stems compostos apenas por números, como `12.125.mkv`. Esses casos continuam `UNKNOWN`, sem número de episódio/identidade absoluta; a alteração só adiciona a sinalização de ambiguidade e não promove o nome numérico a episódio. Não foram alteradas as regras de identidade absoluta inteira nem o fallback conservador de nomes numéricos.

### Estado de execução dos testes

Os métodos de regressão foram adicionados aos arquivos de teste existentes e revisados estaticamente; **não foram executados durante este prompt**. A restrição desta etapa proíbe executar manualmente a suíte Kotlin/JVM e despachar o workflow manual. `.github/workflows/kotlin_jvm_tests.yml` tem apenas o gatilho `workflow_dispatch`; o workflow automático `build_apk.yml` executa a suíte Python, produz o APK e compila Kotlin de produção com `:app:compileReleaseKotlin`, mas não executa `:app:testDebugUnitTest`. Portanto, os novos testes Kotlin permanecem pendentes de execução autorizada e não podem ser descritos como aprovados com base no build do APK.

Nenhum workflow, script de build, arquivo de persistência, schema, módulo Python, diretório ou estrutura paralela de testes foi alterado ou criado nesta etapa.

