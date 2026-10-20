# Kotlin App Bootstrap Migration — Prompt 19/29

## Estado observado no repositório de referência

A aplicação ainda é um host híbrido. `ReiAnixApplication` herda de `FlutterApplication`, `MainActivity` herda de `FlutterFragmentActivity`, e o workflow Android prepara o template Flet/Flutter e o runtime Python. Portanto, esta etapa não declara inicialização integral independente de Python/Flet/Flutter.

Antes desta alteração, a raiz `ReiAnixAppDependencies` já existia e mantinha serviços nativos em propriedades `lazy(SYNCHRONIZED)`, incluindo banco e repositórios SQLite, consulta e mutações da biblioteca, lista pessoal, consumo, identidade de gêneros, artwork e AniList. Essa raiz é process-scoped; os repositórios de projeção legados continuam pertencendo aos ViewModels. Nenhum framework de injeção adicional foi introduzido.

A primeira versão desta etapa ainda iniciava a recuperação de receipts SAF e o dispatcher antes da decisão de integridade SQLite. A correção complementar reposiciona a validação da base canônica como primeira fase process-owned, preserva receipts quando a base estiver em recuperação e só libera a recuperação de receipts/dispatcher depois de uma decisão saudável ou de instalação nova. A Activity também passou a adiar a montagem do host Compose e o processamento de intents até essa decisão.

## Bootstrap Kotlin implementado

`ReiAnixApplication.onCreate()` cria uma única instância de `ReiAnixAppDependencies` e inicia seu coordenador `ReiAnixAppBootstrap`. A Activity também pode pedir o início; a operação é idempotente e não cria outro grafo ou dispatcher.

O bootstrap usa um `CoroutineScope(SupervisorJob() + Dispatchers.IO)` com vida útil do processo e publica um `StateFlow<ReiAnixBootstrapState>`. Não mantém referência de Activity e não faz trabalho de disco na thread principal.

### Ordem real após a correção complementar

1. Construir a única raiz process-owned de serviços nativos de forma preguiçosa.
2. Validar, em IO e sob mutex, o arquivo canônico `filesDir/data/library.sqlite3`: contrato de schema v29 e integridade SQLite para base existente.
3. Se a base estiver corrompida, vazia/incompatível ou houver evidências de promoção/recuperação interrompida, publicar `RECOVERY_REQUIRED`, manter receipts/snapshots e retornar sem recuperar receipts SAF nem iniciar o dispatcher.
4. Se a base estiver saudável, ou não existir sem qualquer evidência de recuperação, tentar recuperar receipts SAF com o `NativeScanRunner` existente. Se essa etapa falhar, revalidar o banco antes de permitir o dispatcher; se a integridade estiver comprometida, preservar os receipts e encaminhar ao Recovery Mode.
5. Iniciar o único `NativeCommandDispatcher` existente, depois da validação e da tentativa de recuperação segura dos receipts.
6. Somente depois da decisão process-owned, montar o host Compose na rota normal ou na rota Recovery; somente com base aprovada liberar os consumidores do catálogo nos ViewModels. A revalidação após restauração de Recovery Mode atualiza novamente o estado do bootstrap antes de atualizar os ViewModels e navegar para Home.

A validação não cria, substitui, migra nem apaga a base. A ausência da base sem evidência de promoção interrompida ou snapshot de recuperação é classificada como `NOT_CREATED`, não como biblioteca vazia nem corrupção. Falhas e evidências de recuperação são explícitas.

## Fases, ciclo de vida e falhas

O estado distingue `NOT_STARTED`, `STARTING`, `READY` e `DEGRADED`, etapas, estado de persistência, etapas concluídas e avisos. Os consumidores podem observar estados em andamento e degradação sem bloquear a thread principal. O ViewModel da biblioteca expõe o fluxo aos componentes Compose; o host observa mudanças de fase para diagnóstico, enquanto o onboarding de armazenamento e a rota de recuperação continuam utilizando seus estados funcionais específicos e canônicos.

