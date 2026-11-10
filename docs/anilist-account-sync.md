# Conta e sincronização AniList

## Cadastro necessário para publicar o login

O login exige um aplicativo OAuth próprio registrado em https://anilist.co/settings/developer.

- Nome: ReiAnix.
- Redirect URL: `reianix-anilist://oauth` (sem barra final).
- Configurar o **Client ID público** como `anilistClientId=NUMERO` em `android/gradle.properties`, ou passar `-PanilistClientId=NUMERO` ao Gradle. Também é aceito `REIANIX_ANILIST_CLIENT_ID` no ambiente do processo Gradle.
- **Não inserir Client Secret, access token nem senha no repositório ou APK.** O fluxo implicit documentado pelo AniList recebe o token após consentimento do usuário.
- Sem Client ID, o app compila, mas o botão Conectar fica indisponível. Não inventar um ID ou reutilizar o cadastro de outro aplicativo.

Os workflows existentes não foram alterados. Para os builds oficiais usarem o ID, configurar a propriedade pública em `android/gradle.properties` depois de concluir o cadastro.

## Fluxo do usuário

Configurações → Contas e sincronização → AniList. Conectar abre o navegador oficial. O callback dedicado valida nonce, prazo de 15 minutos e URI; seu token nunca passa pelo player, logs ou mailbox. O token é cifrado com AES-GCM usando chave não exportável no Android Keystore; backups Android já estão desativados no manifesto.

A sincronização fica desligada após conectar. Ao ativar, um checkpoint local confirmado em pelo menos 80% do episódio pode alimentar a fila da conta. A reprodução e o salvamento SQLite não aguardam rede.

Para evitar alterações no perfil baseadas em capas possivelmente incorretas, somente associações ao AniList confirmadas manualmente nos detalhes são enviadas. O ID persistido é revalidado antes do envio. Especiais e episódios fracionários são excluídos; temporadas posteriores exigem numeração absoluta. O cadastro não interfere em capas, thumbnails ou buscas públicas.

A fila guarda o maior progresso pendente por anime e recibos por conta. Não reduz progresso remoto, preserva Completed/Repeating e só conclui quando o total remoto é conhecido. Falhas de rede ficam pendentes; há três tentativas com espera e respeito ao Retry-After, com novo processamento ao receber checkpoints ou reabrir a tela da conta. Ao reconectar outra conta/desconectar, as pendências da conta anterior são descartadas. Desativar limpa pendências não enviadas. Uma requisição já aceita pelo servidor não pode ser desfeita por desconectar.

## Validação necessária

Além do build oficial, validar com o cadastro real: consentimento/negação, retorno ao app, nonce inválido/repetido, token revogado, ativação/desativação, 79%/80%, repetição de checkpoints, progresso remoto maior, último episódio, numeração incompatível, offline/reconexão, troca de conta e proteção do progresso local em falha de rede. Os testes JVM permanecem manuais.
