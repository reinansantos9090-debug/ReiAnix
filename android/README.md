# ReiAnix Native Android

O diretório `android/` contém a aplicação Android standalone. O launcher é `com.reiflix.reiflix_local.MainActivity`, uma `ComponentActivity` que hospeda a navegação Jetpack Compose. `ReiAnixApplication` mantém o grafo de dependências e o bootstrap durante a vida do processo.

A aplicação usa Kotlin, Jetpack Compose, SQLite, SAF/MediaStore e Media3. O build oficial compila diretamente com Gradle e não usa Python, Flet ou Flutter.

## Toolchain

| Componente | Versão |
| --- | --- |
| JDK | 17 |
| Android Gradle Plugin | 8.9.1 |
| Kotlin / Compose Compiler | 2.2.10 |
| Gradle | 8.11.1 |
| Android compileSdk / targetSdk | 36 / 36 |
| minSdk | 24 |
| Media3 | 1.11.1 |

Não há Gradle Wrapper versionado; instale a versão indicada e execute:

```bash
gradle -p android --no-daemon :app:assembleRelease
gradle -p android --no-daemon :app:compileReleaseKotlin :app:testDebugUnitTest
```

O contrato SQLite v29 é mantido em `app/src/main/assets/native-sqlite-contract-v29.json.gz`. O workflow de build valida o contrato, compila o release, inspeciona as classes nativas no DEX e rejeita payloads de Python/Flet/Flutter no APK. O APK é construído diretamente pelo Gradle.

## Funcionalidades nativas

- Bibliotecas locais por MediaStore, SAF e acesso amplo opcional autorizado pelo usuário.
- Persistência canônica SQLite, validação de integridade e recuperação.
- Interface Compose para Home, biblioteca, busca, detalhes, organização, lista pessoal e configurações.
- AniList para metadados/capas, mantendo o catálogo e o cache locais.
- Media3 para reprodução local, progresso, retomada, autoplay, controles de imagem e timer de sono.
- Credential Manager para login Google opcional, sem persistir tokens.

O workflow manual de testes Android usa o projeto standalone em `android/`. Os testes unitários Kotlin/JVM continuam separados dos testes instrumentados que dependem de um dispositivo ou emulador. A existência de um build ou de testes estáticos não substitui a validação dos fluxos físicos de SAF, Settings, armazenamento removível e codecs no dispositivo-alvo.