Falhas na recuperação de receipts ou no canal de comandos são registradas sem incluir paths/URIs ou mensagens arbitrárias de SQLite nos logs. A gravação de diagnóstico é best-effort. A recuperação de receipts não libera o dispatcher se a revalidação do banco indicar risco; receipts e snapshots permanecem disponíveis para Recovery Mode. O bootstrap não reinicia operações em loop e não utiliza espera artificial, polling ou `GlobalScope`.

O estado de armazenamento e as permissões não são reconstruídos aqui. A Activity continua a usar o fluxo SAF/onboarding migrado no Prompt 17, validar a concessão real, registrar pasta no SQLite canônico e iniciar varreduras apenas pelas ações já protegidas por autorização. A recriação da Activity não cria uma segunda raiz de serviços nem inicia uma nova varredura a partir do bootstrap.

## Classificação das responsabilidades

### Grupo A — Kotlin nativo

- Composição process-scoped e compartilhamento lazy dos serviços Kotlin existentes em `ReiAnixAppDependencies`.
- Recuperação de receipts SAF interrompidos via `NativeScanRunner`, no fluxo coordenado.
- Inicialização do canal interno de comandos por `NativeCommandDispatcher`, após a tentativa de recuperação.
- Validação de integridade/esquema da base existente e decisão de encaminhamento à rota nativa de recuperação.
- Publicação do estado explícito de bootstrap por `StateFlow`.

### Grupo B — Parcialmente migradas

- Consultas, mutações, lista pessoal, consumo, configurações persistidas, AniList e artwork possuem serviços Kotlin, mas algumas projeções e comandos ainda trafegam pelas pontes da interface híbrida.
- NativeScanController/NativeScanRunner possuem execução e coordenação nativa; a Activity mantém as entradas que dependem de permissões/SAF e os adaptadores de compatibilidade.
- O player é nativo e continua sob demanda; o bootstrap não cria sessão, player nem progresso fictícios.
- O estado de inicialização pode ser observado por Compose, mas as telas mantêm seus estados funcionais próprios.

### Grupo C — Python/Flet/Flutter ainda necessários

`main.py` continua sendo iniciado pelo runtime Flet e ainda constrói `LibraryStore`, `SettingsStore`, `ComposeLibraryBridge`, `ComposeSettingsBridge` e `ScanCoordinator`, além de manter navegação/handlers usados por telas ou subsistemas remanescentes. `MainActivity` permanece em `FlutterFragmentActivity`; `ReiAnixApplication` permanece em `FlutterApplication`. Esses limites foram preservados intencionalmente. Não foram removidos `main.py`, runtime Python, Flet, Flutter nem os contratos das pontes.

A composição dos serviços nativos não depende de um bootstrap Python para reconstruir o grafo Kotlin, mas isto **não** equivale a provar que o aplicativo inteiro inicia funcionalmente sem Python.

## Propriedade dos serviços

- `ReiAnixApplication` é o dono do grafo process-scoped.
- `ReiAnixAppBootstrap` coordena somente o trabalho de inicialização e expõe estado; não é um segundo repositório.
- `ReiAnixSqliteDatabase` e repositórios compartilhados continuam vindo da mesma instância em `ReiAnixAppDependencies`.
- Repositórios de projeção continuam sendo geridos pelos ViewModels e fechados conforme o ciclo de vida já estabelecido.
- Serviços remotos/artwork são lazy ou acionados pelos consumidores existentes; não são aquecidos integralmente ao abrir o app.
- O scanner não é iniciado pelo bootstrap. Onboarding e permissões permanecem como pré-condição dos caminhos de varredura já existentes.

## Python removido ou preservado

Não foi removida delegação global Python porque a inicialização de LibraryStore/settings, o ScanCoordinator e as pontes ainda servem consumidores legados. A duplicação entre Activity e bootstrap para recuperação SAF e inicialização do dispatcher foi removida; a checagem da base passou a ter um proprietário explícito Kotlin. O adaptador híbrido continua onde ainda é necessário.

## Validação e limites

Foram adicionados testes de contrato Python executados pela suíte de regressão do workflow, além da documentação. Nenhum teste de emulador/instrumentado foi adicionado. Os testes Kotlin/JVM continuam exclusivamente manuais e não são executados por este prompt.

