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

O projeto fixa `flet==0.86.5`. A própria distribuição instalada declara
**Flutter 3.44.8**; Java 17 e Android SDK 36 são o contrato de build. O código
em [`android/`](android/README.md) é um overlay do host Flutter gerado pelo
Flet, não um aplicativo Android independente.

```bash
python -m unittest discover -s tests -v
python -m compileall -q main.py app_config.py core views
git diff --check
flet build apk --yes
python scripts/verify_android_host.py build/apk/<arquivo>.apk
```

A última verificação é obrigatória: ela procura no DEX as classes
`MainActivity`, `NativeMailbox`, `SafScanner`, `MediaStoreScanner`,
`BroadStorageScanner`, `NativePlayerActivity` e `GoogleIdentity`. Caso elas
não estejam presentes, o APK é o cliente Flet stock e **não** deve ser
distribuído, pois a bridge `reiflix://native`, SAF e Media3 não estarão
integrados. O workflow GitHub Actions reproduz essas etapas, configura Python,
Java 17, Flutter 3.44.8 e Android SDK 36, e publica apenas o APK verificado.

Não há APK comitado no repositório e não são incluídos secrets de Google. No
Android, o usuário pode conceder leitura de vídeos pelo MediaStore e, para a
biblioteca que procura vídeos em várias pastas, também pode conceder o acesso
especial de armazenamento amplo. O SAF continua disponível para autorizar uma
pasta específica. O app não converte URIs content:// em caminhos artificiais.

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

O player Android usa Media3 1.11.1 para conteúdo autorizado localmente. Ele
preserva retomada/progresso via `NativeMailbox` e inclui velocidade entre
0,5x–2x, fit/fill/zoom, reinício, marcar visto/não visto, autoplay do próximo
episódio e timer de sono de sessão. PiP, codecs e reprodução devem ser
validados em APK/dispositivo; não há streaming, download ou legenda online.

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

```bash
flet build apk --yes
```

O build requer Java 17, Flutter e dependências Android acessíveis. Depois de
configurar a mesclagem do overlay, valide em aparelho a seleção SAF, a conta
Google e arquivos/codec reais, especialmente MKV.
