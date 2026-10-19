# Prompt 04.1/18 — fechamento funcional do armazenamento Android e da indexação nativa

## Resultado desta etapa

**Foram encontrados e corrigidos dois defeitos funcionais nos scanners existentes.** O SAF podia classificar uma varredura como completa apesar de rejeições de escopo documentadas; agora erros de cobertura fazem o resultado ser parcial ou indisponível, nunca vazio/completo. No MediaStore, um cursor nulo na consulta final de estabilidade era interpretado como ausência de vídeos; agora esse caso é registrado como erro e impede a conclusão de uma geração vazia. As correções reaproveitam as implementações ativas, não criam scanner/coordenador/banco paralelo e não alteram a política de persistência.

Referência da `main` usada ao iniciar esta etapa: merge do Prompt 03/18, commit `d3ed5e3f44a84fd665a6fdee97cecb3d3af5bcc5`. A PR #201 já existia, estava aberta e tinha apenas a documentação do Prompt 04; esta correção foi anexada à mesma branch por commits novos, sem reescrever histórico. A `main` não foi alterada.

**Discrepância de numeração identificada no plano oficial:** `docs/KOTLIN_MIGRATION_18_PROMPT_PLAN.md` define Prompt 04 como contratos IPC/bridge, Prompt 05 como SAF/permissões e Prompt 06 como scanner. Este trabalho segue o escopo corretivo explícito do Prompt 04.1/18 enviado pelo usuário e não inicia o Prompt 05/18.

