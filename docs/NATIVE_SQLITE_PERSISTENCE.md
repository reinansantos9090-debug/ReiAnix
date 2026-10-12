# Persistência SQLite nativa — Prompt 3

## Estado inicial verificado

- Repositório: `reinansantos9090-debug/ReiAnix`.
- Branch de referência: `main`.
- SHA completo inicial: `495ad3b3c80243ef0a5be97a002e63d0d4a76810`.
- Fonte canônica atual: `core/library_store.py`, `LibraryStore.SCHEMA_VERSION = 29`.
- Arquivo canônico esperado pelo host Android e pelos testes de regressão existentes: `filesDir/data/library.sqlite3`.
- O esquema canônico tem 14 tabelas: `folders`, `anime`, `episodes`, `episode_observations`, `artwork`, `associations`, `pending_matches`, `account`, `preferences`, `genres`, `genre_aliases`, `anime_genres`, `schema_migrations` e `scan_runs`.

## Implementação Kotlin

O pacote `data/sqlite` contém:

- `ReiAnixSqliteDatabase`: abre o arquivo existente em modo somente leitura ou leitura/escrita, habilita chaves estrangeiras, aplica `busy_timeout` e oferece transações. A compatibilidade em runtime consome `native-sqlite-contract-v29.json`, gerado em build a partir de um banco temporário inicializado pelo `LibraryStore` real; não há listas manuais parciais de centenas de colunas no validador. `PRAGMA table_xinfo` compara **todas as colunas** das 14 tabelas canônicas por nome, tipo declarado, nulabilidade, default, posição da PK e estado oculto/gerado. Para tabelas canônicas, colunas extras não versionadas são rejeitadas; tabelas adicionais fora do conjunto canônico são toleradas. `PRAGMA index_list/index_xinfo` verifica os 29 índices explícitos, e também todas as restrições únicas/PK do SQLite por assinatura semântica, comparando tabela, unicidade, origem, ordem de colunas, expressões, direção, collation e predicado parcial. `PRAGMA foreign_key_list` compara IDs e sequência de cada FK, origem/destino e ações `ON UPDATE`/`ON DELETE`/`MATCH`. Opções `STRICT`/`WITHOUT ROWID`, restrições `CHECK` e triggers também são confrontados com o contrato extraído do DDL real. Operações de leitura/escrita validam o contrato antes de executar a primeira operação; a inspeção diagnóstica retorna os motivos de incompatibilidade. `quickCheck()` exige `ok`, e a inspeção também usa `PRAGMA foreign_key_check`. Não cria, migra, apaga ou substitui o arquivo canônico.
- `ReiAnixSqliteLibraryRepository`: leitura paginada de anime, consultas por IDs persistidos, episódios por anime, episódios recentes, pastas e artwork; atualização transacional de progresso; patch de metadados que preserva campos em `metadata_manual_fields`, trata strings vazias/`[]` como dados não fornecidos e não altera favoritos, pins, etiquetas, notas, identidade nem bookkeeping de origem.
- `ReiAnixSqliteSettingsRepository`: leitura, gravação transacional e remoção explícita de preferências na tabela existente.
- `ReiAnixSqliteModels`: registros Kotlin para as colunas existentes. Valores nulos e colunas JSON/textuais são mantidos sem normalização destrutiva. IDs de anime, episódio e mídia não são gerados novamente.

As APIs dos repositórios usam corrotinas em `Dispatchers.IO` para evitar I/O de SQLite na thread principal. Os valores externos são vinculados por placeholders SQLite; não são interpolados em SQL.

## Compatibilidade e fonte da verdade

Nenhuma alteração foi feita no esquema 29; não foi adicionada versão, tabela ou índice. O banco usado pelo Kotlin é o mesmo arquivo `library.sqlite3`, não uma cópia nem um catálogo paralelo. Falhas de abertura, consulta e escrita são propagadas; uma leitura com erro não vira uma lista vazia.

O fluxo de Compose existente ainda usa `ReiAnixLibraryRepository` e `ReiAnixSettingsRepository` como adapters de projeção/IPC. A biblioteca Python (`LibraryStore`, `LibraryService`) continua sendo a escritora usada por esses fluxos e pelo pipeline de `NativeMailbox`. As novas operações de escrita Kotlin são APIs reais sobre o mesmo SQLite, mas não devem ser chamadas para uma mutação que ainda esteja sendo enviada ao Python; cada operação precisa ser cortada para um único escritor. O player e `PlayerLocalMetadataStore` não foram alterados neste prompt.

