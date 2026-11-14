# Assinatura permanente dos APKs ReiAnix

## Por que isso existe

O APK instalável precisa ter a **mesma identidade de assinatura** em todos os lançamentos oficiais. Anteriormente, quando os segredos de release estavam vazios, o Gradle assinava `assembleRelease` com a chave **Android Debug** do runner temporário do GitHub. Isso impede atualizações confiáveis entre builds e não cria identidade permanente.

A correção **não desativa o Google Play Protect** nem garante ausência de avisos. Identidade de assinatura e análise do Play Protect são verificações diferentes.

## Configuração obrigatória — uma vez, antes de mesclar esta PR

1. Em um terminal **confiável** com JDK (inclui `keytool`), `openssl`, `base64` e [GitHub CLI](https://cli.github.com/), autentique-se no GitHub com permissão de administrar os segredos e as variáveis de Actions deste repositório (`gh auth login`).
2. A partir de um clone confiável do repositório, abra **a branch desta PR** (`git fetch origin fix/stable-release-signing-20261110` e `git switch fix/stable-release-signing-20261110`) e execute: `bash scripts/setup_release_signing.sh`. O script ainda não existe na `main` enquanto a PR não for mesclada.
3. Escolha e **guarde com segurança** a senha da nova chave. O script cria `$HOME/ReiAnix-release-key.jks` (ou reaproveita o arquivo se já existir), grava **quatro segredos criptografados** e uma variável pública do certificado no repositório.
4. **Faça uma cópia de segurança privada da chave .jks, em local diferente do terminal usado, e guarde a senha em um gerenciador seguro.** Se gerar a chave em ambiente temporário (incluindo Codespaces), baixe uma cópia privada antes de encerrar o ambiente. Jamais coloque o arquivo, senha ou base64 em um commit, issue, PR, print ou chat.
5. Confirme em **Settings → Secrets and variables → Actions** que aparecem os seguintes nomes (GitHub não mostra os valores dos segredos):
   - Segredos: `REIANIX_ANDROID_KEYSTORE_B64`, `REIANIX_ANDROID_KEYSTORE_PASSWORD`, `REIANIX_ANDROID_KEY_PASSWORD`, `REIANIX_ANDROID_KEY_ALIAS`.
   - Variável pública: `REIANIX_ANDROID_EXPECTED_CERT_SHA256`.
6. Depois disso, mescle esta PR. A execução de **push na main** exige a chave estável e confere se o certificado SHA-256 é o já fixado. Se estiver ausente/incorreto, o build oficial **falha antes de gerar ou publicar APK**. Verifique no resultado do GitHub Actions `signing_mode=stable` e `certificate_sha256=...`.

É possível passar um caminho explícito para a chave: `bash scripts/setup_release_signing.sh /local/privado/ReiAnix-release-key.jks`. O script não sobrescreve um keystore existente. A variável pública SHA-256 impede a substituição silenciosa por outra chave.

## APKs de PR: testes separados dos lançamentos oficiais

- Workflows do evento `pull_request` não recebem os segredos de assinatura e continuam podendo produzir APKs de **prévia**, explicitamente assinados com **Android Debug** (`signing_mode=pr-debug-preview`). Os APKs de prévia **não atualizam** uma instalação assinada pela chave oficial.
- Em `push` na `main` e `workflow_dispatch` na `main`, a assinatura **estável é obrigatória**. Um disparo manual em outra branch é recusado para não expor a chave privada a código não revisado.
- Gradle não aplica mais debug signing silenciosamente à variante release. Fora da prévia de PR, um `assembleRelease` local sem keystore produz artefato sem assinatura, não um APK oficial instalável.
- Não houve alteração de `applicationId`, configurações do player, biblioteca, SQLite, SAF, artwork ou permissões.

## Migração da instalação atual

Se o celular estiver com um APK assinado em debug, uma nova versão assinada com a chave oficial normalmente **não pode ser instalada como atualização** sobre esse APK. Antes de qualquer desinstalação, exporte um backup pela funcionalidade de backup do ReiAnix e confirme que ele pode ser restaurado. A primeira instalação com a nova chave pode exigir desinstalar a versão anterior; depois, os lançamentos oficiais futuros deverão compartilhar o mesmo certificado.

Não gire a chave por conveniência. Se a chave privada for perdida e não houver backup, você não poderá distribuir atualizações diretas compatíveis com instalações já assinadas por essa chave. **Guarde a chave com mais cuidado que o APK.**

## Como verificar

- No job oficial `Build ReiAnix Android APK`, confirme as etapas `Configure stable Android release signing` e `Verify APK signing certificate and identity`.
- Examine o artefato `reianix-release-metadata.txt`: modo `stable` e o mesmo `certificate_sha256` fixado em `REIANIX_ANDROID_EXPECTED_CERT_SHA256`.
- A opção de release contínuo (`reianix-ci-latest`) só pode ser executada manualmente na `main` e depois de passar pela verificação do certificado.
- Os testes Android Instrumented e os testes Kotlin/JVM **continuam manuais**; este ajuste não instala emuladores nem muda esses workflows.
