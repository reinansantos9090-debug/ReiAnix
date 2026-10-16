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

A Activity captura o intent inicial e enfileira intents recebidos durante a validação, monta o único Compose host apenas após a decisão do bootstrap e redireciona intenções de navegação normal para Recovery Mode enquanto a base exigir recuperação. Isso também evita que a rota Home seja exposta temporariamente antes do resultado da inspeção.

Os ViewModels do catálogo aguardam a publicação de uma fase `READY` e de persistência `HEALTHY` ou `NOT_CREATED` antes de iniciar leituras/consultas do catálogo. A simples abertura do aplicativo não dispara `requestPendingArtwork()`, reconciliação remota AniList, playback, nem scan geral. Uma base recém-instalada sem evidências continua `NOT_CREATED`, não é classificada como corrupção; o fluxo híbrido existente continua responsável por sua inicialização legítima.

A primeira inicialização sem raiz SAF usa o mesmo launcher `ACTION_OPEN_DOCUMENT_TREE`. Uma guarda de Activity impede lançamentos duplicados por recomposição/lifecycle/retorno do picker; a guarda é persistida no estado salvo da Activity. Cancelamento ou erro deixa a ação manual disponível e não dispara nova tentativa automática na mesma inicialização. Dismiss não oculta o caminho de onboarding enquanto nenhuma raiz autorizada estiver configurada.
