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

1. `LocalMediaReference` expõe uma classificação derivada de localização: caminho filesystem, `file://`, `content://`, SAF tree URI, SAF document URI ou outro esquema. O valor persistido original continua intacto; a classificação não concede acesso nem transforma URI em caminho.
2. `MediaFilenameParser` reconhece números de episódio decimais com ponto ou vírgula em marcadores explícitos e sufixo numérico. A guarda conservadora permanece: nomes somente numéricos (incluindo `12.5.mkv`) não são promovidos a identidade de episódio; numeração absoluta continua inteira.
3. `normalizeReiAnixGenre` foi alinhada ao comportamento case-fold relevante do Python para `ß` e sigma final grego, preservando normalização Unicode e o algoritmo de classificação existente.
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
- Nenhum teste foi criado, removido ou modificado. Os testes Kotlin/JVM não foram executados manualmente.
- O workflow oficial de PR já contém regressão Python e compilação/build Android. Isso não prova que os novos casos de parser decimal/classificação de URI tenham asserts próprios nem equivale a executar a suíte Kotlin/JVM. Essa cobertura específica fica pendente até uma fase autorizada de validação.

## Integridade e validação

- Não adiciona classes de domínio duplicadas, novas tabelas, banco alternativo ou novos testes.
- Não altera workflows, configuração de build, `main.py`, bridges ou módulos Python legados.
- Preserva contratos SQLite e valores de localizações armazenados.
- Esta implementação não representa a migração integral concluída: a camada de domínio ainda depende das integrações posteriores e de validação oficial.