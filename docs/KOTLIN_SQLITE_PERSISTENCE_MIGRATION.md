# Migração da persistência SQLite para Kotlin — Prompt 03/18

## Escopo e commit de referência

- Repositório oficial: `reinansantos9090-debug/ReiAnix`.
- Branch-base observada antes das alterações: `main`.
- Commit-base: `193621e088f0aee1ef98b7e4b1524e53cd76d461` (merge do Prompt 02/18).
- Branch deste trabalho: `migration/prompt-03-sqlite-persistence-20261019`.
- Objetivo: fazer a inicialização nativa criar ou avançar com segurança o schema SQLite v29 no arquivo canônico, compartilhar uma única resolução de caminho, e preservar a base/schema e os dados existentes.

## Estado encontrado e funções que já existiam

A camada SQLite Kotlin não começou do zero neste prompt. Na base de referência já existiam:

| Arquivo ou módulo | Responsabilidade existente | Situação nesta etapa |
|---|---|---|
| `core/library_store.py` | Implementação legada do domínio e fonte de referência do schema; declara `LibraryStore.SCHEMA_VERSION = 29` e usa `data_dir/library.sqlite3`. | Preservado para operações Python/compatibilidade; o contrato de DDL é exportado no build e executado pelo bootstrap Kotlin. O corte de todos os consumidores Python continua pendente. |
| `android/app/src/main/kotlin/com/reiflix/reiflix_local/data/sqlite/ReiAnixSqliteDatabase.kt` | Limite único de arquivo/conexão, validação do contrato gerado a partir do `LibraryStore`, integridade e transações. | Expõe o arquivo canônico, compartilha o contrato gerado e executa operações de bootstrap sob o mesmo bloqueio da conexão. Não contém DDL manual. |
| `ReiAnixSqliteLibraryRepository.kt` e `ReiAnixSqliteLibraryMutations.kt` | Consultas paginadas, leitura por IDs/caminhos/identidade e atualizações transacionais de consumo, metadados e campos pessoais já suportados. | Reutilizados; não foi criado outro repositório nem outro conjunto de modelos. |
| `ReiAnixSqliteModels.kt` e `SqliteDomainMappers.kt` | Representação dos registros persistidos e conversão para os contratos Kotlin existentes. | Preservados. |
| `ReiAnixLibraryQueryService.kt`, `ReiAnixLibraryService.kt`, `ReiAnixConsumptionService.kt`, `ReiAnixPersonalListService.kt`, repositórios de conta/configurações e `ReiAnixAppDependencies.kt` | APIs nativas e grafo compartilhado para consumidores já migrados. | Reutilizados; sem arquitetura concorrente. |
| `ReiAnixSqliteSchemaBootstrap.kt` | Não existia antes desta etapa. | Inicializa instalações novas e aplica alterações aditivas seguras a schemas anteriores a v29 usando DDL exportado do contrato canônico, numa única transação. Recria índices explícitos sem reconstruir tabelas nem copiar registros. |\n| `ReiAnixAppBootstrap.kt` | Decide se a base canônica está saudável antes de recuperar recibos SAF ou iniciar o dispatcher. | Verifica evidências de recuperação primeiro e só então chama o bootstrap de schema nativo; a base é revalidada antes do início dos serviços. |
| `android/app/build.gradle.kts`, scripts de fixture e workflows atuais | Geração do contrato SQLite a partir do `LibraryStore` e validação/build existentes. | Mantidos; nenhum workflow novo foi criado. |

## Resolução do caminho canônico

