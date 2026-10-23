# Auditoria atual da arquitetura nativa

Este documento descreve a árvore de código atual, não os snapshots históricos de migração listados em outros relatórios.

## Estado atual

O aplicativo Android usa Kotlin, Jetpack Compose, Gradle, SQLite v29 e Media3. O entry point é `MainActivity : ComponentActivity()`, com o bootstrap process-scoped em `ReiAnixApplication : Application()`.

Os arquivos de configuração Python/Flet foram removidos: `app_config.py`, `requirements.txt` e `pyproject.toml`. O entry point `main.py` e os módulos Python de domínio já não estão na árvore ativa da branch. O workflow de reparo que executava Python e dependia de scripts inexistentes também foi removido.

## Build e validação

- `.github/workflows/build_apk.yml` compila o APK release diretamente por Gradle e verifica as classes Kotlin, o Manifest, o contrato SQLite e a ausência de payloads Python/Flet/Flutter.
- `.github/workflows/release_certification.yml` faz a certificação nativa e agora também rejeita arquivos `.py`/`.pyc` e configurações de pacotes Python.
- `.github/workflows/android_instrumented.yml` permanece manual e usa o projeto standalone em `android/`; as ferramentas de fixture são ffmpeg, sqlite3 e jq, sem instalar Python/Flet/Flutter.
- O projeto não contém Gradle Wrapper. O build local usa JDK 17, Android SDK 36 e Gradle 8.11.1.

Comandos locais:

```bash
gradle -p android --no-daemon :app:assembleRelease
gradle -p android --no-daemon :app:compileReleaseKotlin :app:testDebugUnitTest
```

## Documentos históricos

Relatórios nomeados por prompts e fases anteriores (incluindo inventários de migração Python) registram o estado encontrado naqueles commits. Podem mencionar arquivos, pontes e workflows que já não existem. Use este documento, o README e os workflows atuais como referência da arquitetura ativa; referências históricas em Markdown não são dependências de build/runtime.

## Limites da evidência

A remoção dos arquivos/configurações Python é uma alteração de fonte e configuração. Isso não equivale, por si só, a uma execução bem-sucedida de build, APK, testes instrumentados ou validação física. Informe cada resultado apenas quando o workflow correspondente do SHA final terminar e fornecer evidência.
