# ReiAnix — auditoria final de migração para Kotlin

Data da atualização: 2026-10-18 (UTC)  
Pull request de trabalho: #194, branch migration/prompt-28-native-scan-bridge-cutover-20261017  
Escopo: evidência estática do repositório e definição das verificações exigidas pelo Prompt 29. Os estados de CI finais desta alteração devem ser consultados na PR e não são antecipados neste documento.

## Conclusão executiva

**A migração integral ainda não pode ser certificada como concluída.** O repositório tem uma implementação Android nativa extensa: Activity e Application Android, UI Jetpack Compose, navegação, ViewModels, serviços Kotlin, acesso ao SQLite canônico, SAF/MediaStore, cliente AniList, artwork, backup/recuperação, diagnóstico e Media3/ExoPlayer. Porém, esses componentes não eliminam todos os consumidores híbridos.

A distribuição oficial continua a instalar Python 3.12, Flet 0.86.5 e Flutter 3.44.8 e executar a construção Flet do APK. O ponto de entrada Python main.py, NativeMailbox e consumidores de comandos/snapshots, telas Flet remanescentes e caminhos de compatibilidade continuam parte de fluxos reais. O APK nativo Gradle é uma verificação útil de compilação/empacotamento, mas não foi declarado substituto funcional do APK de distribuição. Não foi validado runtime físico neste trabalho.

## Estado de arquitetura encontrado

- O Manifest Android declara ReiAnixApplication, MainActivity e NativePlayerActivity. MainActivity é uma ComponentActivity nativa e ReiAnixApplication herda de Application; os testes de contrato verificam essas bases de ciclo de vida.
- ReiAnixComposeLibraryHost e ReiAnixNavigation montam a interface Compose principal; o projeto contém telas Compose para Home, Biblioteca, Detalhes, Minha Lista, Pesquisa, Configurações, Armazenamento, Organização e telas auxiliares.
- ReiAnixAppDependencies mantém uma composição de serviços por processo e ReiAnixAppBootstrap valida o banco antes de disponibilizar serviços nativos. O caminho existente preserva Recovery Mode quando a base não pode ser validada.
- O SQLite operacional canônico continua em filesDir/data/library.sqlite3, com contrato de esquema 29. Este trabalho não aumenta a versão, não cria banco paralelo e não redefine o arquivo.
- SAF, MediaStore, NativeScanController/Runner/Publisher e as operações nativas de biblioteca estão presentes. NativeMailbox/NativeCommandDispatcher ainda têm consumidores legados; por isso, varredura e projeções não são certificadas como independentes do runtime híbrido.
- AniList tem ReiAnixAniListClient e ReiAnixAniListIntegrationService Kotlin. Artwork possui ReiAnixArtworkService e extração nativa de miniaturas. Ainda há core/anilist.py, core/artwork.py, Pillow e consumidores Flet nos caminhos legados.
- Media3/ExoPlayer e NativePlayerActivity são implementações nativas reais. A presença dessas classes, isoladamente, não comprova que todos os fluxos de histórico, progresso, transições e comandos estão desconectados da mailbox.

## Correção desta etapa: evolução aditiva do SQLite existente

Foi adicionada a classe ReiAnixSqliteSchemaUpgrader para portar para Kotlin as mudanças de esquema aditivas feitas pelo LibraryStore durante a inicialização. Para um banco existente e reconhecível, a implementação:

1. abre apenas o arquivo canônico existente, sem modo de criação;
2. exige core tables e um registro de versão de schema conhecido, e verifica quick_check antes de escrever;
3. carrega o payload gerado do esquema canônico v29, cria tabelas auxiliares ausentes, adiciona apenas colunas cujas definições são seguras para ALTER TABLE e cria os índices/triggers canônicos;
4. refaz o índice derivado idx_artwork_key conforme a regra legada, normaliza o status de associação AniList e finaliza registros de scan que ficaram em estado running sem término, tudo na transação;
5. verifica o contrato da base, integridade SQLite e chaves estrangeiras antes de confirmar a transação;
6. recusa schemas desconhecidos, mais novos, corrompidos ou que precisem de uma transformação não aditiva. O chamador mantém os serviços bloqueados e encaminha para Recovery Mode.

A classe é integrada ao bootstrap nativo antes da decisão final de compatibilidade de um banco existente. A criação de um banco em instalação nova continua usando ReiAnixSqliteBootstrapper. Ambos utilizam o mesmo arquivo/caminho e a versão 29. Nenhum dado é apagado nem um banco existente é substituído.

**Limite de evidência:** o contrato Python adicionado fiscaliza a presença e o encadeamento do código-fonte. Ele não equivale a executar uma atualização de banco legado com dados reais, nem substitui um teste manual Kotlin/JVM ou a validação em dispositivo. A suíte Kotlin/JVM não deve ser executada automaticamente nem manualmente nesta etapa, conforme as restrições do projeto.

## Matriz final de aceitação

