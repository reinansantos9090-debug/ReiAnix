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

- `ReiAnixSqliteDatabase`: abre o arquivo existente em modo somente leitura ou leitura/escrita, habilita chaves estrangeiras, aplica `busy_timeout`, oferece transações e inspeciona versão, todas as tabelas/colunas requeridas, índices canônicos e `PRAGMA quick_check`. A compatibilidade é aceita somente para a versão 29 exata; versões futuras ou esquemas incompletos são sinalizados como incompatíveis. Não cria diretórios/bancos, tabelas, índices ou migrações e não substitui um arquivo ausente/corrompido.
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
- `ReiAnixSqliteRepositoryRuntimeTest` executa os repositórios contra o SQLite Android via Robolectric no JVM, sem dispositivos nem emuladores. `scripts/generate_native_sqlite_test_fixture.py` inicializa um banco temporário pelo `LibraryStore` real, exporta seu DDL, colunas, restrições e índices para o recurso JSON de teste, e a suíte usa esse DDL no caminho canônico sob o diretório temporário do Robolectric. Os registros de teste são sintéticos; nenhum banco ou dado pessoal é copiado.
- `.github/workflows/kotlin_jvm_tests.yml` permanece exclusivamente manual (`workflow_dispatch`). O workflow gera o recurso de esquema canônico antes de renderizar o host Android e o remove na etapa de limpeza. `scripts/prepare_flet_template.py` copia os recursos de teste, mas não cria nem modifica workflows ou seus gatilhos. O workflow de APK mantém seus próprios gatilhos.
- O teste Python do contrato executa o gerador e confere a versão, o conjunto de 14 tabelas, colunas obrigatórias e índices; a suíte Robolectric testa abertura, leituras, escrita, reabertura, rollback, detecção de esquema futuro/incompleto, preferências, ownership de metadados e recusa de bancos ausentes/corrompidos. Não foram adicionados testes instrumentados nem jobs de emulador.

## Pendências explícitas

- Migrar consumidores individuais da mailbox/projeções para estes repositórios só depois de verificar equivalência das regras e remover o escritor Python correspondente para cada operação.
- Migrar validação de configuração, ingestão/reconciliação, recuperação/backup e eventos completos do player exige trabalho de domínio e paridade fora do escopo desta camada.
- A execução do workflow Kotlin JVM continua exigindo acionamento explícito manual. O resultado definitivo dos testes JVM e do build deve ser registrado somente após cada workflow pertinente no SHA final terminar em `completed/success`; a existência dos arquivos não é evidência de aprovação.


## Runtime contract tests

`android/app/src/test/kotlin/com/reiflix/reiflix_local/data/sqlite/ReiAnixSqliteRepositoryRuntimeTest.kt` uses Robolectric to run Android SQLite APIs in the JVM; no device/emulator tests were added. The suite covers a v29 compatibility fixture saved at `filesDir/data/library.sqlite3`: catalog/schema reads, SQLite `quick_check`, reopening connections, playback persistence, transaction rollback, preferences round-trip, manual metadata ownership, and refusal to create/replace missing or corrupt databases. The fixture models the columns consumed by the native repositories and the expected required table names; it is not a binary snapshot of a production user database. Existing Python regression tests continue to validate the canonical Python schema/migration contract.
