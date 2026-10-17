# ReiAnix — contratos de dados Kotlin e fronteiras híbridas

## Escopo e autoridade dos dados

Esta documentação descreve os contratos que já existem no snapshot integrado à branch `main` e as regras endurecidas pelo Prompt 24. Ela não afirma independência integral de Python.

O banco canônico continua sendo `filesDir/data/library.sqlite3` no Android e o arquivo de biblioteca já utilizado por `core/library_store.py`. A versão atual encontrada é schema v29. `ReiAnixSqliteDatabase.kt` abre e inspeciona o banco existente e compara seu contrato com `native-sqlite-contract-v29.json`; esta etapa não altera schema, cria tabelas ou introduz um banco/repositório paralelo.

## Modelos e responsabilidades

- `data/media/MediaDomainModels.kt`: modelos de domínio nativos, com wrappers distintos para IDs internos de anime/episódio, ID externo do catálogo AniList e identidade de mídia. O modelo `LocalMediaReference` conserva a referência de localização armazenada e deriva URI/path apenas para apresentação/uso.
- `data/sqlite/ReiAnixSqliteModels.kt`: projeções das linhas e patches de persistência. Colunas JSON/texto permanecem strings brutas quando a semântica exige que campos desconhecidos ou nulos sobrevivam. `ReiAnixAnimeMetadataPatch` trata `null` como campo não fornecido e não representa campos pessoais que a consulta remota não pode possuir.
- `data/sqlite/SqliteDomainMappers.kt`: conversões explícitas entre registros SQLite e domínio. Não deve criar IDs nem reinterpretar caminhos.
- `ui/model/LibraryUiModels.kt` e `ui/mapper/LibraryUiMappers.kt`: modelos imutáveis de apresentação e projeção do snapshot híbrido. São modelos de UI, não persistência nem fonte de verdade.
- `data/contract/ReiAnixContractValues.kt` e `ReiAnixJsonContract.kt`: conversões numéricas escalares e helpers JSON compartilhados. Inteiros não são truncados; overflow, números não finitos, tipos JSON incompatíveis e arrays/objetos malformados geram erros observáveis. Defaults para coleções/strings/booleanos são usados apenas quando o campo é opcional e está ausente; null explícito só é aceito por helpers de campos nullable.
- `data/library/ReiAnixLibrarySnapshotCodec.kt`: fronteira JSON entre o snapshot `schemaVersion=1`, resultados de comandos e modelos Compose.
- `data/settings/ReiAnixSettingsSnapshotCodec.kt`: fronteira JSON para o snapshot de configurações `schemaVersion=1`. A representação entregue à UI é `Map<String, String>`; valores JSON escalares são convertidos explicitamente para texto, e arrays/objetos mantêm sua serialização JSON. Uma configuração presente com valor JSON null é inválida; configuração ausente continua ausente e string vazia permanece string vazia.
- `bridge/NativeMailbox.kt` e `bridge/NativeRequestState.kt`: eventos/comandos de transporte, correlação por requestId e estados de processamento. A mailbox não substitui o armazenamento de domínio.
- `data/anilist/ReiAnixAniListClient.kt`, `ReiAnixAniListIntegrationService.kt` e `data/sqlite/ReiAnixSqliteAniListAssociation.kt`: consulta/associação remota e persistência local de associações. A resposta GraphQL é validada como JSON tipado: ID deve ser inteiro positivo; episódios/duração/ano não são truncados; averageScore fica no intervalo 0–100; campos de texto e listas rejeitam tipos incorretos. Campos opcionais ausentes/null são tratados como informação indisponível, mas tipos presentes incompatíveis produzem `AniListError.InvalidResponse`.
- `data/backup/ReiAnixBackupService.kt` e `ReiAnixDiagnosticsService.kt`: formatos e projeções de exportação/diagnóstico; formatos preexistentes não devem ser reduzidos só porque algum campo não aparece na UI.

## Regras semânticas relevantes

### IDs

IDs SQLite de anime e episódio são identidades internas distintas. `AniListCatalogId` é um identificador de provedor externo e não deve ser usado como chave local. Caminhos, URIs SAF/content, chaves de cache e IDs de sessão/request também não são substitutos uns dos outros. Conversões de IDs devem ser exatas, sem truncar frações, overflow ou gerar chaves alternativas.

