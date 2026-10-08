"""Configuração pública do aplicativo para o build Android.

O client ID OAuth não é segredo e precisa ser emitido no Google Cloud para o
nome do pacote/assinatura do APK que será distribuído. Não coloque client
secret neste arquivo.
"""

GOOGLE_CLIENT_ID = ""
GOOGLE_REDIRECT_URL = ""

# OAuth 2.0 Web client ID used by Credential Manager. Public, never a secret.
GOOGLE_WEB_CLIENT_ID = ""
