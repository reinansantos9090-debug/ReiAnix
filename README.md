# ReiAnix Local

ReiAnix é uma biblioteca para vídeos de anime que o usuário já possui no
Android. Os arquivos não são enviados para servidor algum; AniList é usado
somente para metadados/capas.

## Android 16

O projeto configura `target_sdk_version = 36` e prepara o build para o Android 16 (API 36). O teste final de permissões e descoberta deve ser feito em um dispositivo Android 16 real, porque a suíte Python não consegue provar o comportamento das Configurações do sistema, MediaStore ou provedores SAF.

## Execução Python

```bash
python -m pip install -r requirements.txt
flet run main.py
python -m unittest discover -s tests -v
```

O banco SQLite e o cache de capas ficam no diretório privado definido por
`FLET_APP_STORAGE_DATA` (ou `.reiflix-data/` no desenvolvimento).

## Android e APK

O módulo Android real fica em `android/app`. A configuração Gradle na raiz aponta para esse mesmo módulo (não cria um segundo aplicativo) e permite compilar as fontes Kotlin/Compose diretamente com JDK 17 e Android SDK 36:

```bash
./gradlew --version
./gradlew :app:assembleDebug
```

O APK de validação nativa é gerado em `android/app/build/outputs/apk/debug/app-debug.apk`. O workflow `ReiAnix Native Gradle APK Validation` realiza essa compilação sem configurar Python, Flet ou Flutter; confere o manifesto, classes nativas, recurso do contrato SQLite v29, ZIP, SHA-256 e metadados de distribuição. Consulte [docs/KOTLIN_NATIVE_GRADLE_BUILD.md](docs/KOTLIN_NATIVE_GRADLE_BUILD.md).

**Estado atual (Prompt 30.2):** tanto o workflow oficial `Build ReiAnix Android APK` quanto o workflow `ReiAnix Native Gradle APK Validation` compilam APKs diretamente pelo Gradle e não empacotam Python/Flet/Flutter. O primeiro gera o variant `release` de produção; o segundo gera um variant `debug` de validação, não um substituto para atualizar o release instalado. O código-fonte ainda mantém módulos Python/Flet para compatibilidade, e algumas funções de domínio/bridges permanecem dependentes desses consumidores. Como esses runtimes não estão dentro dos APKs atuais, funcionalidades com dependência legada ainda não demonstrada como substituída podem ficar indisponíveis. Por isso a independência funcional continua **não certificada** (`runtimeIndependenceCertified: false`). Consulte [o diagnóstico dos APKs e os limites de certificação](docs/KOTLIN_NATIVE_GRADLE_BUILD.md) e a [auditoria do Prompt 30.2](docs/PROMPT_30_2_APK_DIAGNOSTICS.md).

O APK convencional pode ser validado pela presença no DEX de `MainActivity`, `NativeMailbox`, `SafScanner`, `MediaStoreScanner`, `BroadStorageScanner`, `NativePlayerActivity` e `GoogleIdentity`. O identificador continua `com.reiflix.reiflix_local`; as permissões SAF/MediaStore e o player Media3 são mantidos. Nenhuma chave privada é incluída no repositório.

## Licença e autoria

O **ReiAnix** é distribuído sob a **Apache License 2.0 (Apache-2.0)**.

Copyright © 2026 Reinan Santos.

A licença completa está disponível no arquivo [LICENSE](LICENSE). O código próprio do ReiAnix pode ser usado, estudado, modificado e redistribuído sob os termos da Apache-2.0, observando as condições de preservação de avisos de copyright, licença e demais atribuições aplicáveis.

O nome **ReiAnix** e quaisquer marcas, logos, artes ou conteúdos de terceiros não são automaticamente licenciados pela Apache-2.0 apenas por estarem no repositório. As respectivas permissões e licenças aplicáveis devem ser respeitadas. Dependências e componentes de terceiros continuam sujeitos às suas próprias licenças.

## Google Cloud

Copie `.env.example` para o ambiente e forneça somente IDs públicos:

```bash
REIFLIX_GOOGLE_WEB_CLIENT_ID='...apps.googleusercontent.com'
REIFLIX_GOOGLE_CLIENT_ID='...apps.googleusercontent.com' # fallback OAuth desktop
REIFLIX_GOOGLE_REDIRECT_URL='https://redirect-autorizado.example/callback'
```

No Google Cloud Console configure a tela de consentimento, o **Web client ID**
passado a `REIFLIX_GOOGLE_WEB_CLIENT_ID`, e um Android client para o package
`com.reiflix.reiflix_local` com SHA-1/SHA-256 do certificado de assinatura. Não
inclua client secret no APK.

