# Build Android nativo e diagnóstico dos APKs — Prompt 30.2

## Variantes e workflows reais

O único módulo Android é `:app`, mapeado para `android/app`. Ambos os APKs do fluxo atual são compilados por Gradle, JDK 17 e Android SDK 36:

- `.github/workflows/build_apk.yml` — **release oficial de produção**. Executa `:app:assembleRelease`, valida o Manifest/DEX/assets e exclui do APK `assets/app.zip`, `flutter_assets`, bytecode Python e bibliotecas de runtime legado. O artefato oficial é `ReiAnix-apk-instalacao-direta-<run>`; o ZIP de instalação contém o APK e os metadados/checksum.
- `.github/workflows/native_gradle_apk.yml` — **APK debug de validação**, executa `:app:assembleDebug` sem configurar Python, Flet ou Flutter. Produz `ReiAnix-native-gradle-apk-<run>` e ZIP equivalente. Não é uma versão substituta para atualizar o release instalado.
- `.github/workflows/python_regression.yml` — executa a regressão Python/compatibilidade separadamente. Seu sucesso não comprova o runtime nativo.
- `.github/workflows/build_hybrid_legacy.yml` — fluxo híbrido legado restrito a disparo manual; não é o workflow automático de APK de produção.

A documentação histórica anterior ao Prompt 30.1 que diz que `build_apk.yml` ainda instala Flet/Flutter para empacotar o APK foi supersedida. Isso descreve uma configuração antiga, não o workflow corrente. O código-fonte continua a conter módulos Python/Flet, porém não estão incluídos nos dois APKs nativos atuais; qualquer funcionalidade ainda dependente de um consumidor exclusivo desses módulos permanece sem paridade no APK nativo.

## O que a validação de build cobre — e o que não cobre

Os workflows verificam checkout/SHA, compilação, integridade do ZIP, Manifest, application ID, entradas DEX, recursos SQLite v29, conteúdo legado proibido, checksums e assinatura do APK. O prompt 30.2 acrescenta ao artefato o relatório do certificado de assinatura e seu digest SHA-256, além dos ABIs empacotados, para permitir diagnóstico independente de conflitos de instalação.

Essas verificações comprovam a compilação e a estrutura do pacote. Não comprovam que a Activity inicia com sucesso em aparelho físico, que cada botão tem consumidor Kotlin ativo, que o scanner encontra arquivos, que progresso do player persiste ou que um banco pessoal existente foi validado. O manifesto deve manter `runtimeIndependenceCertified: false` até existir evidência funcional suficiente.

## Comparação auditada dos artefatos anteriores ao Prompt 30.2

Os dois artefatos abaixo são do commit anterior `8537ee6f827de2d5b53f9b0ff061f964b2879bd0`; eles são evidência histórica do diagnóstico que motivou esta etapa, não os artefatos gerados pelo HEAD final do Prompt 30.2.

