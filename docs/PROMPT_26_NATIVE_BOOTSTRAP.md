# Prompt 26/29 — Entrada Android nativa e bootstrap

Data: 2026-10-17  
Repositório de referência: `reinansantos9090-debug/ReiAnix`  
Base de implementação: `38273c688ed516f595e84a12e6306f6d7d135866`

## Alterações desta etapa

- `MainActivity` é uma `androidx.activity.ComponentActivity`, não uma Activity Flutter.
- `ReiAnixApplication` herda de `android.app.Application`; a raiz `ReiAnixAppDependencies` e o coordenador de processo `ReiAnixAppBootstrap` existentes foram preservados.
- O bootstrap continua validando a SQLite canônica e o seu contrato antes de expor consumidores da biblioteca. Falha de integridade leva a Recovery Mode; ausência da base sem evidências de recuperação continua sendo o caso de instalação nova.
- Depois da decisão do bootstrap, a Activity conecta o `ReiAnixComposeLibraryHost` existente ao root Android e direciona para Home ou Recovery Mode. Não foi criado outro App Shell nem um segundo grafo de navegação.
- O host usa as telas e rotas Compose já existentes, o grafo de dependências por processo, ViewModels existentes, estado de onboarding SAF e `NativePlayerActivity` com Media3.
- Um comando de compatibilidade antigo não pode ocultar a única superfície Compose do aplicativo. A política de Back permanece Android/Compose e não delega a um Flutter engine.
- O hook de template e o Gradle foram atualizados para documentar corretamente a separação entre runtime de entrada nativo e ferramentas híbridas de empacotamento.

## Componentes e dados preservados

Nenhuma base de dados foi recriada, migrada de forma destrutiva ou limpa. O fluxo continua usando `ReiAnixAppDependencies` e `ReiAnixSqliteDatabase` canônicos. O código de SAF, permissões persistentes, scanner nativo, atualização incremental de artwork, configuração, navegação e player não foi duplicado por esta mudança.

O bootstrap process-scoped mantém recuperação de operações interrompidas e inicialização controlada do dispatcher nativo. A Activity não inicia um scanner próprio nem faz consultas SQLite diretamente. Os ViewModels continuam aguardando uma decisão segura antes de iniciar consumidores do catálogo nativo.

## Limites que permanecem

Esta etapa **não** declara removidas globalmente Python, Flet ou Flutter. O workflow de APK continua usando Python/Flet/Flutter para preparar/renderizar o projeto Android e parte dos contratos históricos de projeção/IPC e operações ainda têm dependências Python documentadas em auditorias anteriores. Esses componentes de build e os contratos restantes foram mantidos para não introduzir um corte prematuro nem apagar funcionalidades que dependem da migração posterior.

O resultado desta etapa é a fronteira de entrada e composição da UI nativa, e não uma declaração de que todo fluxo de domínio já deixou de executar qualquer código Python. A migração total do build e das integrações restantes continua nas etapas próprias.

## Testes e workflows

Os testes estáticos de contrato foram atualizados para exigir a Activity/Application nativas e proibir delegação de Back a um engine Flutter. O workflow `ReiAnix Kotlin JVM Validation` permanece manual (`workflow_dispatch`), sem testes instrumentados ou testes de emulador adicionados por esta etapa.

Os resultados de build Android, certificação, PR e artifacts devem ser registrados a partir das execuções reais da revisão final; este documento não presume aprovação de uma execução ainda pendente.
