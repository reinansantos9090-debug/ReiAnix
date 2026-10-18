# Prompt 30.2 — diagnóstico e certificação do ReiAnix nativo

Data: 18/10/2026 UTC. PR de trabalho: [#194](https://github.com/reinansantos9090-debug/ReiAnix/pull/194). Branch: `migration/prompt-28-native-scan-bridge-cutover-20261017`.

## Escopo da evidência

O diagnóstico inicial foi realizado sobre o commit anterior `8537ee6f827de2d5b53f9b0ff061f964b2879bd0`. A branch e os três runs abaixo foram consultados remotamente:

- [Build oficial #6072 / run 39104981877](https://github.com/reinansantos9090-debug/ReiAnix/actions/runs/39104981877): `completed / success`, SHA `8537ee6f827de2d5b53f9b0ff061f964b2879bd0`.
- [Validação Gradle #108 / run 39104981822](https://github.com/reinansantos9090-debug/ReiAnix/actions/runs/39104981822): `completed / success`, mesmo SHA.
- [Regressão Python / run 39104981861](https://github.com/reinansantos9090-debug/ReiAnix/actions/runs/39104981861): `completed / success`, mesmo SHA; relatório do job indica 2.006 testes e 58 subtests aprovados.

Esses são os resultados do **HEAD anterior**, não dos commits posteriores do Prompt 30.2. A conclusão do prompt depende dos runs executados sobre o SHA final após as alterações.

## Comparação binária dos APKs

| Campo | Release oficial (#6072) | Gradle debug de validação (#108) |
|---|---|---|
| Artefato Actions | [12127812080](https://github.com/reinansantos9090-debug/ReiAnix/actions/runs/39104981877/artifacts/12127812080) | [12128455869](https://github.com/reinansantos9090-debug/ReiAnix/actions/runs/39104981822/artifacts/12128455869) |
| Tamanho do arquivo de artefato Actions | 18.674.447 bytes | 26.963.301 bytes |
| Tamanho do APK extraído | 19.876.738 bytes | 28.275.281 bytes |
| SHA-256 do APK extraído | `5dfcadd68c6ee8acd882cd76203b55d4e78fd0043900195f189e8612380b36d1` | `2de50b0db5f9fde392607ef6c871b2660140cfd553c3196dfc70d6b769444aa7` |
| `applicationId` | `com.reiflix.reiflix_local` | `com.reiflix.reiflix_local` |
| Build variant | release | debug |
| `versionCode` / `versionName` | 2006072 / 0.2.1 | 2 / 0.2.1 |
| ABIs no APK | `arm64-v8a` | `arm64-v8a, armeabi-v7a, x86, x86_64` |
| SHA-256 do certificado de assinatura | `81ebf2b9aa52cd455cb4ec3af87c9c68506d83c347e77a3d5d8cd03d902755ab` | `1a42981251b7e79f5d1f90dc712cb94cc8de60a0c126f1946b5a54a1f942ea3b` |
| Payload Python/Flet/Flutter | Ausente na inspeção de entradas | Ausente na inspeção de entradas |
| `runtimeIndependenceCertified` | `false` | `false` |

Os hashes do APK extraído correspondem aos SHA-256 registados nos manifests históricos do run. Os checksums fornecidos dentro dos artefatos passaram em `sha256sum -c`; os arquivos ZIP foram validados e o APK interno foi inspecionado com leitor ZIP independente. Os assets `native-sqlite-contract-v29.json`, `native-sqlite-bootstrap-v29.json` e classes DEX foram localizados. Isso certifica empacotamento estrutural, não comportamento da aplicação em execução.

## Causa confirmada de incompatibilidade de instalação

Ambos os APKs têm o mesmo `applicationId` e **certificados SHA-256 diferentes**. A assinatura não é compatível entre eles para atualização. No run oficial analisado, o log confirmou que os segredos de assinatura estável estavam ausentes e o workflow assinou o release com `debug-fallback`. O APK debug também é produzido em outro workflow, com certificado de debug diferente. Além disso, o `versionCode` debug (2) é inferior ao release (2006072), o que configura downgrade ao tentar instalar o debug por cima do release.

Essa é uma causa comprovada para a recusa de instalação/substituição entre esses artefatos. **Não é prova da causa de uma tela preta ou encerramento após uma instalação nova.** Corrigir atualização compatível exige configurar os segredos da chave estável legítima; não se deve inventar chave nem desinstalar/limpar um app com dados pessoais para contornar esse problema.

O log do run oficial também registou a ausência de `REIFLIX_GOOGLE_WEB_CLIENT_ID`. Isso deixa o login Google sem configuração, mas não foi demonstrado como causa de falha de inicialização.

## Fluxo de inicialização inspecionado

- `AndroidManifest.xml` declara `.ReiAnixApplication`, launcher `.MainActivity` e `.NativePlayerActivity`.
- `ReiAnixApplication.onCreate()` constrói um único `ReiAnixAppDependencies` process-scoped e inicia `ReiAnixAppBootstrap`.
- `MainActivity.onCreate()` chama o bootstrap e aguarda a decisão de integridade do SQLite antes de criar `ReiAnixComposeLibraryHost` e abrir Home ou Recovery Mode.
- Em instalação genuinamente nova, `ReiAnixSqliteBootstrapper` cria uma candidata para schema v29, valida contrato/integridade e promove sem sobrescrever uma base canônica existente. A abertura comum continua open-only.
- Em APK sem `assets/app.zip`, `NativeMailbox.write()` retorna sem publicar eventos e `NativeCommandDispatcher` não inicia. Isso evita uma fila sem consumidor legado. Porém, qualquer fluxo de produto que ainda dependa exclusivamente de uma operação Python não tem esse consumidor no APK e permanece um risco funcional até existir substituto Kotlin comprovado.
- A inspeção estática identifica launcher e serviços nativos; não observou cold start real, concessão SAF, ingestão de vídeos, AniList, persistência de progresso ou reprodução em aparelho.

## Matriz de certificação

| Área | Estado | Evidência/limite |
|---|---|---|
| Compilação Android | **Certificada para o SHA auditado** | Gradle release e debug terminaram com sucesso nos runs listados; precisa ser revalidada no HEAD final. |
| Empacotamento nativo | **Certificado estruturalmente para o SHA auditado** | Manifest, DEX, assets, ausência de payload legado, checksums e ZIP verificados. Não equivale a runtime funcional. |
| Inicialização em Android | **Parcialmente validada** | Sequência Manifest/Application/Activity/bootstrap existe; falta logcat/execução real. |
| UI e navegação | **Parcialmente validada** | Compose host e rotas nativas existem; eventos e fluxo completo de tela não foram exercitados no aparelho. |
| SAF, MediaStore e scanner | **Parcialmente validado** | Serviços Kotlin existem; permissões, URI persistida, descoberta e escrita SQLite ponta a ponta não foram observadas no dispositivo. |
| SQLite v29 e instalação nova | **Parcialmente validado** | Código/bootstrap e assets presentes; não houve execução física nem validação de base pessoal real. |
| Base existente/dados pessoais | **Não verificada** | Nenhum banco pessoal foi acessado; não apagar/reconstruir dados para testar. |
| AniList, capas e thumbnails | **Parcial** | Serviços Kotlin coexistem com contratos/caminhos Python, incluindo artwork legado; paridade completa não demonstrada. |
| Player Media3 e progresso | **Parcial** | Activity e classes estão no DEX; reprodução e gravação/recuperação de progresso não foram observadas em dispositivo. |
| Configurações, backup e restauração | **Parcial / pendente por função** | Serviços nativos existem, mas a equivalência integral das operações não foi demonstrada. |
| Independência funcional Python/Flet/Flutter | **Pendente — não certificada** | O payload foi excluído; isso não prova que todas as funções têm consumidor Kotlin real. |
| Validação em aparelho Android real | **Não executada** | Ausência de dispositivo e logs de runtime nesta execução. |
| Assinatura estável e Google Login | **Bloqueada externamente** | Segredos de assinatura e Web Client ID estavam ausentes no run anterior. Login não deve bloquear funções independentes. |

## Evidência indispensável que ainda falta

Não há logcat do aparelho nem descrição exata da falha para afirmar por que a aplicação aparenta não funcionar depois de instalada. Preciso do comportamento exato (recusa de instalação, fechamento imediato, tela preta/branca, tela aberta sem biblioteca ou erro após uma ação) e, quando possível, do log do instante da falha. Com ADB, recolher `adb logcat -d` e filtrar por `AndroidRuntime`, `ReiAnixAppBootstrap`, `ReiAnixApplication` e `[REIFLIX][ANDROID]`. Não desinstalar nem limpar dados para recolher isso.

**Decisão deste snapshot:** **CORREÇÕES DOCUMENTAIS E DE DIAGNÓSTICO EM ANDAMENTO; CERTIFICAÇÃO FUNCIONAL PENDENTE.** O novo estado só pode ser decidido após correções e workflows terminais no HEAD final; a independência funcional permanece falsa enquanto faltarem evidências de execução real.
