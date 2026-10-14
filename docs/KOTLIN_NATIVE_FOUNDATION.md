# Fundação de execução nativa Android — ReiAnix

## Referência e objetivo

- Repositório: reinansantos9090-debug/ReiAnix.
- Branch de referência antes das alterações: main.
- SHA de referência: 40e8c86be49282774ec45060aea9374155a14ca9.
- Base de implementação do Prompt 02: head do PR #166, 5e8e6e92f077a1a0a23c57794867c4ac517f5bb6, que contém a auditoria e o roadmap produzidos no Prompt 01.
- Pull Request do Prompt 01: https://github.com/reinansantos9090-debug/ReiAnix/pull/166 (aberto durante a preparação desta etapa).

Esta etapa cria uma composição de dependências nativas e define o ciclo de vida dos serviços já existentes. Não representa o corte definitivo para Kotlin puro.

## Estado encontrado antes da implementação

O manifesto declara .MainActivity como launcher. A classe efetiva é MainActivity : FlutterFragmentActivity(), e o workflow compila o overlay Kotlin dentro do projeto Android gerado pelo Flet. Portanto, o runtime real ainda é híbrido: o host Flutter/Flet inicia o runtime Python, main.py e os serviços de domínio Python necessários; o shell Compose é montado por ReiAnixComposeLibraryHost no host Android existente.

MainActivity.onCreate também liga a mailbox, recupera recibos de scans SAF interrompidos, configura a política da janela, monta o único host Compose e trata intents. No ciclo de vida, os observers MediaStore e os receivers de armazenamento são ativados em onStart e removidos em onStop; as permissões e o agendamento de descoberta são tratados no fluxo de retomada existente. A Activity salva IDs de requisição, fases do seletor SAF e dados de handoff do player para proteger a recuperação de estado.

Os ViewModels de Biblioteca e Configurações já são criados com factories compatíveis com Lifecycle. Os repositórios ReiAnixLibraryRepository e ReiAnixSettingsRepository são adapters de snapshot/IPC e precisam permanecer no ciclo de vida do ViewModel; eles não substituem o SQLite canônico.

## Implementação efetiva

### Entrada e Application

Foi adicionada ReiAnixApplication, registrada tanto no manifesto de overlay quanto no manifesto produzido pelo hook scripts/prepare_flet_template.py. Ela herda de FlutterApplication e chama super.onCreate() antes de criar ReiAnixAppDependencies, preservando o bootstrap do host híbrido. A classe Application não move regras de domínio nem inicia operações de longa duração.

A Activity de entrada permanece MainActivity : FlutterFragmentActivity(); nenhuma Activity concorrente ou novo shell foi criado. A chamada ao grafo é explícita nas factories usadas pela Activity. O shell Compose, o NavHostController, os destinos e a política atual de Back permanecem em ReiAnixComposeLibraryHost e ReiAnixNavigationHost.

### Composition root e ownership

ReiAnixAppDependencies usa construção manual — não foram adicionados Hilt, Koin ou dependências externas. O objeto por processo contém propriedades lazy(SYNCHRONIZED), sem executar trabalho pesado durante a construção. Os componentes integrados reutilizam o mesmo ReiAnixSqliteDatabase e o mesmo ReiAnixSqliteLibraryRepository:

- ReiAnixLibraryQueryService e ReiAnixPersonalListService, consumidos pelo ViewModel da Biblioteca;
- ReiAnixSqliteSettingsRepository, usado como acesso nativo de leitura às preferências existentes e compartilhado com o serviço AniList;
- ReiAnixAniListClient e ReiAnixAniListIntegrationService;
- ReiAnixConsumptionService, usado pela Activity do player para persistência de consumo/progresso;
- NativeScanRunner usa o mesmo sqliteLibraryRepository para indexar um snapshot completo de descoberta, mantendo sua coordenação, cancelamento, recuperação e publicação já existentes.

O banco só é aberto quando uma operação existente usa um serviço que precisa dele. A propriedade do grafo é sincronizada e suas dependências são lazy; o grafo não inicia scanner, não realiza download de capas, não dispara chamadas HTTP, nem executa uma consulta SQLite durante a inicialização global. Uma Application ausente no manifesto gera um erro de integração explícito em vez de criar silenciosamente outro grafo.

### Limites do ciclo de vida

Os adapters de projeção da Biblioteca e de Configurações continuam pertencendo aos ViewModels e são liberados pelos mecanismos onCleared/close existentes. Eles continuam enviando os comandos que ainda são propriedade do Python pela NativeMailbox; a injeção do repositório SQLite compartilhado não redireciona uma mutação para Kotlin nem muda o writer lógico. Serviços nativos sem recursos a fechar usam a instância do processo; as operações SQLite continuam limitadas à execução de cada chamada.