| Propriedade | Release oficial (#6072) | Validação Gradle (#108) |
|---|---|---|
| Run | [39104981877](https://github.com/reinansantos9090-debug/ReiAnix/actions/runs/39104981877) | [39104981822](https://github.com/reinansantos9090-debug/ReiAnix/actions/runs/39104981822) |
| Artefato Actions | [12127812080](https://github.com/reinansantos9090-debug/ReiAnix/actions/runs/39104981877/artifacts/12127812080) | [12128455869](https://github.com/reinansantos9090-debug/ReiAnix/actions/runs/39104981822/artifacts/12128455869) |
| Variante | `release` | `debug` |
| `applicationId` | `com.reiflix.reiflix_local` | `com.reiflix.reiflix_local` |
| `versionCode` / `versionName` | `2006072` / `0.2.1` | `2` / `0.2.1` |
| Tamanho do APK interno | 19.876.738 bytes | 28.275.281 bytes |
| SHA-256 do APK interno | `5dfcadd68c6ee8acd882cd76203b55d4e78fd0043900195f189e8612380b36d1` | `2de50b0db5f9fde392607ef6c871b2660140cfd553c3196dfc70d6b769444aa7` |
| SHA-256 do certificado de assinatura | `81ebf2b9aa52cd455cb4ec3af87c9c68506d83c347e77a3d5d8cd03d902755ab` | `1a42981251b7e79f5d1f90dc712cb94cc8de60a0c126f1946b5a54a1f942ea3b` |
| ABI(s) no APK | `arm64-v8a` | `arm64-v8a, armeabi-v7a, x86, x86_64` |
| Payload Python/Flet/Flutter verificado | Ausente | Ausente |
| `runtimeIndependenceCertified` | `false` | `false` |

Os checksums indicados foram recalculados sobre os APKs extraídos dos artefatos Actions, e os arquivos `.sha256` dentro dos artefatos passaram em `sha256sum -c`. A inspeção das entradas também confirmou os assets `native-sqlite-contract-v29.json` e `native-sqlite-bootstrap-v29.json` e classes DEX nativas. Nenhum destes dados é log de execução em dispositivo.

## Diagnóstico confirmado sobre a instalação

Os APKs usam o mesmo `applicationId`, mas os certificados SHA-256 são diferentes. O release tinha sido assinado com `debug-fallback`, porque os segredos para assinatura estável não estavam configurados. Por isso, **um APK não é uma atualização compatível do outro**. Além disso, o APK de validação tem `versionCode=2`, inferior ao release `2006072`, então uma tentativa de substituir o release pelo debug também pode ser recusada por downgrade. A diferença de tamanho decorre, entre outros pontos, do conjunto de ABI: o release empacota apenas arm64; a variante debug empacota quatro ABI.

Esse conflito explica uma causa concreta possível para falhas ao instalar o segundo artefato por cima do primeiro; **não explica nem prova um crash na inicialização de uma instalação nova**. Não tente resolver isso desinstalando o aplicativo ou limpando dados. É necessária a chave estável correta para compatibilidade de atualização e uma inspeção do erro real para diagnosticar a inicialização.

O workflow oficial também reportou ausência de `REIFLIX_GOOGLE_WEB_CLIENT_ID`; isso afeta o login Google e não foi demonstrado como causa de falha de inicialização. Nenhuma credencial foi inventada.

Para o inventário completo do diagnóstico, fingerprints dos certificados, checksums dos APKs e a matriz por área funcional, consulte [`docs/PROMPT_30_2_APK_DIAGNOSTICS.md`](PROMPT_30_2_APK_DIAGNOSTICS.md). Esse documento diferencia a evidência histórica do SHA anterior dos resultados que ainda precisam ser gerados sobre o HEAD final.

## Estado funcional que permanece aberto

- **Inicialização:** Manifest registra `.ReiAnixApplication` e a launcher `.MainActivity`; a Activity chama o bootstrap nativo e mostra Compose após a decisão sobre a base. Essa inspeção estática não substitui logcat de um crash real.
- **SQLite:** há uma única base canônica em `filesDir/data/library.sqlite3`, schema v29. Instalação nova usa bootstrapper Kotlin com candidata verificada; banco existente passa por validação/upgrade conservador. Não foi testado um banco pessoal real neste diagnóstico.
- **SAF, MediaStore e scanner:** componentes nativos existem e são conectados ao fluxo de Compose, mas a operação real no aparelho — permissões, seleção de diretório, varredura, ingestão e persistência — não foi observada nesta execução.
- **Biblioteca, settings, AniList/arte, backup/restauração e player:** implementações Kotlin existem em diferentes estágios; ainda há compatibilidade e funções de domínio no código Python. `NativeMailbox` devolve sucesso sem publicar evento quando `assets/app.zip` não está presente; isso impede filas órfãs, mas significa que um fluxo que ainda dependa exclusivamente de um consumidor Python não será executado pelo APK nativo. Essas áreas não estão certificadas globalmente.
- **Dispositivo físico:** não houve acesso autorizado a um dispositivo nem captura de logcat neste workflow. Não afirmamos que os APKs foram instalados, abertos ou que reproduziram vídeo com sucesso.

Para fechar o diagnóstico de inicialização, é indispensável obter o erro real do aparelho: mensagem exibida ao abrir (fecha imediatamente, tela preta, tela vazia ou erro de instalação) e, se houver ADB, trecho de `adb logcat -d` contendo `AndroidRuntime`, `ReiAnixAppBootstrap`, `ReiAnixApplication` e `[REIFLIX][ANDROID]` na hora da falha. Não apagar dados para coletar esse log.

## Regra de certificação

`runtimeIndependenceCertified` permanece `false`. A migração não pode ser declarada completa somente porque Gradle compila, o Manifest contém uma Activity nativa ou o APK não inclui arquivos Python. Certificação exige execução comprovada dos fluxos reais, compatibilidade com dados existentes e, para validação física, testes autorizados em dispositivo Android real.
