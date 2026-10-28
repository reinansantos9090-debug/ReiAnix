# Prompt 3 — validação manual de provedores, artwork e APK

## Estado real desta rodada

- O `AGENTS.md` reserva os testes Kotlin/JVM para a etapa manual definida pelo usuário. Esta rodada foi explicitamente designada para essa validação, mas a execução não pôde ser iniciada no ambiente do assistente: não existe Gradle instalado e a tentativa de obter o checkout com `git clone` falhou com `Could not resolve host: github.com`. Portanto, **nenhum teste Kotlin/JVM foi executado** e não há resultado PASS/FAIL da suíte.
- O workflow Android do PR executou `:app:assembleRelease`, `:app:compileReleaseKotlin`, verificações do contrato SQLite v29, inspeção do APK, SHA-256 e assinatura. Esse workflow não executa testes Kotlin/JVM.
- O APK é assinado com a chave de depuração. A validação em aparelho físico não foi realizada nesta rodada.
- Não havia token TMDB de teste autorizado disponível; a consulta real autenticada continua pendente.

## 1. Testes Kotlin/JVM relevantes

Pré-requisitos: checkout da branch `feat/prompt-3-metadata-providers-20261028`, JDK 17, Gradle 8.11.1, Android SDK Platform 36 e Build Tools 36.0.0, além de acesso aos repositórios de dependências Maven. Não é necessário emulador, e não se deve adicionar infraestrutura de emulador ao repositório.

No diretório raiz do projeto, execute apenas os testes relacionados ao Prompt 3:

```bash
gradle -p android --no-daemon --console=plain :app:testDebugUnitTest \
  --tests 'com.reiflix.reiflix_local.data.metadata.ReiAnixMetadataProvidersTest' \
  --tests 'com.reiflix.reiflix_local.data.artwork.ReiAnixTmdbArtworkCachePolicyTest' \
  --tests 'com.reiflix.reiflix_local.data.artwork.ReiAnixArtworkCandidatePolicyTest' \
  --tests 'com.reiflix.reiflix_local.data.artwork.ReiAnixArtworkPublicationSignalTest' \
  --tests 'com.reiflix.reiflix_local.data.anilist.ReiAnixAniListCoverFileValidationTest' \
  --tests 'com.reiflix.reiflix_local.data.anilist.ReiAnixArtworkDiscoveryPolicyTest' \
  --tests 'com.reiflix.reiflix_local.ui.artwork.ReiAnixLocalArtworkTest' \
  --tests 'com.reiflix.reiflix_local.data.library.ReiAnixNativeLibraryCatalogProjectorArtworkTest' \
  --tests 'com.reiflix.reiflix_local.data.anilist.ReiAnixAniListClientTest'
```

Registre o comando executado, o SHA do checkout, os testes totais/passed/failed/skipped e o caminho dos relatórios em `android/app/build/reports/tests/testDebugUnitTest/`. Em caso de falha, registre o stack trace e corrija somente o defeito reproduzido no escopo do Prompt 3; não remova testes nem altere casos não relacionados. A ausência de uma execução é BLOCKED, nunca PASS.

## 2. Preparar o APK de avaliação

