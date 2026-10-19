# Migração da persistência SQLite para Kotlin — Prompt 03/18

## Escopo e commit de referência

- Repositório oficial: `reinansantos9090-debug/ReiAnix`.
- Branch-base observada antes das alterações: `main`.
- Commit-base: `193621e088f0aee1ef98b7e4b1524e53cd76d461` (merge do Prompt 02/18).
- Branch deste trabalho: `migration/prompt-03-sqlite-persistence-20261019`.
- Objetivo: compartilhar uma única resolução do arquivo SQLite entre o repositório nativo e o bootstrap, documentar a equivalência com o caminho usado pelo Flet e preservar a base/schema existentes.

## Estado encontrado e funções que já existiam

A camada SQLite Kotlin não começou do zero neste prompt. Na base de referência já existiam:

| Arquivo ou módulo | Responsabilidade existente | Situação nesta etapa |
|---|---|---|
| `core/library_store.py` | Fonte legada do schema e inicialização do SQLite; declara `LibraryStore.SCHEMA_VERSION = 29` e usa `data_dir/library.sqlite3`. | Preservado como compatibilidade do runtime híbrido e como fonte do contrato de schema. |
| `android/app/src/main/kotlin/com/reiflix/reiflix_local/data/sqlite/ReiAnixSqliteDatabase.kt` | Abertura de arquivo existente, validação do contrato gerado a partir do `LibraryStore`, consultas de integridade, serialização por instância e transações. Não cria nem migra a base. | Ajustado para expor o objeto `canonicalDatabaseFile` como referência de caminho compartilhada. |
| `ReiAnixSqliteLibraryRepository.kt` e `ReiAnixSqliteLibraryMutations.kt` | Consultas paginadas, leitura por IDs/caminhos/identidade e atualizações transacionais de consumo, metadados e campos pessoais já suportados. | Reutilizados; não foi criado outro repositório nem outro conjunto de modelos. |
| `ReiAnixSqliteModels.kt` e `SqliteDomainMappers.kt` | Representação dos registros persistidos e conversão para os contratos Kotlin existentes. | Preservados. |
| `ReiAnixLibraryQueryService.kt`, `ReiAnixLibraryService.kt`, `ReiAnixConsumptionService.kt`, `ReiAnixPersonalListService.kt`, repositórios de conta/configurações e `ReiAnixAppDependencies.kt` | APIs nativas e grafo compartilhado para consumidores já migrados. | Reutilizados; sem arquitetura concorrente. |
| `ReiAnixAppBootstrap.kt` | Decide se a base canônica está saudável antes de recuperar recibos SAF ou iniciar o dispatcher. | Alterado para consultar o mesmo caminho exposto pelo limite SQLite, em vez de reconstruir independentemente o caminho/arquivo. |
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

- Schema antes/depois: **v29**, conforme `LibraryStore.SCHEMA_VERSION`; nenhuma migração estrutural é necessária ou foi executada.
- `PRAGMA user_version` permanece conforme o contrato canônico existente.
- Nenhuma tabela, coluna, índice ou restrição foi removida ou adicionada.
- Nenhum segundo arquivo SQLite, cópia ativa, tabela paralela ou fonte de verdade foi introduzido.
- A abertura nativa usa modos de leitura/leitura e escrita sobre um arquivo existente, verifica que o arquivo existe e não está vazio e rejeita contratos/integridade incompatíveis. Não abre com flag de criação e não apaga/recria uma base em caso de falha.
- As operações nativas usam SQL parametrizado e transações onde o contrato exige atomicidade. O caminho de bootstrap só inspeciona a base; não executa migrações nem corrige o schema automaticamente.
- A inicialização de uma instalação nova ainda pode ser feita pelo `LibraryStore` enquanto o runtime permanece híbrido. Essa compatibilidade temporária não autoriza o Kotlin a criar uma segunda base.
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

1. `android/app/src/main/kotlin/com/reiflix/reiflix_local/data/sqlite/ReiAnixSqliteDatabase.kt`: expõe a referência interna do arquivo canônico sem mudar o nome nem o local do arquivo.
2. `android/app/src/main/kotlin/com/reiflix/reiflix_local/ReiAnixAppBootstrap.kt`: obtém o arquivo e o diretório-pai através do limite SQLite compartilhado, evitando uma segunda montagem independente do caminho.
3. `docs/KOTLIN_SQLITE_PERSISTENCE_MIGRATION.md`: registra o estado de referência, as funcionalidades reaproveitadas, o contrato de caminho/schema, as limitações híbridas e as regras de validação.

Não foram removidos arquivos ou diretórios. Nenhum arquivo de teste ou workflow foi alterado.

## Pendências que continuam explícitas

A etapa não declara independência integral de Python/Flet nem transfere todos os escritores de `LibraryStore`. Os chamadores legados devem ser migrados em etapas próprias, com equivalência semântica, ownership de escrita único por operação e validação de não regressão. O veredito final desta etapa depende do build/CI terminal da branch/PR e da avaliação dos critérios de aceitação; resultados de commits anteriores não valem como resultado do SHA final.