O workflow Android continua sendo responsável pela suíte Python, validações do host híbrido e compilação/empacotamento APK e ZIP. A compilação prova que o código Kotlin compila; não substitui uma verificação em aparelho físico de permissões SAF revogadas ou disponibilidade de provedores Android. A independência integral do runtime Python/Flet/Flutter permanece para as etapas finais da migração.


## Integração de startup e permissões validada na correção complementar

A Activity captura o intent inicial e enfileira intents recebidos durante a validação, monta o único Compose host apenas após a decisão do bootstrap e redireciona intenções de navegação normal para Recovery Mode enquanto a base exigir recuperação. Depois do redirecionamento, todos os demais comandos nativos — incluindo scan, playback, permissões, storage e conta — recebem resultado terminal de falha até a recuperação da base. No bridge de arquivos de configurações, somente verificação de integridade, snapshot bruto de segurança, restauração de recuperação e exportação de diagnóstico continuam permitidos; importação, reconciliação e restauração normal são bloqueadas para evitar mutações fora do fluxo de recuperação. Isso também evita que a rota Home seja exposta temporariamente antes do resultado da inspeção.

Os ViewModels do catálogo aguardam a publicação de uma fase `READY` e de persistência `HEALTHY` ou `NOT_CREATED` antes de iniciar leituras/consultas do catálogo. A simples abertura do aplicativo não dispara `requestPendingArtwork()`, reconciliação remota AniList, playback, nem scan geral. Uma base recém-instalada sem evidências continua `NOT_CREATED`, não é classificada como corrupção; o fluxo híbrido existente continua responsável por sua inicialização legítima.

A primeira inicialização sem raiz SAF usa o mesmo launcher `ACTION_OPEN_DOCUMENT_TREE`. Uma guarda de Activity impede lançamentos duplicados por recomposição/lifecycle/retorno do picker; a guarda é persistida no estado salvo da Activity. Cancelamento ou erro deixa a ação manual disponível e não dispara nova tentativa automática na mesma inicialização. Dismiss não oculta o caminho de onboarding enquanto nenhuma raiz autorizada estiver configurada.

## Registro auditável — Prompt 14/18 (2026-10-20)

**Status desta etapa: PARCIAL.** O fluxo nativo parcial e o coordenador process-scoped já existem no código atual. A independência completa não pode ser declarada nem as bridges host removidas com segurança: os consumidores e o pipeline Flet/Flutter continuam ativos. Esta seção registra a verificação do estado real para evitar que a próxima etapa trate uma classe Compose como prova de substituição integral.

### Caminho de inicialização observado no código

1. O manifesto registra `.ReiAnixApplication`, `.MainActivity` como launcher/deep-link e `.NativePlayerActivity` como Activity do player.
2. `ReiAnixApplication.onCreate()` chama `FlutterApplication.onCreate()`, cria uma única `ReiAnixAppDependencies` e inicia `dependencies.bootstrap.start()`.
3. `MainActivity.onCreate()` ainda passa por `FlutterFragmentActivity`; chama novamente `bootstrap.start()`, cuja proteção por `startupJob` é idempotente, restaura estado persistido da Activity e inicia a validação assíncrona do bootstrap.
4. `ReiAnixAppBootstrap` valida em IO a base canônica existente e o contrato SQLite. Se houver corrupção/incompatibilidade ou evidência persistida de recuperação interrompida, mantém as operações normais bloqueadas e solicita Recovery Mode. Se a base estiver saudável, ou for uma instalação realmente nova sem sinais de recuperação, executa a recuperação de receipts SAF e inicia o dispatcher nativo existente.
5. Depois da decisão do bootstrap, a Activity cria um único `ReiAnixComposeLibraryHost`, processa o intent inicial e os intents enfileirados durante a validação, e seleciona Home ou Recovery. O host instala um `ComposeView` e chama `setContent` apenas uma vez por view; a navegação usa um `NavHostController` lembrado pelo Compose. Portanto, a UI principal tem superfícies Compose nativas, mas a entrada do processo ainda é hospedada pelo Flutter.
6. O scanner não é iniciado indiscriminadamente pelo bootstrap. A decisão de descoberta continua subordinada ao lifecycle, estado do bootstrap e permissões/seleção SAF; player e artwork seguem sob demanda.

