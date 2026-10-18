# ReiAnix — matriz de dependências e riscos da migração Kotlin

**Baseline:** `80fd95acd0d569d3013ac2d71fcf4cd166ae7844`  
**Fonte de verdade:** SQLite/LibraryStore existente, `library.sqlite3`, schema v29. Sem Room/DB paralela ou fonte de gravação concorrente.

## 1. Diagnóstico central

A main auditada continua híbrida: `main.py` é entrypoint Flet; `MainActivity` deriva de `FlutterFragmentActivity`; `ReiAnixApplication` deriva de `FlutterApplication`; o build Android usa Python/Flet/Flutter e template Flet. Compose e serviços Kotlin existem, mas não comprovam independência do runtime Python.

## 2. Matriz funcional

| Área/contrato | Python/consumidores observados | Kotlin encontrado | Risco/condição de corte | Prompt |
|---|---|---|---|---|
| Bootstrap/runtime | main.py, Flet, views, pyproject.toml e prepare_flet_template.py | Compose screens, Activity/Application e app shell existem, mas Activity/Application ainda derivam de Flutter no snapshot | O runtime e o empacotamento continuam híbridos. Só cortar após boot nativo e todos os fluxos essenciais verificados. | 02, 16, 17 |
| Persistência SQLite | core/library_store.py (schema v29), LibraryService e gravações/merge Python | ReiAnixSqliteDatabase valida contrato v29; ReiAnixSqliteLibraryRepository tem queries e algumas operações | Provar FLET_APP_STORAGE_DATA vs filesDir/data, regras de merge e identidade de arquivo; nunca criar DB paralelo. | 03, 07 |
| Bridge / IPC | core/android_bridge.py, core/compose_library_bridge.py, core/compose_settings_bridge.py, NativeMailbox legado | NativeMailbox, MainActivity, Compose host/ViewModels e NativeScanPublisher/Runner | Rastrear ações, payloads, requestId, ack, falha, recovery e cada consumidor; não remover bridge com consumidores ativos. | 04 |
| SAF e permissões | core/storage_access.py, main.py e views | Activity, serviços/helpers SAF e ReiAnixStorageScreen | Preservar URI e persistable grants, identidade de folder/volume, revogação/reseleção e retorno de Android Settings. | 05 |
| Scanner | core/scan_coordinator.py, LibraryService, bridge Python | NativeScanController, NativeScanRunner, NativeScanPublisher e integrações SAF/MediaStore | Um coordenador operacional; preservar ownership/generation, progress, cancelamento, dedup, recovery e estado terminal. | 06 |
| Regras da biblioteca | core/library_store.py, core/library_service.py, core/library_discovery.py | ReiAnixSqliteDatabase, ReiAnixSqliteLibraryRepository, modelos/ViewModels | Portar create/update/merge/dedup e observações; mesmos IDs e resultados sem mudar schema. | 07, 11 |
| Configurações | core/settings.py, bridge e SettingsView Flet | Settings repository/contract/codec/file service e Compose Settings | Preservar defaults, validação, formato, persistência, reload e valores desconhecidos. | 08 |
| AniList/metadados | core/anilist.py, associations no Store e views | AniList client/localizer/integration/matcher e SQLite associations | Preservar IDs, rate-limit, erros de rede, cache, localização pt-BR e prioridade de metadados manuais. | 09 |
| Artwork/thumbnails | core/artwork.py, cache e eventos do runtime Python | ReiAnixArtworkService, ReiAnixLocalArtwork e pipeline de thumbnails | Preservar arte local, chaves/cache, modifiedAt, invalidation, eventos e refresh visual. | 10 |
| Discovery/episódios | library_service/store/discovery e episode observations | Scanner, modelos e repositório Kotlin | Preservar identidade de mídia, season/episode/absolute number, missing state, matching e dedup. | 11 |
| Backup/recovery | core/backup.py, core/recovery.py, views/recovery_view.py | ReiAnixBackupService e serviços de diagnóstico Kotlin | Preservar preview, formato/versões, validação, restore transacional e recuperação de corrupção. | 12 |
| Diagnósticos/performance | core/diagnostics.py, core/diagnostic_service.py, core/performance.py | PerformanceDiagnostics e serviços Kotlin | Manter correlation/request IDs, timestamps e registros de falha sem bloquear UI/scan. | 12, 18 |
| Telas principais | views/home_view.py, details_view.py e main.py | Compose Home/Library/My List/Search/Details e ViewModels | A existência de telas Compose não prova que todas as rotas estejam ativas; conectar ao repositório canônico. | 13 |
| Organize/Storage/Settings/lifecycle | views Flet e estado/nav em main.py | Compose Organize/Storage/Settings, Navigation e Activity | Preservar Back, intents, picker, foco, cancelamento, estados e retorno de settings. | 14 |
| Player | main.py/AndroidBridge para consumidores legados | NativePlayerActivity e Media3/ExoPlayer | Preservar URI SAF, resume/seek, progresso/histórico, autoplay, next/previous, lifecycle/Back/PiP. | 15 |
| Build/release | pyproject, requirements, scripts e workflow Flet/Flutter | Gradle e fontes Kotlin dentro do host gerado Flet | Kotlin compila dentro de pacote híbrido hoje; retirar template/Serious Python só após cutover nativo. | 02, 16, 17 |
| Testes/tooling | 164 arquivos Python em tests/ e scripts operacionais existentes | 47 testes JVM e 14 testes instrumentados já existentes | Não adicionar/remover testes nem criar workflows/scripts/jobs/emuladores; não disparar Kotlin/JVM manual. | 17, 18 |