### Login Google no Android

O botão usa o Credential Manager somente quando
`REIFLIX_GOOGLE_WEB_CLIENT_ID` (ou `GOOGLE_WEB_CLIENT_ID` em
`app_config.py`) contém um Web client ID OAuth 2.0 público. Sem esse
identificador, o APK mostra que a configuração é necessária e não simula um
login ou salva uma conta falsa.

No mesmo projeto do Google Cloud Console:

1. Crie um cliente OAuth 2.0 do tipo **Web application** e configure somente
   o seu client ID público como `REIFLIX_GOOGLE_WEB_CLIENT_ID`.
2. Crie um cliente OAuth 2.0 do tipo **Android** para o package
   `com.reiflix.reiflix_local`; registre o SHA-1 e o SHA-256 do certificado que
   assina o APK distribuído (release/Play Console conforme aplicável).
3. Não coloque client secret, token, senha ou arquivo de credenciais no
   repositório, nas variáveis empacotadas ou no APK.

O aplicativo armazena somente ID, nome, e-mail e foto retornados pela conta.
Ele não pede Gmail, Drive, contatos ou permissões de arquivos.

## AniList, scanner e organização

O scanner Python atende caminhos reais em desktop. No Android, a camada SAF
entrega documentos autorizados e `LibraryService.ingest_documents()` os agrupa
por anime/temporada/episódio, persistindo a URI em vez de caminho POSIX.
AniList GraphQL pesquisa o título e mantém título, gêneros, sinopse e cache
local de capas. Sem internet, a biblioteca/URI e capas cacheadas permanecem
utilizáveis. A Home filtra os títulos por gêneros devolvidos pelo AniList.

Além dos favoritos, cada anime pode receber etiquetas pessoais na tela de
Detalhes (por exemplo, `Prioridade` ou `Assistir com amigos`). Essas etiquetas
são privadas, ficam no SQLite local, aparecem em buscas locais e não são
enviadas ao AniList ou ao Google.

## Biblioteca pessoal e player local

A biblioteca também mantém, exclusivamente no SQLite local, **pins** e notas
pessoais (até 2000 caracteres). A tela de detalhes permite editar ambos; as
projeções locais podem filtrar por favorito, pin, progresso, nota, metadata,
capa e etiqueta. Configurações mostra estatísticas agregadas e o relatório
real do último scan, sem abrir arquivos nem chamar serviços de metadata.

O player Android usa Media3 1.11.1 para conteúdo autorizado localmente.
Ele implementa controles nativos e inclui velocidade entre 0,5x–2x, fit/fill/zoom,
reinício, autoplay do próximo episódio e timer de sono de sessão. Retomada,
progresso, transição de episódio e ações como marcar visto precisam ser validadas
no APK nativo real; não trate publicação em `NativeMailbox` como prova de
persistência, pois a mailbox não publica quando o pacote não inclui `assets/app.zip`.
PiP, codecs e reprodução exigem validação em dispositivo; não há streaming,
download ou legenda online.

## Permissões

O Android usa três mecanismos complementares para a biblioteca local:

- READ_MEDIA_VIDEO (e o estado de acesso visual selecionado no Android 14+)
  para consultar vídeos pelo MediaStore;
- MANAGE_EXTERNAL_STORAGE, concedido pelo usuário nas Configurações do
  Android, para a varredura ampla de armazenamento local;
- SAF (ACTION_OPEN_DOCUMENT_TREE) para uma pasta específica escolhida pelo
  usuário.

A tela de Configurações do ReiAnix explica e solicita esses acessos em contexto.
O app não solicita permissões de notificações, contatos, SMS, telefone,
localização, câmera, microfone, Gmail ou Drive.

Na primeira abertura Android, o app consulta o estado real das permissões antes
de oferecer o onboarding. O MediaStore é uma camada complementar de descoberta
e pode informar acesso parcial no Android 14+. O acesso amplo só é considerado
concedido quando `Environment.isExternalStorageManager()` confirma o retorno
das Configurações; ele alcança o armazenamento compartilhado que o Android
permite ao aplicativo, não áreas protegidas do sistema. SAF continua sendo a
opção para uma árvore escolhida explicitamente pelo usuário. Cancelar qualquer
etapa mantém o app aberto e permite tentar de novo em Configurações.

## Build

O comando nativo, as diferenças entre variants e os limites de certificação estão documentados em [Kotlin native Gradle build](docs/KOTLIN_NATIVE_GRADLE_BUILD.md). O APK oficial atual já é produzido por Gradle nativo, mas a migração funcional não está certificada enquanto houver operações legadas sem substituição comprovada.