O progresso e a data de reprodução estão persistidos nas colunas da tabela `episodes`; não foi criada uma tabela de histórico paralela. A recuperação/backup do banco continua pertencendo aos serviços existentes até que uma etapa posterior migre esses comportamentos com paridade comprovada.

## Testes

- `ReiAnixSqliteModelsTest` valida os modelos e a validação de progresso sem emulador.
- `test_native_sqlite_persistence_contract.py` verifica os contratos estáticos de caminho, esquema, transação, consultas parametrizadas e ausência de criação destrutiva.
- `ReiAnixSqliteRepositoryRuntimeTest` executa os repositórios contra o SQLite Android via Robolectric no JVM, sem dispositivos nem emuladores. `scripts/generate_native_sqlite_test_fixture.py` gera em uma única execução o fixture de teste (com DDL) e o asset de runtime (com os metadados esperados), ambos provenientes do mesmo snapshot temporário do `LibraryStore`. A suíte compara colunas e metadados do contrato, testa a rejeição real na fronteira do repositório e injeta divergências somente em bancos temporários para validar tipos, nulabilidade, defaults, PKs, FKs, unicidade, ordem/collation/expressões de índices, `CHECK`, triggers, colunas geradas, corrupção e integridade referencial. Os registros de teste são sintéticos; nenhum banco ou dado pessoal é copiado.
- `.github/workflows/kotlin_jvm_tests.yml` permanece exclusivamente manual (`workflow_dispatch`). O workflow gera o recurso de esquema canônico antes de renderizar o host Android e o remove na etapa de limpeza. `scripts/prepare_flet_template.py` copia os recursos de teste, mas não cria nem modifica workflows ou seus gatilhos. O workflow de APK mantém seus próprios gatilhos.
- O teste Python do contrato executa o gerador e confere a versão, o conjunto de 14 tabelas, colunas obrigatórias e índices; a suíte Robolectric testa abertura, leituras, escrita, reabertura, rollback, detecção de esquema futuro/incompleto, preferências, ownership de metadados e recusa de bancos ausentes/corrompidos. Não foram adicionados testes instrumentados nem jobs de emulador.

## Pendências explícitas

- Migrar consumidores individuais da mailbox/projeções para estes repositórios só depois de verificar equivalência das regras e remover o escritor Python correspondente para cada operação.
- Migrar validação de configuração, ingestão/reconciliação, recuperação/backup e eventos completos do player exige trabalho de domínio e paridade fora do escopo desta camada.
- A execução do workflow Kotlin JVM continua exigindo acionamento explícito manual. O resultado definitivo dos testes JVM e do build deve ser registrado somente após cada workflow pertinente no SHA final terminar em `completed/success`; a existência dos arquivos não é evidência de aprovação.


## Runtime contract tests

`android/app/src/test/kotlin/com/reiflix/reiflix_local/data/sqlite/ReiAnixSqliteRepositoryRuntimeTest.kt` uses Robolectric to run Android SQLite APIs in the JVM; no device/emulator tests were added. The suite consumes a JSON fixture generated from a fresh temporary database opened by the canonical `LibraryStore`; it recreates the real v29 table/index DDL and adds only synthetic test rows. It covers catalog/schema reads, SQLite `quick_check`, reopening connections, playback persistence, transaction rollback, preferences round-trip, manual metadata ownership, rejection of future/incomplete schemas, and refusal to create/replace missing or corrupt databases. This is schema derived from the canonical implementation, not a copy of any user's database.


## Validação estrutural do Prompt 3.2

A validação deixou de considerar um índice válido apenas pelo nome. O snapshot contém metadados estruturados e registra razões explícitas de incompatibilidade. As definições SQL são usadas apenas para extrair predicados de índices parciais, com normalização que preserva valores literais; não se exige igualdade textual integral do DDL. O contrato de FKs é comparado para todas as tabelas canônicas, inclusive a ausência de FKs não previstas. Restrições únicas geradas pelo SQLite são inspecionadas por seus metadados, sem depender dos nomes internos `sqlite_autoindex_*`.

O gerador confirma a versão do esquema e exporta `PRAGMA user_version` separadamente de `schema_migrations`. O `LibraryStore` atual só registra a versão 29 em `schema_migrations`; não define `PRAGMA user_version`, portanto o valor canônico esperado é 0. Um valor diferente é classificado como incompatível, sem migração automática.


