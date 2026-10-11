# Persistência SQLite nativa — Prompt 3

## Estado inicial verificado

- Repositório: `reinansantos9090-debug/ReiAnix`.
- Branch de referência: `main`.
- SHA completo inicial: `495ad3b3c80243ef0a5be97a002e63d0d4a76810`.
- Fonte canônica atual: `core/library_store.py`, `LibraryStore.SCHEMA_VERSION = 29`.
- Arquivo canônico esperado pelo host Android e pelos testes de regressão existentes: `filesDir/data/library.sqlite3`.
- As tabelas reais observadas incluem `anime`, `episodes`, `folders`, `artwork`, `preferences`, `account` e `schema_migrations`.

## Implementação Kotlin

O pacote `data/sqlite` contém:

- `ReiAnixSqliteDatabase`: abre o arquivo existente em modo somente leitura ou leitura/escrita, habilita chaves estrangeiras, aplica `busy_timeout`, oferece transações e inspeciona tabelas, versão registrada e `PRAGMA quick_check`. Não cria diretórios/bancos, tabelas, índices ou migrações e não substitui um arquivo ausente/corrompido.
- `ReiAnixSqliteLibraryRepository`: leitura paginada de anime, consultas por IDs persistidos, episódios por anime, episódios recentes, pastas e artwork; atualização transacional de progresso no registro existente.
- `ReiAnixSqliteSettingsRepository`: leitura, gravação transacional e remoção explícita de preferências na tabela existente.
- `ReiAnixSqliteModels`: registros Kotlin para as colunas existentes. Valores nulos e colunas JSON/textuais são mantidos sem normalização destrutiva. IDs de anime, episódio e mídia não são gerados novamente.

As APIs dos repositórios usam corrotinas em `Dispatchers.IO` para evitar I/O de SQLite na thread principal. Os valores externos são vinculados por placeholders SQLite; não são interpolados em SQL.

## Compatibilidade e fonte da verdade

Nenhuma alteração foi feita no esquema 29; não foi adicionada versão, tabela ou índice. O banco usado pelo Kotlin é o mesmo arquivo `library.sqlite3`, não uma cópia nem um catálogo paralelo. Falhas de abertura, consulta e escrita são propagadas; uma leitura com erro não vira uma lista vazia.

O fluxo de Compose existente ainda usa `ReiAnixLibraryRepository` e `ReiAnixSettingsRepository` como adapters de projeção/IPC. A biblioteca Python (`LibraryStore`, `LibraryService`) continua sendo a escritora usada por esses fluxos e pelo pipeline de `NativeMailbox`. As novas operações de escrita Kotlin são APIs reais sobre o mesmo SQLite, mas não devem ser chamadas para uma mutação que ainda esteja sendo enviada ao Python; cada operação precisa ser cortada para um único escritor. O player e `PlayerLocalMetadataStore` não foram alterados neste prompt.

O progresso e a data de reprodução estão persistidos nas colunas da tabela `episodes`; não foi criada uma tabela de histórico paralela. A recuperação/backup do banco continua pertencendo aos serviços existentes até que uma etapa posterior migre esses comportamentos com paridade comprovada.

## Testes

- `ReiAnixSqliteModelsTest` valida a validação pura dos dados de progresso sem depender de emulador.
- `test_native_sqlite_persistence_contract.py` verifica os contratos de caminho, esquema, transação, consultas parametrizadas e ausência de criação destrutiva.
- Os testes acima não substituem a validação Android integrada do banco no dispositivo. Não foram adicionados testes instrumentados ou workflows de emulador.

## Pendências explícitas

- Migrar consumidores individuais da mailbox/projeções para estes repositórios só depois de verificar equivalência das regras e remover o escritor Python correspondente para cada operação.
- Migrar validação de configuração, ingestão/reconciliação, recuperação/backup e eventos completos do player exige trabalho de domínio e paridade fora do escopo desta camada.
- A execução de testes e build ainda precisa ser confirmada por um run CI concluído; a existência destes arquivos não é evidência de build aprovado.
