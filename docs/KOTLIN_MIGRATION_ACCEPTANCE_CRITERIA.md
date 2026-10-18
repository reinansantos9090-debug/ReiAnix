# Critérios de aceitação — migração Kotlin nativa do ReiAnix

**Escopo documental histórico:** Prompt 01/18 (auditoria). A seção 5 registra os critérios específicos do Prompt 02/18.  
**Baseline:** `80fd95acd0d569d3013ac2d71fcf4cd166ae7844`.  
**Meta global:** Android Kotlin/Jetpack Compose sem Python/Flet/Flutter no runtime e no pacote final, preservando funções/dados/testes existentes.

## 1. Aceitação específica do Prompt 01/18

| ID | Critério | Condição verificável | Evidência exigida |
|---|---|---|---|
| A01 | Baseline oficial | Main no SHA fixado antes da escrita; branch deriva da história atual; main intocada. | Refs de Git e compare main↔branch. |
| A02 | Inventário Python | 217 arquivos Python / 66,062 LOC, com 164 testes Python separados de runtime/tooling. | Tabela completa com símbolos/imports/consumidores/efeitos capturados e limites declarados. |
| A03 | Inventário Kotlin | 184 Kotlin files / 68,419 LOC; produção e testes atuais separados. | Tabela completa; testes atuais preservados. |
| A04 | Runtime/entrypoint | main.py Flet, Activity FlutterFragmentActivity e Application FlutterApplication documentados. | Fontes no SHA auditado. |
| A05 | Build | Python/Flet/Flutter/template/Gradle híbridos documentados. | pyproject, prepare_flet_template, Gradle e workflow consultados. |
| A06 | SQLite | LibraryStore schema v29, nome/caminho, Kotlin DB contract e gap do diretório documentados. | Fontes Python/Kotlin; sem schema change/DB paralelo. |
| A07 | Matriz | Contratos/consumidores e gaps para IPC/storage/scan/merge/metadata/artwork/UI/player/recovery. | Matriz e gates vinculados aos prompts futuros. |
| A08 | Plano 18 prompts | Prompts 02–18 com escopo e critérios verificáveis. | Documento do plano. |
| A09 | Critérios de aceitação | DoD documental e critérios globais funcionais definidos. | Documento de critérios. |
| A10 | Histórico 25–30 | PRs 190–194 e restaurações 195–197 contextualizadas; não presumir que commits supersedidos definem o tree atual. | Inventário usa o SHA da main restaurada como fonte de verdade. |
| A11 | Não adicionar testes/infra | Nenhum teste, workflow, script, job ou emulador novo; nenhum workflow Kotlin/JVM manual disparado. | Diff restrito aos quatro documentos e workflow tree preservado. |
| A12 | Arquivos da PR | Exatamente quatro docs em comparação com main; o JSON auxiliar temporário foi removido do tree final da branch. | Compare e lista de arquivos da PR. |
| A13 | PR/document commit | PR aberta, head SHA correto, base main e sem merge automático. | Metadados verificáveis da PR. |
| A14 | CI aplicável | build_apk.yml da pull_request conclui success no SHA final; inspeção de jobs/artefatos reais. | Run ID/URL/status/conclusion e lista de artifacts. |
| A15 | Workflows manuais | Release certification não tratada como sucesso sem run; Kotlin/JVM manual não acionado. | Triggers e runs consultados. |
| A16 | Relatório final | Branch, base/parent/final SHA, PR, arquivos/linhas, CI, artifacts, testes não executados e pendências. | Links ao commit, PR e run final. |

## 2. Prova estática não é prova de runtime

Não satisfaz a meta final: Kotlin compilar dentro do host Flet; produzir APK híbrido; encontrar classe com nome parecido; chamar Compose via bridge Python; migrar apenas consultas sem gravações/merge; ou comparar somente extensões. O produto final precisa ter entrypoint, runtime e empacotamento nativos sem Python/Flet/Flutter.

## 3. Invariantes por domínio para o aceite global

| Domínio | Critérios mínimos |
|---|---|
| SQLite/dados | Mesmo arquivo canônico, schema v29 sem alteração não autorizada, IDs/constraints/associações/transações preservados, sem DB paralela. |
| SAF/storage | URI/grants persistidos, identidade de fonte/volume, revogação/reseleção, cancelamento e Back corretos. |
| Scanner | Estados terminais, progress, ownership/generation, cancelamento, recovery e dedup equivalentes. |
| Biblioteca/discovery | Merge, filtros/ordenação e identidade de episódio equivalentes; sem duplicatas; metadata manual preservada. |
| AniList/artwork | Associação por ID, rate-limit/cache/pt-BR, arte local, invalidação e refresh corretos. |
| Settings/account | Defaults, validação e persistência, sem reset silencioso de valores desconhecidos. |
| Backup/recovery | Preview, validação de versão/conteúdo, restore seguro; corrupção/incompatibilidade não causa perda de dados. |
| Compose/navigation | Home, Library, My List, Search, Details, Organize, Storage, Settings e rotas auxiliares funcionam com Back/intents/estados. |
| Player | Media3/ExoPlayer, URI SAF, resume/seek/progress/history, autoplay, next/previous, lifecycle/Back/PiP. |
| Build/runtime | Entry point nativo e APK sem runtime Python/Flet/Flutter, bundle Flet ou host Flutter. |

