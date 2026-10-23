# ReiAnix Local

ReiAnix é uma biblioteca Android nativa para organizar e reproduzir vídeos de anime armazenados localmente. A aplicação usa Kotlin, Jetpack Compose, SQLite e Media3. Os arquivos de vídeo permanecem no dispositivo; o AniList é utilizado para metadados e capas.

## Ambiente de desenvolvimento

- JDK 17
- Android SDK Platform 36 e Build Tools 36.0.0
- Gradle 8.11.1 instalado no ambiente

O projeto Android independente fica em `android/`. O repositório não inclui Gradle Wrapper; os comandos abaixo usam a instalação local de Gradle.

## Compilar

Para gerar o APK release:

```bash
gradle -p android --no-daemon :app:assembleRelease
```

Para compilar o Kotlin de release e executar os testes unitários locais:

```bash
gradle -p android --no-daemon :app:compileReleaseKotlin :app:testDebugUnitTest
```

O workflow oficial **Build ReiAnix Android APK** compila o APK diretamente por Gradle, valida o Manifest, as classes nativas no DEX, o contrato SQLite v29 e a ausência de payloads de runtimes não nativos. O asset comprimido `android/app/src/main/assets/native-sqlite-contract-v29.json.gz` é a origem versionada do contrato SQLite utilizado pelo aplicativo.

## Aplicativo Android

O launcher é `MainActivity : ComponentActivity()`; `ReiAnixApplication : Application()` mantém os serviços e o bootstrap nativos. As telas são Compose, a persistência usa o SQLite canônico e a reprodução local usa Media3.

O app descobre vídeos por MediaStore, pela pasta escolhida via Storage Access Framework (SAF) ou pelo acesso amplo ao armazenamento compartilhado quando autorizado pelo usuário. O acesso a uma pasta pode ser concedido ou revogado nas Configurações do Android; o app não solicita acesso a contatos, SMS, localização, câmera, microfone, Gmail ou Drive.

O player inclui retomada de progresso, velocidade de 0,5x a 2x, ajuste de imagem, autoplay do próximo episódio e timer de sono. A compatibilidade com arquivos, codecs e permissões reais deve ser validada no dispositivo Android de destino.

## Login Google opcional

O login Android usa Credential Manager. O workflow de release aceita o Web Client ID público pela variável de repositório `REIFLIX_GOOGLE_WEB_CLIENT_ID` (ou secret com o mesmo nome). Não armazene client secrets ou tokens no repositório ou no APK. Sem o identificador, o aplicativo permanece compilável e informa que o login precisa ser configurado.

## Privacidade e dados

O catálogo, as preferências, o progresso e o cache local são mantidos no armazenamento privado do aplicativo. Os vídeos locais não são enviados para servidores; as solicitações de metadados/capas usam os serviços correspondentes.

## Licença

O ReiAnix é distribuído sob a Apache License 2.0. Consulte [LICENSE](LICENSE) para os termos completos.

Copyright © 2026 Reinan Santos.
