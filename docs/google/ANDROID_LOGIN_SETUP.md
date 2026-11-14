# Configuração do Login Google no ReiAnix

O login Android usa o Credential Manager e um OAuth 2.0 Web Client ID. O Web Client ID é público; **client secrets e tokens nunca devem ser incluídos no repositório ou no APK**.

## 1. Google Cloud

No mesmo projeto do Google Cloud:

1. Configure a tela de consentimento OAuth.
2. Crie um OAuth Client ID do tipo **Web application**.
3. Use o valor terminado em `apps.googleusercontent.com` como `REIFLIX_GOOGLE_WEB_CLIENT_ID`.
4. Crie também um OAuth Client ID do tipo **Android** para `com.reiflix.reiflix_local`.
5. Cadastre no cliente Android os fingerprints SHA-1 e SHA-256 do certificado que assina o APK instalado.

Não é necessário habilitar Google Drive, Gmail, Contacts ou APIs de arquivos.

## 2. Configurar o Web Client ID

O workflow oficial aceita o valor como variável de repositório ou secret com o nome `REIFLIX_GOOGLE_WEB_CLIENT_ID`. Como o identificador é público, a **Repository Variable** costuma ser a opção mais simples. O workflow injeta o valor no recurso Android somente durante a compilação.

Se nenhum valor estiver configurado, o APK continua compilável, mas o aplicativo informa que o login precisa ser configurado em vez de simular uma autenticação.

## 3. Descobrir a assinatura do APK

Depois de compilar, o workflow executa `apksigner --print-certs` e mostra os fingerprints SHA-1/SHA-256 nos logs do GitHub Actions. Eles devem corresponder ao certificado usado pelo APK instalado. Se a assinatura mudar, atualize o cliente OAuth Android correspondente.

## 4. Dados da conta

O Android mantém um perfil local reduzido: ID Google, nome, e-mail e foto. O ID token e outros tokens de autenticação não são persistidos no SQLite. O login identifica a conta localmente; a biblioteca de vídeos permanece local e o aplicativo não usa Google Drive para procurar ou reproduzir vídeos.