| Área | Evidência disponível nesta auditoria | Estado |
|---|---|---|
| Runtime exclusivamente Kotlin | O APK oficial ainda empacota Serious Python/Flet/Flutter e o entrypoint main.py continua ativo | **Pendente — requisito obrigatório não atendido** |
| Activity e bootstrap Android | Manifest, MainActivity nativa, ReiAnixApplication e coordinator Kotlin presentes; contratos de fonte existentes | **Implementado no código; CI final necessário; dispositivo não verificado** |
| Jetpack Compose e navegação | Shell, NavHost, rotas, telas e ViewModels Compose existem | **Parcial — existem consumidores e telas legadas** |
| Persistência canônica | Caminho library.sqlite3 e esquema v29 são compartilhados; bootstrap de instalação nova já existia; upgrader aditivo foi conectado nesta etapa | **Parcial — falta execução comportamental com banco legado** |
| SAF e permissões | Seletor, permissões persistentes, onboarding e scanners Android existem | **Parcial — coordenação/mailbox híbrida ainda presente** |
| Scanner e indexação | NativeScanController/Runner/Publisher e proteção de cobertura SAF/MediaStore já existem | **Parcial — consumidores residuais do ScanCoordinator não foram retirados** |
| AniList e metadados | Cliente e integração Kotlin existem; contratos de associação e patches SQL existem | **Parcial — compatibilidade Python permanece** |
| Capas e miniaturas | Serviço nativo e publicação incremental existem | **Parcial — ArtworkEngine/core/artwork.py ainda têm consumidores Flet** |
| Configurações | Tela, ViewModel e repositórios Kotlin existem | **Parcial — mailbox/projeções e settings Flet ainda permanecem** |
| Minha Lista, pesquisa e detalhes | Telas Compose e serviços/consultas nativos existem | **Parcial — todos os caminhos de compatibilidade não foram eliminados** |
| Organização | UI e serviços Kotlin de classificação/registro existem | **Parcial — ferramentas e consumidores Flet permanecem** |
| Backup, recuperação e diagnóstico | Serviços Kotlin com schema v29 e rota Recovery existem | **Parcial — contratos auxiliares legados continuam ativos** |
| Player, histórico e retomada | Media3/ExoPlayer, NativePlayerActivity e serviço de consumo existem | **Parcial — independência de todos os comandos/eventos não foi demonstrada** |
| Build Gradle nativo | Workflow nativo compila APK e inspeciona manifesto/DEX/ativos, cria ZIP e SHA-256 | **Verificação estrutural do build; não validação de runtime** |
| Distribuição APK/ZIP | Workflow oficial gera os artefatos convencionais, mas continua usando cadeia Flet/Flutter | **Parcial — ainda não é distribuição exclusivamente nativa** |
| Regressão Python | Workflow oficial executa pytest uma vez e verifica contratos/build | **Aguardando o resultado do SHA desta etapa** |
| Testes Kotlin/JVM | Workflow workflow_dispatch existente; execução manual não permitida nesta tarefa | **Não executado conforme a restrição** |
| Testes instrumentados/dispositivo | Nenhum teste de emulador foi adicionado/executado | **Não verificado em dispositivo** |
| Código residual | 219 arquivos Python estavam na árvore examinada antes desta alteração; muitos são testes/tooling, outros são runtime e caminhos Flet reais | **Classificado por função, ainda não eliminado globalmente** |
| CI do commit final | Deve ser consultado para o SHA resultante depois desta auditoria | **Pendente até término dos workflows** |

## Runtime, build e fontes — três resultados diferentes

**Runtime:** não independente. O caminho oficial continua carregando a infraestrutura híbrida e consumidores Python/Flet essenciais para paridade funcional.

**Build:** existe um workflow Gradle nativo separado que produz um APK de validação sem instalar Python/Flet/Flutter, e verifica a estrutura/ZIP do artefato. Isso prova que o conjunto de fontes nativas pode ser compilado por esse caminho, não que o APK tenha paridade funcional completa com o APK oficial.

**Código-fonte:** Python está presente tanto em testes/tooling quanto em entrypoint e módulos com consumidores de runtime. Não foi removido em massa porque isso quebraria fluxos ainda ativos e não atenderia ao requisito de preservar funcionalidades.

## Verificações e limites

Os workflows anteriores ao Prompt 29 tinham execuções bem-sucedidas no SHA anterior da PR #194, inclusive para o APK oficial e o APK nativo de validação. Esses resultados pertencem ao commit anterior e não certificam as alterações do Prompt 29. O resultado final deve ser atribuído exclusivamente ao novo SHA e a workflows terminados.

Nenhuma base pessoal real foi acessada ou alterada. Nenhum APK foi instalado/executado em dispositivo nesta auditoria. Não foi acionado o workflow Kotlin/JVM, nenhum teste instrumentado foi introduzido e a suíte Kotlin/JVM não foi executada manualmente. O build Gradle é uma validação de compilação/empacotamento; não deve ser descrito como teste de comportamento de usuário.

## Critério de conclusão global

O Prompt 29 só pode declarar a migração concluída depois de retirar ou substituir com paridade os consumidores reais de main.py/NativeMailbox/Flet/Flutter, estabelecer o caminho oficial de build/distribuição nativo, validar operações com fixtures apropriadas e confirmar os workflows obrigatórios no SHA final. Essas lacunas não podem ser encerradas honestamente apenas com a presença dos serviços Kotlin.

**Conclusão desta auditoria:** há progresso nativo concreto e foi corrigida a lacuna de atualização aditiva do schema SQLite existente, mas a migração total do runtime e da distribuição ainda não foi comprovada. A PR #194 deve permanecer em rascunho e sem merge até que essas lacunas sejam efetivamente resolvidas ou aceitas explicitamente como limite do produto.