### Matriz de bridges e dependências

| Componente | Situação observada | Decisão para este prompt |
|---|---|---|
| `ReiAnixApplication : FlutterApplication` | Necessária ao host Flutter/Flet gerado pelo pipeline atual. | Preservada; trocar para `Application` agora removeria parte do bootstrap esperado pelo template híbrido. |
| `MainActivity : FlutterFragmentActivity` | É o host da Activity gerada; também coordena SAF, intents, identidade e o host Compose. | Preservada; mudar para `ComponentActivity` sem migrar o runtime/template e os consumidores de canal não é uma troca segura. |
| `ReiAnixComposeLibraryHost`, `ReiAnixComposeRoot` e navegação Compose | Código Kotlin ativo, com montagem condicionada ao resultado do bootstrap. O host ainda é anexado ao contêiner Android híbrido. | Preservados; a existência dessas classes não prova que o processo possa iniciar sem Flutter. |
| `NativeMailbox`, `NativeCommandDispatcher`, `NativeRequestState` e bridges em `core/` | Continuam ligados a ações, payloads, correlação de requests e consumidores Python/Flet e Kotlin. | Preservados; não foi demonstrado que todos os consumidores e efeitos colaterais foram substituídos. |
| `main.py`, `views/`, `core/library_store.py`, `core/library_service.py`, `core/scan_coordinator.py`, `core/android_bridge.py`, `core/compose_library_bridge.py`, `core/compose_settings_bridge.py` | O runtime e os fluxos Python/Flet continuam presentes no repositório e são incluídos/configurados no build oficial. | Preservados; nenhuma remoção comprovadamente segura nesta etapa. |
| `android/app/build.gradle.kts` e arquivos Gradle versionados | Definem o módulo Kotlin/Compose que é sobreposto no template de Android híbrido. | Preservados; não foi justificado alterar plugins, SDKs ou dependências do Gradle versionado. |
| `.github/workflows/build_apk.yml` | Prepara template Flet, chama `scripts/prepare_flet_template.py`, executa `flet build apk`, verifica o pacote Python e compila Kotlin no projeto Android renderizado em `build/flutter/android`. | Mantido. O caminho oficial do APK ainda depende de Python/Flet/Flutter; compilação Kotlin ocorre dentro desse host. |
| `.github/workflows/release_certification.yml` | Instala Python, dependências do projeto e Flet; executa a certificação de fontes sem Gradle. | Mantido; não é evidência isolada de que o APK Android seja independente do runtime legado. |
| `.github/workflows/kotlin_jvm_tests.yml` | Possui trigger manual `workflow_dispatch` e prepara ambiente Flet/Flutter antes dos testes. | Não disparado nem executado manualmente por esta etapa. |

### Persistência e compatibilidade

- O bootstrap e o serviço de backup verificam o nome canônico `library.sqlite3`; o proprietário Kotlin declara o contrato/schema v29 e não cria uma segunda base como fallback.
- Não há alteração de versão do schema, remoção de dados, migração destrutiva ou alteração do manifesto/Gradle nesta etapa.
- A equivalência absoluta entre `filesDir/data/library.sqlite3` no Kotlin e o diretório efetivo escolhido pelo runtime Python através de `FLET_APP_STORAGE_DATA` não fica provada apenas pelo código consultado. Essa reconciliação de caminho e comportamento de escrita compartilhada continua sendo um bloqueio de remoção, não uma autorização para criar outra base.
- Os componentes de inicialização já usam `applicationContext`, serviços lazy compartilhados, coroutines/IO e guarda de início. Não foi acrescentado framework DI, scanner no bootstrap, polling, espera artificial ou nova tarefa em background.

### Verificações desta etapa