## Contrato de runtime e limites de compatibilidade (Prompt 3.3)

O contrato usado pelo Kotlin é gerado em tempo de build no arquivo `android/app/src/main/assets/native-sqlite-contract-v29.json`. O gerador cria um banco temporário chamando o `LibraryStore` real, exporta seus metadados uma única vez e produz também o recurso JSON de teste. O build copia o asset para o projeto Android renderizado; a validação não usa o fixture de teste como banco de produção e não executa DDL do fixture contra o arquivo do usuário.

A versão 29 é registrada em `schema_migrations`. O código canônico não define `PRAGMA user_version`, portanto o valor canônico observado é `0`; o contrato registra esse valor em separado. O validador compara tabelas/colunas, metadados dos índices explícitos e internos, FKs, checks, opções de tabela e triggers. Ele não exige igualdade textual integral do DDL: o SQL é normalizado apenas para comparar predicados de índices parciais, restrições CHECK e definições de trigger. Os metadados estruturados do SQLite são a base principal.

O esquema canônico v29 não define colunas geradas, tabelas `STRICT`, tabelas `WITHOUT ROWID`, restrições `CHECK` nem triggers. A fixture exporta essas propriedades, e o runtime compara sua ausência. A API do SQLite não expõe a expressão de uma coluna gerada por todos os mesmos pragmas; `table_xinfo` expõe o indicador `hidden`, e colunas inesperadas/ocultas são rejeitadas. Os índices de expressão são identificados por `index_xinfo` (`cid = -2`) e comparados estruturalmente.

Tabelas adicionais são toleradas porque podem pertencer a extensões ou a outros componentes. Colunas adicionais dentro das 14 tabelas de versão 29 são tratadas como deriva de esquema não versionada, incompatível com o contrato exato dessa versão. Triggers inesperados são rejeitados porque podem alterar silenciosamente os efeitos de escrita. Uma divergência, um `quick_check` diferente de `ok` ou uma linha em `foreign_key_check` nunca aciona reparo automático; os dados são preservados e o erro é exposto ao chamador.

## Prompt 10 — serviço de consultas nativas da biblioteca

- SHA inicial observado na branch `main` antes das alterações do Prompt 10: `962c0e5df91fb2a8fb1673697e4cc2242a6c7c56` (merge do Prompt 9).
- A fonte canônica continua sendo o SQLite `library.sqlite3`, esquema 29, criado e escrito por `LibraryStore`. Não foi criado banco paralelo nem alterado o esquema.
- Foi adicionado `ReiAnixLibraryQueryService`, uma camada de consulta somente leitura sobre `ReiAnixSqliteLibraryRepository`. Ela expõe consultas por ID de anime/episódio, páginas de animes, episódios por anime, reproduções recentes, pastas e artwork. A paginação lê no máximo `limit + 1` linhas para determinar `hasMore`, com limite de página explícito.
- O resultado distingue `Success`, `Empty`, `NotFound` e `Failure`. Exceções de consulta/mapeamento são registradas e mantidas como falhas; cancelamento de corrotina é propagado. O serviço preserva IDs SQLite, relações por `anime_id` e campos de metadados nulos/incompletos, sem gerar valores de substituição.
- `ReiAnixLibraryViewModel` agora expõe métodos suspensos de consulta nativa para anime, episódios, histórico recente, pastas e artwork, usando o serviço acima. A integração é aditiva: a UI existente continua consumindo o snapshot JSON/IPC para os fluxos de biblioteca que ainda dependem dos comandos Python, scanner, metadados e reconciliação. Não foi feita uma troca silenciosa de escritor nem removida a mailbox.
- Foram acrescentados testes Robolectric/JVM para resultados com registros, paginação além do fim, IDs inexistentes, relações anime/episódio, pastas, artwork e falha quando o banco canônico está ausente. Esses testes foram adicionados ao código, mas a suíte JVM não foi executada por esta sessão; a aprovação em runtime permanece pendente de execução do workflow manual `kotlin_jvm_tests.yml`.
- Dependências remanescentes: `core/library_store.py` e `core/library_service.py` continuam como autoridade de escrita/ingestão e fonte das projeções consumidas pelas telas atuais. A migração de cada fluxo de UI deve ocorrer com paridade comprovada e escritor único. Portanto, esta etapa adiciona o serviço nativo e os pontos de acesso no ViewModel, mas **não declara a biblioteca inteira nem o fluxo visual principal totalmente migrados para SQLite nativo**.