## 3. SQLite v29 e caminho canônico

- `core/library_store.py` define `SCHEMA_VERSION = 29`, usa `library.sqlite3` e possui inicialização, constraints e regras de merge.
- `ReiAnixSqliteDatabase.kt` declara `DATABASE_FILE_NAME = library.sqlite3`, `EXPECTED_SCHEMA_VERSION = 29` e `native-sqlite-contract-v29.json`; abre `filesDir/data/library.sqlite3` e valida contrato/schema.
- `ReiAnixSqliteLibraryRepository.kt` implementa queries reais de anime/episódios/filtros e operações parciais. Comentários ainda deixam regras de merge sob `LibraryStore` até migração com paridade.
- Python seleciona `data_dir` via `FLET_APP_STORAGE_DATA` ou fallback. A equivalência com `filesDir/data` não está provada. Não abrir segunda base para contornar discrepância.

Tabelas da implementação Python: `folders`, `anime`, `episodes`, `episode_observations`, `artwork`, `associations`, `pending_matches`, `account`, `preferences`, `genres`, `genre_aliases`, `anime_genres`, `schema_migrations`, `scan_runs`.

### Invariantes

- Mesmos IDs/chaves e foreign keys/constraints; nenhuma DB alternativa ou gravador concorrente.
- Preservar associações AniList, metadados manuais, artwork/cache, preferências/account, grants SAF, observações/gerações de scan, histórico/watched/progresso e backups.
- Rollback ou resultado transacional desconhecido nunca pode ser apresentado como sucesso.
- Manter schema v29; qualquer mudança exige justificativa/compatibilidade/plano próprio e autorização.

## 4. Fluxo atual resumido

```text
Android start → host Flutter/Flet → MainActivity : FlutterFragmentActivity
  ├─ SAF / MediaStore / intents / player nativo
  ├─ NativeMailbox + Compose host/ViewModels
  └─ Flet runtime → main.py
      ├─ LibraryStore (SQLite v29) / LibraryService / ScanCoordinator
      ├─ AndroidBridge / ComposeLibraryBridge / ComposeSettingsBridge
      └─ ArtworkEngine / AniList / backup / recovery / diagnostics / Flet views
```

É um resumo de dependências, não sequência temporal exaustiva de todos callbacks e eventos assíncronos.

## 5. Workflows existentes e regras

Workflows existentes observados: `android_instrumented.yml`, `build_apk.yml`, `cleanup_old_runs.yml`, `kotlin_jvm_tests.yml`, `reianix_auto_repair.yml`, `release_certification.yml`. Nenhum novo workflow é criado.

| Workflow | Trigger conhecido | Decisão nesta PR |
|---|---|---|
| build_apk.yml | push main, pull_request, workflow_dispatch | Deve executar na PR e é o build automático aplicável. O APK resultante ainda será do pipeline híbrido atual. |
| release_certification.yml | push main, workflow_dispatch | Não executa automaticamente em pull_request. Não declarar aprovação sem run específico; não alterar main para disparar. |
| kotlin_jvm_tests.yml | workflow_dispatch | Manual por AGENTS.md. Não disparar nem rodar durante este prompt. |
| android_instrumented.yml | workflow_dispatch | Workflow já existente; não adicionar emulador nem alterar nesta auditoria. |
| cleanup_old_runs.yml e reianix_auto_repair.yml | Conforme os triggers atualmente existentes | Preservar; não criar novos workflows, scripts ou jobs. |

O build atual instala Python/Flet/Flutter, prepara template, produz APK e compila Kotlin. Isso prova somente o pipeline híbrido, não a meta de 100% Kotlin.

## 6. Bloqueios para remoção

- Consumidor ativo de bridge/IPC ou view Flet.
- Caminho SQLite não reconciliado ou regra de merge ainda sob LibraryStore.
- Scanner sem cancelamento/recovery/dedup e estado terminal equivalente.
- Fluxo Compose parcial, sem navegação/estado/ação equivalente.
- APK final ainda empacotando Python/Flet/Flutter.
- Workflow aplicável ausente ou não concluído em sucesso no SHA final.