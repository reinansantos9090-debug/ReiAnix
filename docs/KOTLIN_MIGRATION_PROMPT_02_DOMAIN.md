# Prompt 02/18 — Migração de contratos de domínio e identidade de mídia

## Baseline e resultado

- Repositório: `reinansantos9090-debug/ReiAnix`
- Base oficial verificada: `main` em `11eba5aa7b546d80d9d3d8042638931e012e1a4d`
- Branch: `migration/prompt-02-domain-contracts-20261018`
- Escopo: modelos de mídia/domínio, classificação de localização, parser decimal, normalização de gênero e reconciliação documental.
- SQLite: schema v29 preservado; nenhuma tabela, coluna, índice, chave persistida ou banco alternativo foi criado.
- Testes: nenhum teste foi adicionado ou modificado. Os testes Kotlin/JVM não foram executados manualmente. A validação automática oficial será conferida no SHA final.

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
- Nenhum teste foi criado, removido ou modificado. Os testes Kotlin/JVM não foram executados manualmente. Os cenários de localização e decimais foram revisados estaticamente contra o parser e os asserts preexistentes; não há prova de execução comportamental desses novos cenários.
- O workflow oficial de PR já contém regressão Python e compilação/build Android. Isso não prova que os novos casos de parser decimal/classificação de URI tenham asserts próprios nem equivale a executar a suíte Kotlin/JVM. Essa cobertura específica fica pendente até uma fase autorizada de validação.

## Correções do Prompt 02.1

1. A classificação de localização agora retorna `UNRESOLVED` para strings vazias/compostas por espaços e mantém esquemas reconhecíveis como URI mesmo quando o valor é malformado.
2. Os marcadores `/tree/` e `/document/` deixaram de ser suficientes isoladamente para classificar SAF. A classificação exige uma autoridade DocumentsProvider incluída na lista conservadora e a estrutura exata dos segmentos; URI de provedor não reconhecido permanece genérica `CONTENT_URI`.
3. A documentação restringe explicitamente a alegação de paridade Unicode: os mapeamentos `ß`/`ss` e `ς`/`σ` são tratados, mas isso não representa uma implementação completa de `str.casefold()`.
4. O parser também impede truncar `ABS1122.5` para o absoluto `1122`, interpretar um decimal absoluto como episódio regular ou extrair os dígitos fracionários como candidato separado. Nomes somente numéricos continuam sem identidade de episódio, mesmo quando o decimal excede o limite aceito.

## Integridade e validação

- Não adiciona classes de domínio duplicadas, novas tabelas, banco alternativo, testes, workflows ou scripts.
- Os três arquivos de teste Kotlin/JVM consultados permanecem inalterados; nenhum teste Kotlin/JVM foi executado manualmente. Os novos comportamentos foram revisados estaticamente, não demonstrados por asserts dedicados. O workflow automático comprova regressão Python e compilação, não execução da suíte Kotlin/JVM nem paridade de cada caso limite.
- O workflow automático `build_apk.yml` que valida esta correção deve ser lido apenas na execução associada ao SHA final da PR. Runs vinculados a SHAs anteriores não validam este complemento.
- Schema SQLite v29, mapeadores de leitura, IDs, referências de mídia e consumidores Python foram preservados.
- Esta implementação não representa a migração integral concluída: a camada de domínio ainda depende de integrações posteriores e validação comportamental autorizada.

## Prompt 02.2/18 — cobertura de regressão e limites de validação

### Regressões acrescentadas nas suítes existentes

- `MediaFilenameParserTest.kt`: decimais com ponto e vírgula em marcadores SxxExx, temporada/EP, NxM e Episode; S00, OVA e SP; decimais absolutos rejeitados; precisão acima de duas casas; separadores repetidos e decimais encadeados; nomes compostos apenas por números decimais e fallback para o título da pasta; exclusão de anos, resoluções e números técnicos.
- `MediaDomainAndIdentityTest.kt`: classificação de caminhos e esquemas URI, URI de documentos/árvore SAF de autoridades reconhecidas, autoridades desconhecidas com segmentos parecidos com SAF, caminhos inválidos de provedores reconhecidos e esquema `content:` malformado. Também confirma que `storedLocation` e os acessores derivados preservam a string original.
- `ReiAnixGenreClassifierTest.kt`: exemplos concretos para `ß → ss`, `ς → σ`, remoção de acentos e normalização de separadores/espaços. A cobertura reflete exemplos específicos compatíveis com `core/genre_registry.py::normalize_genre`; não afirma equivalência Unicode universal com `str.casefold()`.

### Correção estritamente ligada à regressão

O parser agora rejeita separadores decimais repetidos antes de aceitar um prefixo inteiro em marcadores de episódio e sufixos numéricos (por exemplo, `03..5`, `03,,5` e `03.5.2`). A incerteza de precisão excessiva em sufixos numéricos também passa a ser refletida em `ambiguities`, alinhada ao campo `unresolved`. Não foram alteradas as regras de identidade absoluta inteira nem o fallback conservador de nomes numéricos.

### Estado de execução dos testes

Os métodos de regressão foram adicionados aos arquivos de teste existentes e revisados estaticamente; **não foram executados durante este prompt**. A restrição desta etapa proíbe executar manualmente a suíte Kotlin/JVM e despachar o workflow manual. `.github/workflows/kotlin_jvm_tests.yml` tem apenas o gatilho `workflow_dispatch`; o workflow automático `build_apk.yml` executa a suíte Python, produz o APK e compila Kotlin de produção com `:app:compileReleaseKotlin`, mas não executa `:app:testDebugUnitTest`. Portanto, os novos testes Kotlin permanecem pendentes de execução autorizada e não podem ser descritos como aprovados com base no build do APK.

Nenhum workflow, script de build, arquivo de persistência, schema, módulo Python, diretório ou estrutura paralela de testes foi alterado ou criado nesta etapa.