PRs do ciclo 01/18–03/18 verificadas no GitHub:
- [PR #198 — Prompt 01/18](https://github.com/reinansantos9090-debug/ReiAnix/pull/198): fechada e mesclada.
- [PR #199 — Prompt 02/18](https://github.com/reinansantos9090-debug/ReiAnix/pull/199): fechada e mesclada; merge commit `193621e088f0aee1ef98b7e4b1524e53cd76d461`.
- [PR #200 — Prompt 03/18](https://github.com/reinansantos9090-debug/ReiAnix/pull/200): fechada e mesclada; merge commit `d3ed5e3f44a84fd665a6fdee97cecb3d3af5bcc5`. O bootstrap SQLite Kotlin utiliza o mesmo arquivo canônico `filesDir/data/library.sqlite3`; o contrato alvo permanece v29 e não há banco de biblioteca paralelo.

As implementações de armazenamento e indexação já integradas incluem também [PR #173 — configuração de fontes SAF e gatilhos de varredura](https://github.com/reinansantos9090-debug/ReiAnix/pull/173), [PR #174 — serviço nativo de indexação e reconciliação](https://github.com/reinansantos9090-debug/ReiAnix/pull/174), [PR #182 — onboarding/permissões de armazenamento](https://github.com/reinansantos9090-debug/ReiAnix/pull/182), além das correções de recuperação e coordenação que foram integradas posteriormente. Os estados das PRs foram consultados, não inferidos apenas pela existência dos branches.

## 1. Matriz do estado encontrado antes desta mudança

| Componente | Implementação atual | Consumidores reais | Dependência Python/Flet remanescente | Dependência Android | Comportamento preservado e alteração desta etapa | Risco e evidência |
|---|---|---|---|---|---|---|
| Seleção de pasta SAF | `MainActivity`, `ActivityResultContracts`, `SafScanner` | Onboarding Compose, ações nativas de armazenamento e solicitações por intent | Fluxos Flet antigos ainda podem solicitar operações por compatibilidade | DocumentsUI, URI `content://`, `ContentResolver`, `DocumentsContract` | Já implementado. Reutilizado; não criar outro launcher ou tela | Validação em `MainActivity.handleTreePickerResult`: resultado válido → grant persistente verificado → `upsertSafFolder` no SQLite → varredura. Cancelamento não substitui a pasta válida |
| Grants SAF e estado de autorização | `SafScanner`, `StorageAuthorization`, estado nativo de onboarding | Activity, ViewModel/telas Compose de Storage e Library | Ponte/estado legado segue ativo para consumidores que ainda não migraram | `ContentResolver.persistedUriPermissions`, `takePersistableUriPermission`, consulta à árvore | Já implementado. A URI salva não é considerada autorização por si só; revogação/provedor inacessível são estados distintos | Código diferencia `REVOKED`, `UNAVAILABLE`, `FAILED` e estados completos; erro não autoriza exclusão de catálogo |
| Descoberta SAF | `SafScanner` com enumeração via provider autorizado | `NativeScanRunner` | Eventos compatíveis ainda podem seguir por `NativeMailbox` | `DocumentsContract`, `ContentResolver`, `DocumentFile` onde adequado | Já integrado no fluxo de indexação; regras existentes e extensões de vídeo são preservadas | Arquivos aninhados, MIME/nome ausentes e falhas localizadas devem permanecer explícitos no resultado |
| MediaStore | `MediaStoreScanner`, observador de mudanças e `MediaStoreRetryScheduler` | Entry points nativos de Activity/runner | Diagnóstico/eventos de compatibilidade permanecem para clientes antigos | Permissões runtime conforme API, URIs MediaStore e volumes | Já implementado; a consulta nativa é reutilizada. MediaStore/Broad Storage não viram automaticamente novas fontes canônicas de biblioteca | Evita duplicar itens já descobertos pelo SAF ou transformar uma visão global/parcial em reconciliação destrutiva |
| Coordenação e lifecycle | `NativeScanController` + `NativeScanRunner` | `MainActivity`, gatilhos nativos e estado da UI Compose | `core/scan_coordinator.py` continua necessário para pedidos originados por fluxos Flet legados | Coroutines, estado observável, cancelamento cooperativo | Já integrado como coordenador nativo único dos fluxos Android; não criar segundo scanner/coordenador | Reserva por chave de fonte; estados terminais distintos; uma chamada bloqueada dentro de provider pode atrasar a observação de cancelamento |
| Staging, lotes e recuperação | `NativeIndex`, snapshots de descoberta e protocolo de recovery | `NativeScanRunner` | Mailbox conserva eventos de diagnóstico/compatibilidade; não é o writer canônico do snapshot SAF atual | Armazenamento privado do app e APIs Android de mídia | Já integrado; o índice/staging não substitui o catálogo SQLite | Recuperação desconhecida/parcial não autoriza reconciliação de ausências |
| Identidade e persistência canônica | `ReiAnixLibraryService`, `ReiAnixSqliteLibraryRepository`, `ReiAnixSqliteDatabase` | Runner de indexação, serviços/ViewModels nativos e consumidores do catálogo Compose | `LibraryStore`/serviços Python seguem ativos em fluxos legados, não como segundo writer do caminho Android normal | SQLite Android, transações e dispatcher IO | Já integrado; reaproveitado sem banco/tabela de biblioteca paralelos, mantendo schema v29 | Reconciliar ausências apenas com snapshot completo, autorizado e com cobertura verificada |
| Interface/onboarding | Estado de onboarding Android, Activity e ViewModels/telas Compose existentes | Tela de Storage, estado da Library e gatilhos de scan | Estado/eventos Python ainda servem consumidores legados | Activity Result, lifecycle e permissões runtime | Já conectado aos estados nativos; nenhuma tela duplicada foi criada | A UI só deve indicar sucesso após validar autorização e persistência; falha de leitura não deve ser mostrada como biblioteca vazia |
| Manifesto e Gradle | `android/app/src/main/AndroidManifest.xml`, `android/app/build.gradle.kts` | Build e fluxo Android | Host Flet/Flutter ainda faz parte do empacotamento do aplicativo | `READ_EXTERNAL_STORAGE` limitado à API 32, `READ_MEDIA_VIDEO`, `READ_MEDIA_VISUAL_USER_SELECTED` e `MANAGE_EXTERNAL_STORAGE` opcional para o recurso Broad Storage | Configuração já presente; nenhum permissionamento amplo adicional foi introduzido | O acesso amplo só é efetivo após autorização runtime; SAF não exige `MANAGE_EXTERNAL_STORAGE` |

## 2. Fluxo real de seleção SAF

A seleção de pasta usa o launcher Android existente em `MainActivity`. O fluxo de retorno verifica o resultado e a URI antes de persistir qualquer mudança. `SafScanner.persistPermission` valida que se trata de uma árvore `content://`, retém apenas os grants oferecidos pelo sistema, confirma a concessão persistente de leitura e inspeciona o provider. Em seguida, a pasta é registrada por `ReiAnixSqliteLibraryRepository.upsertSafFolder` e o scan é disparado somente depois de a configuração ter sido persistida.

As raízes são recuperadas do SQLite canônico e reconciliadas com `ContentResolver.persistedUriPermissions`. Concessão ausente, URI inválida e provider temporariamente indisponível não são convertidos em diretório vazio nem removem registros. O usuário pode recuperar acesso selecionando novamente uma pasta. O fluxo não tenta derivar um caminho físico de URIs SAF arbitrárias.

Evidências de código:
- `android/app/src/main/kotlin/com/reiflix/reiflix_local/MainActivity.kt`
- `android/app/src/main/kotlin/com/reiflix/reiflix_local/scanner/SafScanner.kt`
- `android/app/src/main/kotlin/com/reiflix/reiflix_local/storage/StorageAuthorization.kt`
- `android/app/src/main/kotlin/com/reiflix/reiflix_local/data/sqlite/ReiAnixSqliteLibraryRepository.kt`

## 3. MediaStore e permissões por versão Android

`MediaStoreScanner.requiredPermissions()` determina o conjunto de permissões relevante por versão, e `accessLevelValue()` reconsulta o estado real do sistema antes da varredura. O scanner usa coleções de vídeo e volumes externos disponibilizados pelo Android, trata o acesso parcial como escopo limitado e registra as mudanças pelo observer existente. O trabalho de scan é encaminhado ao executor nativo, não executado em recomposição Compose.

O MediaStore é consultado quando o fluxo nativo solicita essa fonte e para diagnóstico/descoberta sob o contrato atual. Não é mesclado automaticamente ao catálogo SAF: isso é intencional para evitar duplicações e para impedir que uma consulta global ou com acesso parcial autorize exclusões de registros provenientes de pastas configuradas pelo usuário.

Evidências de código:
- `android/app/src/main/kotlin/com/reiflix/reiflix_local/scanner/MediaStoreScanner.kt`
- `android/app/src/main/kotlin/com/reiflix/reiflix_local/scanner/MediaStoreRetryScheduler.kt`
- `android/app/src/main/AndroidManifest.xml`

## 4. Descoberta, estados e coordenação

O caminho normal Android já usa:

```text
MainActivity
  → NativeScanController
  → NativeScanRunner
  → SafScanner / MediaStoreScanner / BroadStorageScanner
  → NativeIndex (staging, lotes e snapshots)
  → NativeLibraryScanSnapshot
  → ReiAnixLibraryService
  → ReiAnixSqliteLibraryRepository.indexDiscoveredMedia
  → SQLite canônico (library.sqlite3)
  → resultado terminal/atualização da UI
```

`NativeScanController` é o coordenador de execução nativo: reserva uma execução por chave de fonte, publica estados observáveis e oferece cancelamento cooperativo. O runner diferencia falha, parcial, cancelamento e conclusão. Apenas resultados declarados completos, autorizados e com cobertura suficiente podem habilitar a reconciliação de ausências. Uma pasta vazia só é completa quando o scanner a reporta explicitamente como `EMPTY_COMPLETE`; erro de acesso não significa zero arquivos.

`NativeIndex` é staging/índice físico e protocolo de recuperação, não uma segunda biblioteca. O serviço delega as mutações ao repositório SQLite já existente; não replica SQL, regras de identidade ou scanner. A indexação nativa preserva IDs e campos que não pertencem à descoberta, incluindo progresso, histórico, metadados, capas, miniaturas e dados manuais. Um resultado transacional desconhecido não é entregue a um writer Python concorrente.

Evidências de código:
- `android/app/src/main/kotlin/com/reiflix/reiflix_local/scanner/NativeScanController.kt`
- `android/app/src/main/kotlin/com/reiflix/reiflix_local/scanner/NativeScanRunner.kt`
- `android/app/src/main/kotlin/com/reiflix/reiflix_local/storage/NativeIndex.kt`
- `android/app/src/main/kotlin/com/reiflix/reiflix_local/data/sqlite/ReiAnixLibraryService.kt`
- `android/app/src/main/kotlin/com/reiflix/reiflix_local/data/sqlite/ReiAnixSqliteLibraryRepository.kt`

## 5. Contratos do domínio e persistência

Os contratos Kotlin do Prompt 02/18 estão disponíveis na base integrada: modelos de referência de mídia, identidade e parsing de nome de arquivo são implementados por código de domínio existente. O Prompt 03/18 adicionou o bootstrap/migração da base canônica em Kotlin. Esta etapa reutiliza ambos; não introduz uma identidade global baseada apenas em nome, não atualiza metadados remotos durante a indexação e não cria uma base substituta.

O destino é o único arquivo `filesDir/data/library.sqlite3`; a versão de schema alvo continua v29. As URIs de conteúdo e os caminhos físicos são mantidos como tipos distintos; quando houver URI de provider sem caminho POSIX legítimo, a URI permanece a referência de acesso. A perda de grant ou uma consulta incompleta não é prova de remoção do arquivo.

## 6. Componentes Python/Flet ainda necessários

A migração de armazenamento/indexação Android não declara independência integral de Python:
- `main.py`, `core/android_bridge.py`, `core/storage_access.py`, `core/library_service.py` e `core/scan_coordinator.py` continuam necessários para consumidores legados, fluxos originados em Flet, compatibilidade com versões antigas e projeções/eventos ainda ativos.
- `NativeMailbox` continua sendo uma fronteira de compatibilidade e diagnóstico; não é o writer canônico da indexação SAF executada pelo runner Android atual.
- Flet/Flutter ainda participam do host/empacotamento Android.
- Metadados remotos, artwork, thumbnails, backup/recovery e outros domínios seguem seus próprios limites de migração.

Nenhum desses componentes foi removido, renomeado ou contornado nesta etapa. A substituição de cada ponte deve ocorrer somente depois da migração e verificação de todos os seus consumidores reais.

## 7. Manifesto, configuração e validação

O Manifesto já declara as permissões usadas pelos fluxos atuais: `READ_EXTERNAL_STORAGE` limitado à API 32; permissões de mídia para versões mais recentes; e `MANAGE_EXTERNAL_STORAGE` para o recurso opcional de Broad Storage. A declaração não equivale a concessão runtime. O SAF permanece baseado em URI/grant, sem depender da permissão de armazenamento amplo. Não foi necessário adicionar permissão ou dependência Gradle nesta etapa.

Não foram criados testes, testes de emulador, testes instrumentados ou workflows. Nenhum teste Kotlin/JVM foi executado manualmente. Os seguintes resultados são verificações históricas reais de commits já integrados, não execuções atribuídas a esta alteração documental:
- PR #173, commit `7bc71aae0787cf3822b7011776511deaac1ea1ff`: workflow [38727992262](https://github.com/reinansantos9090-debug/ReiAnix/actions/runs/38727992262), **completed / success**.
- PR #174, commit `fe1bc23e8be55e07496f6cac246a2e06aa386e49`: workflow [38771548168](https://github.com/reinansantos9090-debug/ReiAnix/actions/runs/38771548168), **completed / success**.
- PR #182, commit `5771adbb35d8e60e3717e3907b7f52a7082034f3`: workflow [38953084545](https://github.com/reinansantos9090-debug/ReiAnix/actions/runs/38953084545), **completed / success**.
- PR #194, commit `8a3cf43075e1f87207f9c8b373f1eb7be22d6056`: workflows [39109464590](https://github.com/reinansantos9090-debug/ReiAnix/actions/runs/39109464590) (Native Gradle APK Validation), [39109464652](https://github.com/reinansantos9090-debug/ReiAnix/actions/runs/39109464652) (Python Regression) e [39109464700](https://github.com/reinansantos9090-debug/ReiAnix/actions/runs/39109464700) (Android APK Build), todos **completed / success**.
- PR #200, commit `51a74fb2997b807e95111260ce98a3beb78af811`: workflow [39206805645](https://github.com/reinansantos9090-debug/ReiAnix/actions/runs/39206805645), **completed / success**.

Os workflows acima comprovam os respectivos commits históricos, não a execução final da PR deste documento. A validação física com diferentes provedores SAF, volumes removíveis, revogações e variantes de acesso MediaStore depende do dispositivo/estado real Android; sucesso de CI ou compilação não é uma prova de teste físico. Nenhum teste físico foi alegado.

## 8. Arquivos desta PR e limites

Arquivos de código modificados:
- `android/app/src/main/kotlin/com/reiflix/reiflix_local/scanner/SafScanner.kt`: classifica rejeições de documentos/subpastas, erros de consulta e erros de cobertura como varredura `PARTIAL` ou `UNAVAILABLE`. Somente uma enumeração sem falhas/rejeições pode resultar em `COMPLETED` ou `EMPTY_COMPLETE`.
- `android/app/src/main/kotlin/com/reiflix/reiflix_local/scanner/MediaStoreScanner.kt`: falha se a consulta de estabilidade do MediaStore retornar cursor nulo; a captura de erro existente registra a falha e impede marcar o volume como completo/vazio.
- `docs/KOTLIN_STORAGE_INDEXING_MIGRATION.md`: registra a auditoria corretiva, os defeitos confirmados e as limitações de validação.

Não foram criados ou removidos arquivos/pastas de código, bancos, tabelas, scanners, coordenadores, dependências Gradle ou permissões do Manifest. Não foram alterados testes existentes, adicionados testes novos ou alterados workflows. O SQLite canônico permanece `filesDir/data/library.sqlite3`, schema alvo v29. As mudanças funcionais são localizadas aos dois scanners existentes e preservam os contratos de indexação já utilizados pelo runner.

## 9. Pendências fora deste escopo

1. Migrar os consumidores remanescentes de `NativeMailbox` e os pedidos iniciados exclusivamente por Flet para seus contratos nativos correspondentes, sem quebrar compatibilidade enquanto ainda existirem consumidores legados.
2. Concluir, em etapas próprias, os domínios de metadados, artwork/thumbnails, backup/recovery e demais serviços que ainda usam Python.
3. Verificar comportamento de runtime em dispositivos/provedores reais quando houver execução física autorizada. Não fazer essa validação por meio de testes instrumentados/emulador adicionados a esta etapa.

## Veredito técnico

Dois defeitos funcionais foram reproduzidos por leitura estática do fluxo e corrigidos no código existente. A classificação SAF agora considera falhas/rejeições além da contagem de consultas; a sonda de estabilidade do MediaStore não interpreta cursor nulo como lista vazia. Isso reduz o risco de estado terminal incorreto, preservando a regra de que uma falha parcial nunca autoriza reconciliação destrutiva.

A compilação Release, o build do APK, os checks de empacotamento e os testes automáticos permitidos só serão considerados aprovados para o SHA final depois que o workflow da PR alcançar estado terminal verificável. Nenhuma validação física em dispositivo, teste Kotlin/JVM manual ou teste de emulador é alegado.