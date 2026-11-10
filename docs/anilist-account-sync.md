# Contas — AniList

## Cadastro necessário para publicar o login

O login exige um aplicativo OAuth próprio registrado em https://anilist.co/settings/developer.

- Nome: ReiAnix.
- Redirect URL: `reianix-anilist://oauth` (sem barra final).
- Configurar o **Client ID público** como `anilistClientId=NUMERO` em `android/gradle.properties`, ou passar `-PanilistClientId=NUMERO` ao Gradle. Também é aceito `REIANIX_ANILIST_CLIENT_ID` no ambiente do processo Gradle.
- **Não inserir Client Secret, access token nem senha no repositório ou APK.** O fluxo implicit documentado pelo AniList recebe o token após consentimento do usuário.
- Sem Client ID, o app compila, mas o botão Conectar fica indisponível. Não inventar um ID ou reutilizar o cadastro de outro aplicativo.

Os workflows existentes não foram alterados. Para os builds oficiais usarem o ID, configurar a propriedade pública em `android/gradle.properties` depois de concluir o cadastro.

## Fluxo do usuário

Configurações → Contas → AniList. Conectar abre o navegador oficial. O callback dedicado valida nonce, prazo de 15 minutos e URI; seu token nunca passa pelo player, logs ou mailbox. O token é cifrado com AES-GCM usando chave não exportável no Android Keystore; backups Android já estão desativados no manifesto.

A atualização automática fica ativada por padrão, inclusive nas contas já conectadas, sem interruptor na interface. Um checkpoint local confirmado em pelo menos 80% do episódio pode alimentar a fila da conta. A reprodução e o salvamento SQLite não aguardam rede.

Para evitar alterações no perfil baseadas em capas possivelmente incorretas, somente associações ao AniList confirmadas manualmente nos detalhes são enviadas. O ID persistido é revalidado antes do envio. Especiais e episódios fracionários são excluídos; temporadas posteriores exigem numeração absoluta. O cadastro não interfere em capas, thumbnails ou buscas públicas.

A fila guarda o maior progresso pendente por anime e recibos por conta. Não reduz progresso remoto, preserva Completed/Repeating e só conclui quando o total remoto é conhecido. Falhas de rede ficam pendentes; há três tentativas com espera e respeito ao Retry-After, com novo processamento ao receber checkpoints ou reabrir a tela da conta. Ao reconectar outra conta/desconectar, as pendências da conta anterior são descartadas. Desativar limpa pendências não enviadas. Uma requisição já aceita pelo servidor não pode ser desfeita por desconectar.

## Apresentação da conta

A tela Contas mostra somente a linha AniList, com logo e nome. Sem conexão, tocar abre o OAuth existente no navegador. Com conexão, a mesma linha mantém um ícone de confirmação; tocar abre um painel com avatar e nome do Viewer, identificação AniList e ações Trocar conta/Sair. Trocar conta abre nova autorização e preserva a conexão atual caso o usuário cancele no navegador; credenciais e pendências são substituídas somente após callback válido de outra conta. A sessão do navegador pertence ao AniList; a escolha de outro usuário ocorre na página oficial. Sair limpa a conta e as pendências neste aparelho, sem alterar o progresso SQLite.

O avatar usa `Viewer.avatar.large` e o componente de imagem de conta existente, com fallback local se estiver indisponível. Contas antigas atualizam nome e avatar ao abrir a tela; uma falha de rede preserva o perfil salvo. A URL de autorização, validação de nonce, Keystore e regras de envio permanecem as mesmas. Não há dependências novas.

## Validação necessária

Além do build oficial, validar com o cadastro real: consentimento/negação, retorno ao app, nonce inválido/repetido, token revogado, atualização automática após conectar, 79%/80%, repetição de checkpoints, progresso remoto maior, último episódio, numeração incompatível, offline/reconexão, troca de conta e proteção do progresso local em falha de rede. Os testes JVM permanecem manuais.

## Regras verificáveis de sincronização

As políticas puras estão em `AniListAccountSyncPolicy.kt` e são cobertas por `AniListAccountSyncPolicyTest.kt`, que roda no workflow Kotlin/JVM existente. Elas verificam o limiar de 80%, exclusão de especiais, numeração absoluta em temporadas posteriores, proibição de retrocesso de progresso, conclusão somente com total conhecido e preservação de `COMPLETED`/`REPEATING`. Os testes de código não substituem um teste OAuth com conta real.

Após enviar `SaveMediaListEntry`, o aplicativo exige um recibo com ID e progresso coerentes antes de confirmar a sincronização local. Falhas temporárias são reprogramadas sem bloquear a reprodução; erros permanentes não entram em um loop agressivo. Desconectar ou desativar limpa as pendências e cancela a retentativa agendada.

**Ativação em builds oficiais:** o Client ID é público e pertence ao aplicativo ReiAnix, não à conta individual de cada usuário. Ele só deve ser configurado após o próprio desenvolvedor registrar `ReiAnix` com Redirect URL `reianix-anilist://oauth` nas configurações de desenvolvedor do AniList. Nenhum Client Secret é necessário para o fluxo implicit de Android. Um APK compilado sem esse número permanece com o botão de conexão desativado.

## Correção do erro `unsupported_grant_type` no navegador

O usuário relatou o retorno JSON `unsupported_grant_type` da página de autorização depois de entrar no AniList. Isso significa que o servidor recusou o fluxo solicitado; **não** comprova senha incorreta. A causa exata no servidor ainda não foi confirmada.

A partir desta correção, `AniListOAuthRequest.authorizationUrl()` monta o endereço a partir do exemplo oficial para o fluxo *implicit*:

- `https://anilist.co/api/v2/oauth/authorize?client_id=56542&response_type=token`, com `state` aleatório adicional conforme OAuth2 para proteção contra CSRF;
- não envia `redirect_uri` na requisição, pois já está cadastrado na aplicação AniList como `reianix-anilist://oauth`;
- mantém verificação obrigatória do `state` recebido, prazo de 15 minutos e a validação do token por `Viewer` antes de salvar a conta;
- não usa `client_secret`, não solicita senha pelo ReiAnix e não altera as regras de sincronização.

**Validação pendente em dispositivo real:** instalar o APK desta branch, entrar em Configurações → Contas → tocar na linha AniList, autorizar no navegador e confirmar que volta ao aplicativo como conectado. Depois testar um episódio que tenha associação AniList confirmada manualmente. Se o navegador continuar exibindo `unsupported_grant_type`, o AniList pode estar recusando o próprio fluxo `response_type=token`; nesse caso, não mudar para `code` sem fluxo seguro de troca de código, nem enviar o Client Secret para o APK. Os testes locais de URL e a compilação não provam a aceitação pelo servidor.