1. Abra o [workflow Android mais recente da branch do PR #226](https://github.com/reinansantos9090-debug/ReiAnix/actions). Selecione a execução concluída para o SHA desejado e baixe o artefato `ReiAnix-apk-instalacao-direta-<run-number>`.
2. Extraia o ZIP do artefato do Actions. O artefato inclui `ReiAnix.apk` e `ReiAnix.apk.sha256`.
3. Na mesma pasta do APK extraído, verifique o checksum:

   ```bash
   sha256sum -c ReiAnix.apk.sha256
   ```

4. O APK desta rodada foi gerado para `arm64-v8a` e requer Android API 24 ou posterior. Confira no dispositivo a arquitetura e a versão do Android.
5. A assinatura observada é `C=US, O=Android, CN=Android Debug`, não a chave oficial de release. Se o ReiAnix já estiver instalado com outra assinatura, o Android pode recusar a atualização. **Não desinstale uma instalação que contém biblioteca local importante sem verificar e testar primeiro um backup restaurável**: a desinstalação pode apagar os dados privados do aplicativo. Prefira um aparelho de teste ou uma instalação de teste sem dados importantes.
6. Transfira `ReiAnix.apk` para o aparelho, abra-o pelo gerenciador de arquivos e autorize a instalação de fontes desconhecidas somente para o aplicativo que iniciou a instalação, se o Android solicitar.
7. Abra o ReiAnix e registre a versão exibida e qualquer mensagem de erro. APK compilado, assinatura verificada e APK executado em aparelho são três evidências diferentes.

Os artefatos do Actions têm retenção limitada (1 dia no workflow atual); use a execução mais recente e não dependa de um link antigo.

## 3. Roteiro de validação em aparelho físico

**Antes de começar:** use um perfil/aparelho de teste sempre que possível. Registre capturas de tela da biblioteca, a quantidade aproximada de títulos/episódios e duas capas válidas antes da atualização. Não use “Limpar cache de artwork” neste teste de preservação. Não apague os dados do aplicativo para contornar um erro de assinatura.

Marque um caso como PASS somente depois de executá-lo no aparelho e guardar a evidência (captura de tela, versão/arquitetura do aparelho, horário e, quando relevante, logs sem segredos).

| ID | Verificação | Procedimento e critério de aceitação | Estado inicial |
|---|---|---|---|
| D1 | Instalação/abertura | Instale o APK de avaliação e abra o ReiAnix. O processo deve chegar à tela inicial sem encerrar inesperadamente nem apresentar erro de integridade do banco. | PENDENTE — não executado em aparelho |
| D2 | Biblioteca local | Abra a biblioteca já existente e confira alguns títulos e episódios locais. Os itens devem continuar acessíveis; não devem desaparecer por causa da adição dos provedores. | PENDENTE — não executado em aparelho |
| D3 | Capas existentes | Escolha duas capas previamente válidas, registre a aparência e os itens correspondentes. Depois de atualizar os detalhes de outro anime, retorne e confirme que ambas continuam presentes. | PENDENTE — não executado em aparelho |
| D4 | Descoberta AniList | Em Configurações → Metadata, deixe “Usar AniList” ligado. Abra os detalhes de um anime local sem pôster utilizável e use o menu ⋮ → “Atualizar detalhes”. Aguarde a atualização. Com o APK sem token TMDB desta rodada, o AniList deve continuar funcionando; se não houver correspondência, registre isso como ausência de resultado, não como falha automática do pipeline. | PENDENTE — não executado em aparelho |
| D5 | Fallback TMDB real | Requer um APK de teste separado compilado com token autorizado. Use um título que não encontre pôster válido pelo AniList e que tenha série correspondente no TMDB. Atualize os detalhes e confirme a capa baixada, o logotipo/atribuição em Sobre e que a biblioteca local permaneceu intacta. Sem credencial, este caso fica BLOCKED. | BLOCKED — sem token autorizado; nenhuma consulta real |
| D6 | TMDB desativado, AniList preservado | Em Configurações → Metadata, desligue “Usar TMDB para capas” e mantenha “Usar AniList” ligado. Atualize os detalhes de um título de teste para o qual AniList tem um resultado. Confirme que o fluxo AniList continua funcionando. Para provar ausência de chamadas a `api.themoviedb.org`, capture a rede por um mecanismo de inspeção autorizado do aparelho/roteador; a aparência final da capa, isoladamente, não prova que nenhuma chamada foi feita. | PENDENTE — não executado em aparelho |
| D7 | Falha remota e preservação | Com uma capa válida já registrada, repita a atualização em uma condição de rede controlada na qual a atualização remota falhe. A capa previamente válida deve permanecer visível e não deve ser apagada somente porque a consulta/download falhou. Não corrompa arquivos ou banco manualmente no dispositivo principal. | PENDENTE — não executado em aparelho |
| D8 | Sem token | Instale o APK oficial do artefato, que foi compilado sem token TMDB, e atualize um título cujo metadado AniList é conhecido. O provedor TMDB deve ser ignorado sem bloquear a pesquisa AniList. | PENDENTE — análise estática confirma o ramo; aparelho não testado |

### APK com token para o caso D5

O APK do artefato normal do Actions não contém token TMDB. Para um teste real autorizado, compile localmente na branch, usando um token de teste que você possa usar nesse cliente:

```bash
# Digite o token sem eco e exporte-o apenas para este processo de shell.
read -rsp 'TMDB API Read Access Token: ' REIANIX_TMDB_READ_ACCESS_TOKEN
export REIANIX_TMDB_READ_ACCESS_TOKEN
printf '\n'
gradle -p android --no-daemon --console=plain :app:assembleRelease
unset REIANIX_TMDB_READ_ACCESS_TOKEN
```

O token é compilado no BuildConfig e pode ser extraído do APK distribuído; portanto, não use um segredo de longa duração que deva permanecer confidencial. Não escreva o token em arquivos versionados, não publique APK/token em logs e revogue ou rode o token de teste conforme a política aplicável após o teste. Não envie o token neste chat. Uma compilação local com assinatura de depuração ainda não será compatível com uma instalação assinada por outro certificado.

## 4. Relato dos resultados

Preencha os estados D1–D8 apenas depois de executar cada caso. Para cada item, registre PASS / FAIL / BLOCKED, versão Android, modelo/arquitetura, SHA-256 do APK, horário, descrição objetiva e o arquivo de evidência. Não cole credenciais, cabeçalhos Authorization nem URLs que contenham segredos.

## 5. Limites que permanecem

- Nenhum teste Kotlin/JVM foi executado neste ambiente nesta rodada; a tentativa de checkout foi bloqueada por resolução DNS e Gradle não está instalado. O build de release anterior não é resultado da suíte unitária.
- Nenhum teste em aparelho físico foi realizado aqui.
- Nenhuma consulta autenticada real ao TMDB foi realizada, por ausência de token autorizado.
- A política de expiração TMDB de 180 dias é aplicada pelo passe de manutenção/backfill existente. Se o app não executar esse passe, a limpeza aguarda a próxima execução; não há execução agendada garantida em segundo plano.
- A chave de assinatura do APK de avaliação é de depuração; não é uma release oficial e não deve ser tratada como compatível com atualização por assinatura estável.
