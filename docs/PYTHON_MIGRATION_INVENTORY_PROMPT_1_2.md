# ReiAnix — Inventário de Python remanescente (Prompt 1.2/9)

**Snapshot de `main` no commit de código validado `566521ed45f0534bc318cb2fa38adc8d8c6ad12d`.**  
**Total exato: 218 arquivos `.py`.**

| Área | Quantidade | Situação funcional |
|---|---:|---|
| Raiz | 2 | `main.py` e configuração legada da aplicação continuam presentes. |
| `core/` | 31 | Serviços de domínio, bridge, biblioteca/SQLite, navegação, recuperação, identidade, pesquisa e configurações Python continuam no código e em contratos legados. |
| `views/` | 6 | Views Flet permanecem para jornadas desktop/legadas; telas Compose equivalentes existem em parte do caminho Android, mas paridade total não foi provada. |
| `scripts/` | 15 | Ferramentas de geração, auditoria, preparação de template, certificação, diagnóstico e verificadores ainda são Python. |
| `tests/` | 164 | Testes Python de regressão, contratos e harnesses continuam no CI; não foram removidos para reduzir a suíte. |
| **Total** | **218** | **Meta global de Python zero ainda não atingida.** |

## O que a validação do APK prova — e o que não prova

O APK criado pelo run [39407009008](https://github.com/reinansantos9090-debug/ReiAnix/actions/runs/39407009008) passou pela inspeção do ZIP e DEX: `NATIVE_RUNTIME=PASS`, `PYTHON_FLET_FLUTTER_ABSENT=PASS`, `ANDROID_ENTRYPOINT=PASS` e `COMPOSE_APK_DEX=PASS`. A inspeção local do artefato confirmou quatro arquivos DEX, as classes `ReiAnixApplication`, `MainActivity`, `ReiAnixComposeLibraryHost` e `NativePlayerActivity`, e nenhum membro de runtime Python/Flet/Flutter proibido.

Isso comprova ausência desses payloads no **APK específico**, não ausência de arquivos Python ou dependências Python do build. O workflow ainda instala Python para a regressão de fonte, auditoria e geração de `assets/native-sqlite-contract-v29.json` a partir de `core/library_store.py`. A fonte `main.py` e os serviços Python continuam no repositório. Não foram excluídos porque a substituição integral das suas funções ainda não foi comprovada.

## Arquivos na raiz — 2 arquivos

```text
app_config.py
main.py
```

## core/ — 31 arquivos

```text
core/android_bridge.py
core/anilist.py
core/artwork.py
core/artwork_palette.py
core/backup.py
core/build_identity.py
core/collector_journey.py
core/compose_library_bridge.py
core/compose_settings_bridge.py
core/consumption.py
core/diagnostic_service.py
core/diagnostics.py
core/dialogs.py
core/genre_classifier.py
core/genre_registry.py
core/google_account.py
core/library_discovery.py
core/library_parser.py
core/library_service.py
core/library_store.py
core/media_identity.py
core/navigation.py
core/organizer_ai.py
core/performance.py
core/recovery.py
core/scan_coordinator.py
core/search_engine.py
core/settings.py
core/settings_focus.py
core/storage_access.py
core/ui.py
```

## views/ — 6 arquivos

```text
views/collector_view.py
views/details_view.py
views/home_view.py
views/organize_view.py
views/recovery_view.py
views/settings_view.py
```

## scripts/ — 15 arquivos

```text
scripts/analyze_android_runtime_log.py
scripts/audit_release.py
scripts/create_library_fixture.py
scripts/create_player_fixture.py
scripts/generate_build_identity.py
scripts/generate_native_sqlite_test_fixture.py
scripts/prepare_flet_template.py
scripts/reianix_auto_repair.py
scripts/release_certification.py
scripts/validate_android_device.py
scripts/verify_android_host.py
scripts/verify_apk_manifest.py
scripts/verify_compose_packaging.py
scripts/verify_native_apk.py
scripts/verify_python_bundle.py
```

## tests/ — 164 arquivos

```text
tests/__init__.py
tests/test_android_runtime_log_analyzer.py
tests/test_anilist.py
tests/test_anilist_matching.py
tests/test_anilist_matching_2.py
tests/test_anilist_service.py
tests/test_architecture.py
tests/test_artwork_engine.py
tests/test_artwork_palette.py
tests/test_artwork_pipeline.py
tests/test_assist_open.py
tests/test_back_lifecycle.py
tests/test_backup_restore.py
tests/test_bridge_cleanup.py
tests/test_broad_storage.py
tests/test_build_identity.py
tests/test_build_tools.py
tests/test_certification.py
tests/test_certification_runner.py
tests/test_collector_journey.py
tests/test_compact_visual_density.py
tests/test_compose_cutover.py
tests/test_compose_host.py
tests/test_compose_insets.py
tests/test_compose_library_bridge.py
tests/test_compose_library_integration.py
tests/test_compose_performance.py
tests/test_compose_player_settings.py
tests/test_compose_settings_bridge.py
tests/test_compose_settings_preferences.py
tests/test_compose_states.py
tests/test_compose_storage.py
tests/test_compose_theme_consistency.py
tests/test_consistency.py
tests/test_continue_resume.py
tests/test_continue_watching_behavior.py
tests/test_continue_watching_resume.py
tests/test_definitive_correction.py
tests/test_details_actions_progress.py
tests/test_details_compose_contract.py
tests/test_details_episode_visual_rebuild.py
tests/test_details_player_launch.py
tests/test_details_regression.py
tests/test_details_semantics.py
tests/test_device_compatibility_contract.py
tests/test_diagnostics.py
tests/test_discovery_engine.py
tests/test_e2e_diagnostics.py
tests/test_episode_persistence.py
tests/test_final_audit.py
tests/test_final_certification.py
tests/test_flet_launch_compat.py
tests/test_flet_page_harness.py
tests/test_flet_ui_removal.py
tests/test_focus_thumbnail_regressions.py
tests/test_forensic.py
tests/test_genre_registry.py
tests/test_global_visual_consolidation.py
tests/test_google.py
tests/test_google_account.py
tests/test_hierarchical_library.py
tests/test_home_library.py
tests/test_home_library_organize.py
tests/test_home_refresh_behavior.py
tests/test_home_refresh_contract.py
tests/test_home_rendering.py
tests/test_home_resilience.py
tests/test_home_sections.py
tests/test_home_thumbnail_preference.py
tests/test_identification_engine.py
tests/test_indexer_professional.py
tests/test_instrumentation.py
tests/test_integration.py
tests/test_kotlin_artwork_migration_contract.py
tests/test_kotlin_backup_recovery_contract.py
tests/test_kotlin_personal_list_migration.py
tests/test_kotlin_sqlite_data_layer_contract.py
tests/test_library.py
tests/test_library_catalog.py
tests/test_library_experience.py
tests/test_library_indexing_migration_contract.py
tests/test_library_intelligence.py
tests/test_library_regression_validation.py
tests/test_library_sources.py
tests/test_library_visual_consolidation.py
tests/test_lifecycle_contract.py
tests/test_mailbox_events_tasks.py
tests/test_main_startup_contract.py
tests/test_media3_errors.py
tests/test_media_controls.py
tests/test_media_identity.py
tests/test_media_store.py
tests/test_metadata_engine.py
tests/test_native_anilist_integration_contract.py
tests/test_native_library_consumer_integration.py
tests/test_native_player_lifecycle.py
tests/test_native_search.py
tests/test_native_sqlite_persistence_contract.py
tests/test_navigation.py
tests/test_navigation_lifecycle.py
tests/test_next_transition.py
tests/test_organize.py
tests/test_organize_collections.py
tests/test_performance.py
tests/test_performance_monitor.py
tests/test_persistence.py
tests/test_phase7_details_contract.py
tests/test_playback_contract.py
tests/test_playback_hardening.py
tests/test_playback_regression.py
tests/test_player_consumption_cycle.py
tests/test_player_forensic.py
tests/test_player_gesture_ux.py
tests/test_player_reconstruction.py
tests/test_player_routing.py
tests/test_player_transitions.py
tests/test_prepare_flet_template.py
tests/test_previous_transition.py
tests/test_progress_continuity.py
tests/test_progress_resume.py
tests/test_prompt14_native_organizer_contract.py
tests/test_prompt16_kotlin_diagnostics_contract.py
tests/test_prompt17_native_storage_onboarding.py
tests/test_prompt19_app_bootstrap.py
tests/test_prompt20_collector_journey_migration.py
tests/test_prompt22_remaining_business_logic_migration.py
tests/test_prompt23_compose_interface_migration.py
tests/test_prompt24_data_contracts.py
tests/test_prompt25_runtime_dependency_audit.py
tests/test_pull_refresh.py
tests/test_reconciliation.py
tests/test_recovery_mode.py
tests/test_regression_baseline.py
tests/test_regressions.py
tests/test_reianix_auto_repair.py
tests/test_repository_hygiene.py
tests/test_rescan_and_reconciliation.py
tests/test_runtime_android_contract.py
tests/test_runtime_regressions.py
tests/test_saf_professional.py
tests/test_scalability.py
tests/test_scan_coordinator.py
tests/test_scanner_scope.py
tests/test_search_details_organize.py
tests/test_search_engine.py
tests/test_session_hardening.py
tests/test_settings_compact_ui.py
tests/test_settings_contract.py
tests/test_settings_contract_parity.py
tests/test_settings_focus.py
tests/test_settings_performance.py
tests/test_settings_store.py
tests/test_smart_library_tools.py
tests/test_stabilization.py
tests/test_stabilization_phase.py
tests/test_storage_onboarding.py
tests/test_storage_permission_flow.py
tests/test_theme_engine.py
tests/test_thumbnails.py
tests/test_transition_hardening.py
tests/test_tv_dpad.py
tests/test_ui_render_pipeline.py
tests/test_ui_states.py
tests/test_zoom.py
```