O projeto fixa `flet==0.86.5` em `pyproject.toml`. A documentação oficial desta versão informa que, no Android, `FLET_APP_STORAGE_DATA` corresponde a `/data/data/<package>/files/data`:
- [Flet 0.86 — mudança de localização dos diretórios de dados](https://flet.dev/docs/updates/breaking-changes/v0-86-0/app-files-unpacked-read-only-bundle/)
- [Variáveis de ambiente do Flet](https://flet.dev/docs/reference/environment-variables/)

O caminho nativo `Context.filesDir/data/library.sqlite3` representa o mesmo diretório privado do pacote Android. Portanto, o contrato encontrado é:

```text
Python/Flet: FLET_APP_STORAGE_DATA/library.sqlite3
Android:    Context.filesDir/data/library.sqlite3
Arquivo:    library.sqlite3 (um único arquivo canônico)
Schema:     v29
```

Essa equivalência é derivada da versão de Flet fixada no repositório e da resolução Android documentada; não depende de copiar ou sincronizar bancos. O bootstrap Kotlin agora usa `ReiAnixSqliteDatabase.canonicalDatabaseFile`, a mesma referência usada pelo limite de persistência, para verificar presença, tamanho, evidências de promoção interrompida e sinais de recuperação.

## Preservação e integridade dos dados

- Versão-alvo e versão final: **schema v29**, conforme `LibraryStore.SCHEMA_VERSION`; `PRAGMA user_version` permanece em **0**, que é o contrato atual. O bootstrap pode inicializar schema novo ou avançar schema anterior a v29 sem incrementar a versão-alvo.
- `PRAGMA user_version` permanece conforme o contrato canônico existente.
- O bootstrap não descarta tabelas nem colunas, nem substitui o arquivo ou copia registros. Para uma base anterior, adiciona apenas colunas compatíveis ausentes e recria índices explícitos/acionadores definidos no contrato dentro de uma transação; se a validação final falhar, a transação é revertida. Novas instalações recebem o mesmo DDL canônico v29.
- Nenhum segundo arquivo SQLite, cópia ativa, tabela paralela ou fonte de verdade foi introduzido.
- As operações comuns de leitura/escrita continuam abrindo a base existente. Só o bootstrap nativo pode usar `CREATE_IF_NECESSARY`, e apenas depois de confirmar ausência do arquivo e de todas as evidências duráveis de recuperação; não é usado para sobrescrever arquivo existente. Arquivo vazio/inválido, integridade corrompida, FK inválida, `user_version` não suportada ou schema v29 incompatível mantêm o Recovery Mode bloqueando operações normais.
- As operações nativas usam SQL parametrizado e transações onde o contrato exige atomicidade. O caminho de bootstrap só inspeciona a base; não executa migrações nem corrige o schema automaticamente.
- O bootstrap Kotlin inicializa a base em instalação nova antes de liberar os serviços nativos. `LibraryStore` permanece no runtime híbrido para operações de domínio legadas e continua apontando para o mesmo arquivo; sua presença não cria uma segunda base, mas também significa que o corte completo da persistência Python ainda não foi concluído.
- A preservação do arquivo e dos registros existentes foi mantida como comportamento de código. Não foi usado nem alterado banco pessoal real neste trabalho.

## Consumidores e limite da migração

Os componentes Kotlin já integrados utilizam o arquivo canônico: consultas da biblioteca, estado assistido/progresso, favoritos/lista pessoal e operações nativas já suportadas de metadados/campos pessoais, configurações e conta. A inserção/atualização de mídia descoberta permanece ligada ao indexador/fluxo nativo existente; esta etapa não reimplementa o scanner.

O runtime ainda é híbrido. `main.py` cria `LibraryStore(data_dir)` com `FLET_APP_STORAGE_DATA` e usa `LibraryService`; partes da ingestão/merge do catálogo, reconciliação, edição/matching de metadados, publicação de snapshots e outros fluxos legados ainda possuem consumidores Python/IPC. Eles não foram removidos aqui, pois ainda precisam de paridade por operação e de transferência segura de ownership. A existência de uma API Kotlin equivalente não significa que todos os chamadores Python já tenham sido cortados.

## Testes, build e workflows

- Nenhum teste foi criado, removido ou modificado neste trabalho.
- Nenhum teste JVM foi executado manualmente.
- Não foram adicionados testes instrumentados/de emulador nem novos workflows.
- A suíte Kotlin/JVM continua sob o gatilho manual existente; a compilação/build será considerada validada somente pelo resultado terminal do workflow oficial associado ao SHA final desta branch.
- Testes Python ou outras verificações executados automaticamente pelo workflow existente serão relatados a partir do resultado real do run, não inferidos pela presença dos arquivos de teste.
- A equivalência de caminho está documentada por referência à versão fixada do Flet. Não houve teste em dispositivo contra um banco pessoal.

## Arquivos alterados nesta etapa

1. `scripts/generate_native_sqlite_test_fixture.py`: inclui no contrato gerado as instruções DDL de tabelas, índices e triggers extraídas da base temporária inicializada pelo `LibraryStore`. O fixture e o contrato de runtime continuam gerados pelo único passo já existente no workflow.
2. `android/app/src/main/kotlin/com/reiflix/reiflix_local/data/sqlite/ReiAnixSqliteDatabase.kt`: expõe a referência interna do arquivo, o contrato gerado e um limite exclusivo para bootstrap, serializado com leituras/escritas nativas.
3. `android/app/src/main/kotlin/com/reiflix/reiflix_local/data/sqlite/ReiAnixSqliteSchemaBootstrap.kt`: inicializa/migra o schema nativamente em uma transação, aplicando somente diferenças aditivas compatíveis e validando novamente a integridade/contrato antes do commit.\n4. `android/app/src/main/kotlin/com/reiflix/reiflix_local/ReiAnixAppBootstrap.kt`: impede que a inicialização nativa contorne evidências de recuperação e verifica a base antes do dispatcher.\n5. `docs/KOTLIN_SQLITE_PERSISTENCE_MIGRATION.md`: registra o mecanismo, as garantias e as limitações restantes.

Não foram removidos arquivos ou diretórios. Nenhum arquivo de teste ou workflow foi alterado.

## Pendências que continuam explícitas

O bootstrap da inicialização/schema agora é nativo, mas esta etapa ainda não remove todos os consumidores Python de `LibraryStore`, o serviço `LibraryService`, nem os fluxos legados de ingestão, merge/reconciliação, metadados e snapshots. Esses chamadores precisam migrar por operação, com equivalência semântica e ownership único de escrita antes que Python possa ser removido do runtime. O contrato DDL ainda é gerado no build a partir da implementação legada como mecanismo de paridade; o schema v29 em runtime já pode ser inicializado pelo Kotlin. O veredito deste incremento depende do workflow oficial do SHA final, build e artefatos; resultados de commits anteriores não valem como validação deste código.