- Inspeção estática do manifesto, Activity de entrada, Application, coordenador de bootstrap, raiz de dependências, host Compose, navegação, Gradle, documentação de migração, scripts de empacotamento e workflows existentes.
- Não foram executados testes instrumentados, emulador nem a suíte Kotlin/JVM manual.
- A execução da compilação Android depende do workflow oficial; um APK compilado, mesmo quando o workflow concluir, não comprova inicialização real em dispositivo.
- Para a confirmação do SHA anterior `2634f1be1966e172659266f2aadce02defd64587`, os workflows `Build ReiAnix Android APK` (run `39368106457`) e `ReiAnix Release Evidence Certification` (run `39368106414`) estavam em andamento quando esta auditoria foi registrada. Resultados desses runs não substituem os resultados relativos ao SHA que incorpora esta atualização documental.

### Bloqueios concretos para independência integral

1. Migrar para Kotlin os consumidores Python/Flet ainda ativos — incluindo inicialização/serviços de domínio, projeções/ações de biblioteca e configurações, scan coordinator, navegação e contratos das bridges — com paridade funcional comprovada.
2. Demonstrar que Kotlin e o runtime atual usam a mesma base/caminho canônico sem escritores concorrentes e sem mudança de identidade ou perda de dados.
3. Mudar o host e o pipeline de empacotamento somente depois de todos os consumidores Flutter/Flet terem sido removidos/substituídos; hoje o workflow oficial ainda gera o projeto Android a partir do template Flet.
4. Após essas migrações, validar o build oficial e seus artefatos no SHA exato. Nenhuma execução em dispositivo real foi realizada nesta etapa.

**Conclusão:** Prompt 14/18 fica **PARCIAL**. O bootstrap Kotlin e o shell Compose são partes existentes e ativas do fluxo híbrido; não foram reescritos desnecessariamente e nenhuma bridge foi removida sem prova. O Prompt 15/18 não foi iniciado.

## Auditoria do build — Prompt 15/18 (2026-10-20)

**Status: PARCIAL.** A referência anterior (SHA `aa061aad1ddcc9a46d05f6cec21a73770da86778`) tem build Android e certificação oficiais aprovados, mas isso valida somente o empacotamento híbrido. A cadeia oficial prepara Python 3.12, Flet 0.86.5 e Flutter 3.44.8, gera o host Android com `scripts/prepare_flet_template.py`, executa `flet build apk` e então chama o wrapper criado pelo template em `build/flutter/android` para `:app:compileReleaseKotlin`. A APK contém `assets/app.zip` e o runtime `serious_python`; a independência de Python/Flet/Flutter não é satisfeita.

O overlay `android/` não contém wrapper Gradle próprio nem o embedding Flutter; `MainActivity : FlutterFragmentActivity` e `ReiAnixApplication : FlutterApplication` ainda dependem desse host gerado. Nenhum plugin, versão, serviço, módulo ou arquivo de runtime legado foi removido, porque a remoção neste ponto impediria construir o host atual e perderia responsabilidades ainda ativas.

O fluxo Gradle/Kotlin existente não foi alterado de arquitetura. O workflow oficial foi ajustado para registrar o Gradle Wrapper efetivo, JDK, AGP, Kotlin/Compose Compiler, SDK, modo do build e tarefas executadas em `build/android-build-toolchain.txt`, além de publicar esse relatório junto do APK e dos metadados. Isso melhora a auditabilidade de cada SHA, mas não transforma o build em standalone.

A configuração versionada observada é AGP 8.9.1, Kotlin/Compose plugin 2.0.21, Java 17, compileSdk/targetSdk 36, minSdk 24 e Media3 1.11.1. O wrapper Gradle 8.14 foi observado na execução oficial anterior; como é obtido do template gerado, essa versão não está fixada por wrapper versionado no repositório.

O bloqueio restante é migrar as dependências funcionais de Python/Flet, retirar o embedding Flutter com equivalência funcional comprovada e só então versionar/validar um projeto Gradle completo. Não foi criado um segundo projeto, wrapper fictício nem task que apenas copie um APK. O Prompt 16/18 não foi iniciado.