### Campos ausentes, nulos e inválidos

A ausência e o nulo permanecem opcionais quando o contrato diz que o campo é opcional; zero e `false` são valores presentes. Uma lista ausente pode usar um default documentado somente quando o campo é opcional. Quando um campo presente deveria ser um número, booleano, objeto ou array, mas contém um tipo incompatível, a fronteira deve falhar de modo observável em vez de convertê-lo silenciosamente para zero, falso, lista vazia ou item descartado. Estados persistidos/desconhecidos não devem receber um default que finja que foram reconhecidos.

`ReiAnixContractValues.kt` centraliza a conversão escalar estrita para as fronteiras Kotlin. Inteiros são validados sem truncamento, os limites de Int são verificados, números usados como Double devem ser finitos, e campos booleanos aceitam Boolean ou formas canônicas 0/1. Os mapeadores e codecs preservam defaults apenas para campos explicitamente ausentes/opcionais.

### Caminhos, artwork e metadados

O caminho/URI armazenado de mídia deve sobreviver à projeção sem ser normalizado para um ID externo. Uma atualização AniList deve manter campos de propriedade local — incluindo caminho, progresso, favoritos, tags, pin e anotação — fora de um patch de catálogo. Referências de artwork distinguem caminho local, URL externa, tipo de material visual e entidade proprietária; ausência temporária de imagem não significa exclusão do registro de domínio.

### Tempo e unidades

- `episodes.progress`, `episodes.duration`, posições e durações do player são segundos.
- Colunas de domínio como `added_at`, `last_played_at` e timestamps de metadados usam epoch seconds quando assim estabelecido pelo contrato persistido.
- Campos explícitos de transporte como snapshot `generatedAt`, eventos de mailbox `createdAt`/`timestamp` e valores monotônicos de diagnóstico usam milissegundos ou nanossegundos conforme o nome/campo correspondente; não são intercambiáveis com timestamps de domínio.
- Snapshot `revision` é uma sequência monotônica de revisão, não uma data.

As conversões de unidade devem permanecer centralizadas nos adaptadores apropriados; não multiplicar/dividir timestamp ou progresso em cada consumidor.

## Snapshot Compose e mailbox

O produtor híbrido em `core/compose_library_bridge.py` publica `schemaVersion=1`, revisão crescente e `generatedAt` em milissegundos para `reianix-compose/library.json`. O consumidor Kotlin exige a versão de schema compatível, rejeita revisões obsoletas e valida as estruturas tipadas do payload. `continue_watching` é aceito somente quando anime e episódio existem no mesmo snapshot da biblioteca, evitando publicar pares órfãos de uma leitura concorrente.

A mailbox contém mensagens de transporte, incluindo identificadores/correlação, campos temporais, tipo de evento e payload. Em transições de migração, consumidores e produtores legados ainda podem depender de campos/aliases existentes; qualquer remoção de chave requer confirmar todas as referências e atualizar a paridade. Falha em decodificar ou validar dados não deve ser substituída por um resultado fictício de sucesso.

## Dependências Python remanescentes

No snapshot documentado, `core/library_store.py` ainda é dono de operações de catálogo/schema e `core/compose_library_bridge.py` produz snapshots para consumidores Compose. Outros fluxos híbridos de serviços, recuperação e integração seguem ativos conforme a matriz de migração. A existência de modelos Kotlin ou de leitura nativa do mesmo arquivo não prova que todos os produtores, escritores e fluxos de execução Python tenham sido eliminados. A retirada de cada dependência exige migrar a operação e seus efeitos colaterais, confirmar seus consumidores, e só então remover a ponte/módulo.

## Persistência e validação

- Não alterar o schema v29 para simplificar modelos.
- Não criar uma segunda base ou fonte de verdade.
- Não apagar dados antigos, backups ou campos que possam ser necessários para recuperar a biblioteca.
- Testes Python de contrato podem fiscalizar as regras e a configuração dos workflows.
- Testes Kotlin/JVM permanecem manualmente acionáveis via `workflow_dispatch` e não são executados manualmente nesta etapa; nenhum novo teste instrumentado/de emulador foi adicionado.
- Resultados de build e CI devem ser atribuídos ao SHA exato do commit verificado. Compilação não prova, por si só, que o runtime Python foi removido.