## 4. Restrições não negociáveis

- Não adicionar testes, workflows, scripts, jobs, emuladores ou infraestrutura de emulador.
- Não disparar/rodar teste ou workflow Kotlin/JVM manual sem autorização.
- Não alterar main, fazer merge automático, reescrever histórico ou force push.
- Não alterar applicationId, namespace, assinatura ou schema v29 sem justificativa técnica e escopo autorizado.
- Não remover bridge/serviço com consumidores ativos nem adicionar stubs, fallback vazio ou sucesso falso.
- Workflow ausente/queued/in_progress/failed/cancelled/skipped não é sucesso. Triggers que não se aplicam devem ser reportados como não executados, não aprovados.
- Testes e scripts Python existentes podem permanecer fora do runtime Android; não apagar tooling para forçar 100% por contagem de extensões.

## 5. Estado de base

O snapshot auditado continua híbrido. A equivalência do caminho SQLite precisa ser provada e existem regras de merge ainda atribuídas ao LibraryStore. O Prompt 01 documenta inventário, dependências, sequência e critérios; não declara migração funcional concluída.

## 6. Aceitação específica do Prompt 02/18 — domínio de mídia

| ID | Critério | Condição verificável | Evidência exigida |
|---|---|---|---|
| P02-01 | Branch e baseline | Branch dedicada parte da `main` oficial; `main` não recebe commits do prompt diretamente. | Branch, parent SHA e compare oficial. |
| P02-02 | Reaproveitamento | Reutilizar modelos, identificadores, mapeadores e registry Kotlin existentes; não criar classes ou registries concorrentes. | Diff e referências no código. |
| P02-03 | Localização de mídia | Preservar o valor armazenado e distinguir path filesystem, `file://`, `content://`, SAF tree, SAF document e outros esquemas. | `MediaDomainModels.kt`; nenhuma alteração de persistência. |
| P02-04 | Parser conservador | Marcadores explícitos/sufixos admitem episódios decimais; filename apenas numérico não cria identidade de episódio. | `MediaFilenameParser.kt` e contrato documental. |
| P02-05 | Identidade estável | IDs de anime/episódio/AniList distintos; título, tamanho ou `modifiedAt` não são identidade física isoladamente. | Modelos, `MediaIdentity.kt` e mapeadores. |
| P02-06 | Gêneros | Reutilizar registry/classifier; canonicalização, aliases e metadados AniList/manual preservados. | Registry/classifier e documentação. |
| P02-07 | SQLite | Schema v29, IDs, tabelas, colunas e arquivo canônico inalterados; mapeadores continuam read-only. | Diff sem mudanças nos arquivos do schema. |
| P02-08 | Python legado | Manter módulos Python enquanto houver consumidores ativos; registrar zero arquivos/linhas Python removidos se os módulos forem preservados. | Consumidores e relatório. |
| P02-09 | Testes/workflows | Não adicionar nem alterar testes/workflows; não executar manualmente os testes Kotlin/JVM reservados. | Diff e execuções oficiais reais. |
| P02-10 | Validação | Build/workflows reportados separadamente; conclusão exige status terminal `success` no SHA final. | SHA final, PR, runs, artefatos e diff. |

### Fronteiras após P02

Esta etapa não migra a orquestração do scanner, LibraryService, discovery/missing/observations, nem desativa o runtime híbrido. A ligação do parser/modelos ao fluxo nativo ativo permanece no Prompt 11; os serviços e consumidores Python continuam até uma troca segura ser implementada e validada. O Prompt 03 não foi iniciado por este registro.


## 7. Limites de classificação URI/SAF e Unicode

A classificação de localização é uma projeção sintática pura-JVM, não substitui `DocumentsContract.isDocumentUri(context, uri)` nem prova que o provider existe ou que a aplicação possui grants. `MediaReferenceSyntax` só identifica SAF para autoridades DocumentsProvider explicitamente reconhecidas e segmentos completos `/tree/{id}`, `/document/{id}` ou `/tree/{treeId}/document/{documentId}`. Strings vazias são `UNRESOLVED`; conteúdo malformado com esquema `content:` permanece `CONTENT_URI`; provedor desconhecido não é promovido a SAF.

`normalizeReiAnixGenre` preserva NFKD, remoção de marcas, separadores e compatibilidade explícita para `ß`/`ss` e `ς`/`σ`. Não é uma implementação completa de todo o mapeamento Unicode de `str.casefold()`; não deve ser descrita como equivalência integral.