A Activity segue dona dos handlers de resultado Android, do seletor SAF, dos receivers/observers e do host Compose. A Activity do player continua dona da sessão Media3 e do seu ciclo de vida, mas recebe o serviço de consumo pela composição de dependências do processo. Os identificadores requestId, playerSessionId, playerGeneration e transitionGeneration, a navegação, as rotas, o shell, o player e os mecanismos de recuperação não são reimplementados nesta etapa.

## SQLite, scanner, armazenamento e serviços preservados

- O único arquivo canônico continua sendo filesDir/data/library.sqlite3, equivalente ao data_dir/library.sqlite3 criado pelo LibraryStore Python. O schema v29, as tabelas/índices atuais, os IDs persistidos e os dados não são alterados por esta fundação.
- Nenhum Room, banco paralelo, repositório de catálogo alternativo, cópia de banco, alteração de backup ou migração de schema foi introduzido.
- A instância de ReiAnixSqliteDatabase compartilhada pelos consumidores integrados fornece um único lock Kotlin para as operações que passam pelo grafo. A coexistência Python/Kotlin e os writers legados que ainda não foram migrados continuam sendo uma limitação explícita; cada operação ainda precisa ter um único owner lógico.
- SAF, MediaStore, BroadStorageScanner, NativeScanController, NativeScanPublisher e NativeIndex mantêm seus mecanismos atuais. Não se inicia scan no Application.onCreate; a recuperação de recibos e os pedidos de scan continuam sob o fluxo atual da Activity/coordenação híbrida.
- AniList foi conectado à instância nativa existente, mas o matching/tradução/cache e o pipeline de artwork que ainda pertencem a Python não foram removidos. Falhas remotas continuam sem bloquear globalmente a abertura da interface.
- NativePlayerActivity, Media3/ExoPlayer, legendas, progresso, histórico e correlação de sessão permanecem existentes; esta etapa muda apenas a origem da instância de consumo nativo para a composição compartilhada.

## Fronteiras híbridas que continuam necessárias

O APK ainda é gerado por Flet e Flutter e o launcher ainda herda de FlutterFragmentActivity. O runtime de Python permanece necessário para main.py, roteamento legado, LibraryStore/schema v29 e operações de domínio ainda não migradas, LibraryService, ScanCoordinator, bridges/snapshots de Biblioteca e Configurações, reconciliação, AniList/metadados e partes centrais do ArtworkEngine/Pillow, bem como backup e recovery legados.

NativeMailbox, NativeCommandDispatcher e os arquivos JSON privados seguem sendo fronteiras transitórias utilizadas por consumidores reais. Esta etapa não remove Flet, Flutter, Python, os serviços existentes, rotas, permissões nem contratos. A próxima etapa continua sendo MIG-02 do roadmap — contratos de domínio e baseline de paridade — e não autoriza a remoção de uma ponte até que todos os consumidores e efeitos colaterais sejam migrados com equivalência.

## Verificação e evidências

Os testes estáticos Python já integrados ao workflow de build foram estendidos para verificar o registro da Application no manifesto gerado e a composição compartilhada. Não foram adicionados testes instrumentados/emulador. Nenhum teste Kotlin/JVM deve ser executado manualmente; o workflow correspondente permanece manual (workflow_dispatch).

A compilação Android, a suíte Python e os artefatos APK/ZIP devem ser avaliados no workflow obrigatório do SHA final desta implementação. A conclusão depende do status final desse run; uma compilação ou execução histórica de outro SHA não demonstra aceitação.

## Critérios para as próximas etapas

1. Ao migrar uma mutação, primeiro provar quem a executa hoje e desligar apenas esse mesmo envio legado após equivalência confirmada; o grafo não deve permitir um writer duplicado por conveniência.
2. Manter o lock/objeto SQLite compartilhado para serviços nativos que participam da mesma operação. Todos os consumidores fora da composição devem ser auditados antes de movê-los para ela.
3. Migrar criação/upgrade do schema em Kotlin somente numa etapa explícita, preservando os dados existentes e os contratos do schema 29.
4. Migrar coordenação/cancelamento/reconciliação de scans como unidade; scans parciais ou cancelados nunca devem autorizar remoções.
5. Migrar o pipeline de metadados e imagens com paridade para manter capas, local_path, metadados manuais e comportamento offline.
6. Remover a herança Flutter, runtime Python ou mailbox somente após identificar seus consumidores transitivos e comprovar funcionamento nativo em todos os fluxos relevantes.
