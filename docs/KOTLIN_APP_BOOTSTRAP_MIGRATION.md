# Kotlin App Bootstrap Migration — Prompt 19/29

## Estado observado no repositório de referência

A aplicação ainda é um host híbrido. `ReiAnixApplication` herda de `FlutterApplication`, `MainActivity` herda de `FlutterFragmentActivity`, e o workflow Android prepara o template Flet/Flutter e o runtime Python. Portanto, esta etapa não declara inicialização integral independente de Python/Flet/Flutter.

Antes desta alteração, a raiz `ReiAnixAppDependencies` já existia e mantinha serviços nativos em propriedades `lazy(SYNCHRONIZED)`, incluindo banco e repositórios SQLite, consulta e mutações da biblioteca, lista pessoal, consumo, identidade de gêneros, artwork e AniList. Essa raiz é process-scoped; os repositórios de projeção legados continuam pertencendo aos ViewModels. Nenhum framework de injeção adicional foi introduzido.

A Activity também fazia diretamente a recuperação de receipts de varreduras SAF interrompidas, iniciava `NativeCommandDispatcher` após essa recuperação e executava o diagnóstico de integridade da base canônica. A ordem das duas primeiras operações foi transferida para o bootstrap nativo; a validação SQLite foi movida para o mesmo coordenador e continua determinando a abertura da rota de recuperação.

## Bootstrap Kotlin implementado

`ReiAnixApplication.onCreate()` cria uma única instância de `ReiAnixAppDependencies` e inicia seu coordenador `ReiAnixAppBootstrap`. A Activity também pode pedir o início; a operação é idempotente e não cria outro grafo ou dispatcher.

O bootstrap usa um `CoroutineScope(SupervisorJob() + Dispatchers.IO)` com vida útil do processo e publica um `StateFlow<ReiAnixBootstrapState>`. Não mantém referência de Activity e não faz trabalho de disco na thread principal.

### Ordem real

1. Construir a raiz de serviços nativos de forma preguiçosa, sem abrir o banco apenas para construir o grafo.
2. Tentar recuperar receipts de varreduras SAF interrompidas por meio do `NativeScanRunner` existente.
3. Iniciar `NativeCommandDispatcher` com contexto de aplicação, depois da tentativa de recuperação. O dispatcher continua a proteger sua inicialização duplicada.
4. Quando a Activity precisar decidir se abre a rota de recuperação, validar a base canônica sob mutex, em IO: reconhecer instalação nova quando o arquivo ainda não existe e não há evidência de recuperação; para um arquivo existente, verificar o contrato de esquema e o relatório de integridade.
5. Publicar o estado final para os consumidores Compose e manter a rota de recuperação existente quando a validação exigir intervenção.

A validação não cria, substitui, migra nem apaga a base. A ausência da base sem evidência de promoção interrompida ou snapshot de recuperação é classificada como `NOT_CREATED`, não como biblioteca vazia nem corrupção. Falhas e evidências de recuperação são explícitas.

## Fases, ciclo de vida e falhas

O estado distingue `NOT_STARTED`, `STARTING`, `READY` e `DEGRADED`, etapas, estado de persistência, etapas concluídas e avisos. Os consumidores podem observar estados em andamento e degradação sem bloquear a thread principal. O ViewModel da biblioteca expõe o fluxo aos componentes Compose; o host observa mudanças de fase para diagnóstico, enquanto o onboarding de armazenamento e a rota de recuperação continuam utilizando seus estados funcionais específicos e canônicos.

Falhas na recuperação de receipts ou no canal de comandos são registradas e produzem inicialização degradada; não são convertidas em sucesso silencioso. A gravação de diagnóstico é best-effort e não impede a inicialização. O bootstrap não reinicia operações em loop e não utiliza espera artificial, polling ou `GlobalScope`.

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
