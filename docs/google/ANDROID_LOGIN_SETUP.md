# Configuração do Login Google no ReiFlix

O login Android usa o **Credential Manager** e um OAuth 2.0 **Web client ID**. O
Web client ID é público; **client secret nunca deve entrar no APK ou no Git**.

## 1. Google Cloud

No mesmo projeto do Google Cloud:

1. Configure a tela de consentimento OAuth.
2. Crie um OAuth Client ID do tipo **Web application**.
3. Use o valor terminado em `apps.googleusercontent.com` como
   `REIFLIX_GOOGLE_WEB_CLIENT_ID`.
4. Crie também um OAuth Client ID do tipo **Android** para:
   `com.reiflix.reiflix_local`.
5. No cliente Android, cadastre o SHA-1 e o SHA-256 do certificado que assina o
   APK que será instalado.

Não é necessário habilitar Google Drive, Gmail, Contacts ou qualquer API de
arquivos.

## 2. Colocar o Web Client ID no APK

O workflow aceita o valor como variável de repositório:

`REIFLIX_GOOGLE_WEB_CLIENT_ID`

ou como secret com o mesmo nome.

Como o identificador é público, **Repository Variable** é a opção mais simples.

O workflow injeta o valor somente durante o build. O arquivo
`app_config.py` continua sem o ID no Git.

Se nenhum valor for configurado, o APK continua compilável, mas o aplicativo
mostra que o login precisa ser configurado em vez de fingir que autenticou.

## 3. Descobrir a assinatura do APK

Depois de um build, o workflow executa `apksigner --print-certs` e mostra os
fingerprints SHA-1/SHA-256 nos logs do GitHub Actions. Esses valores devem ser
usados no cliente OAuth Android correspondente à assinatura daquele APK.

Isso é importante: o fingerprint precisa corresponder **à chave que realmente
assina o APK instalado**. Se no futuro a assinatura mudar, o cliente Android
do Google também precisará ser atualizado.

## 4. O que o aplicativo recebe

O Android reduz a credencial a um perfil sem token:

- ID Google;
- nome;
- e-mail;
- foto.

O ID token não é gravado no SQLite nem enviado pela bridge Python.

O login é apenas identidade local da conta. A biblioteca de vídeos continua
local e o aplicativo não usa Google Drive para procurar ou reproduzir vídeos.
