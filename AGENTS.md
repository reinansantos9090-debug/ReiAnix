# AGENTS.md — ReiAnix

## Objetivo e destino arquitetural

A branch ativa contém uma aplicação Android nativa em Kotlin, Jetpack Compose, Gradle e SQLite. Mantenha esse caminho como a única arquitetura de execução e compilação suportada.

- Não reintroduza fontes `.py`, `requirements.txt`, `pyproject.toml`, Flet, Flutter ou passos de instalação/execução de Python no build, testes ou workflows.
- A configuração de release, identidade Android, schema SQLite v29, dados dos usuários, permissões SAF/MediaStore, AniList, artwork, navegação Compose e player Media3 são contratos existentes que devem ser preservados.
- Documentos de migração antigos em `docs/` podem descrever snapshots históricos híbridos; não os trate como prova do estado atual nem reative dependências que já foram removidas.
- Não crie uma segunda arquitetura paralela nem uma segunda fonte de verdade para banco, catálogo, scanner, artwork ou progresso.
- Considere a migração nativa completa apenas naquilo que o código, build e workflows atuais comprovarem. Diferencie análise estática, testes executados, build de APK e validação em dispositivo real.

## Regras obrigatórias de preservação

- Preserve todas as funcionalidades existentes que já funcionam.
- Não remova arquivos, classes, funções, testes, proteções, integrações ou fluxos apenas porque parecem antigos, redundantes ou não utilizados.
- Antes de remover ou alterar algo, verifique referências, call sites, dependências, fluxo Python/Flet ↔ Kotlin/Android, testes e comportamento em runtime.
- Se houver dúvida sobre a necessidade de um código existente, não o remova.
- Não faça refatorações amplas ou mudanças arquiteturais fora do escopo da tarefa.
- Prefira mudanças mínimas, locais, rastreáveis e compatíveis com a arquitetura existente.
- Não transforme uma correção de bug em uma funcionalidade não relacionada.
- Não remova testes existentes para fazer a suíte passar.
- Não desative validações, tratamento de erros, concorrência, lifecycle, cache, armazenamento, player ou proteções existentes sem evidência técnica de que são incorretos e sem preservar o comportamento necessário.
- Não introduza um banco de dados, fonte de verdade, repositório ou gravador concorrente para contornar a migração. Preserve o SQLite/LibraryStore existente como fonte de verdade até que uma mudança de persistência explicitamente planejada e validada seja necessária.
- Preserve a integração existente de SAF, MediaStore, scanner/ScanCoordinator, artwork e thumbnails, metadados/AniList, histórico e progresso de reprodução, NativePlayerActivity e Media3/ExoPlayer. Migre as responsabilidades para Kotlin sem descartar seus comportamentos.
- Não altere applicationId, namespace, assinatura, configuração de release ou identidade do app sem necessidade técnica demonstrada e escopo explícito.

## Proibição absoluta de novos testes/emuladores no repositório

- É proibido adicionar ao repositório qualquer workflow, script, job, etapa de CI ou infraestrutura que crie, inicialize ou execute um emulador Android especificamente para validar uma tarefa, etapa ou alteração.
- Não adicione testes de emulador ao repositório em hipótese alguma.
- Não crie workflows temporários ou permanentes de validação que usem emulador Android.
- Não adicione etapas como `Run ... instrumentation test on Android ...`, `android-emulator-runner`, matrizes de APIs/emuladores, Android TV emulator ou mecanismos equivalentes.
- Não adicione upload de artefatos específico de validação com emulador.
- Se uma validação em emulador for necessária, ela deve ocorrer fora do repositório e sem adicionar infraestrutura de emulador ao projeto.
- Preserve os workflows e testes permanentes existentes, salvo quando uma mudança explicitamente autorizada e justificada exigir o contrário. Esta regra impede adicionar infraestrutura de emulador; não autoriza remover infraestrutura existente sem justificativa e escopo explícito.
- Os testes Kotlin/JVM devem continuar exclusivamente manuais, conforme a configuração do workflow existente. As IAs e agentes estão autorizados a utilizar e disparar manualmente o workflow Test Kotlin JVM por meio de `workflow_dispatch`, quando isso for necessário e estiver relacionado à tarefa ou à validação solicitada. Também podem acompanhar a execução, consultar logs/relatórios e analisar resultados. Essa autorização não permite disparos automáticos: não adicionar `push`, `pull_request`, `schedule`, gatilhos por alteração de arquivos, chamadas automáticas por outros workflows ou qualquer mecanismo equivalente. Não converter o workflow para execução automática; somente uma ação manual pode iniciá-lo.

## Áreas críticas e contratos que devem sobreviver à migração

Trate como áreas de alto risco:
- seleção de pasta, SAF e persistência de permissões;
- MediaStore, acesso ao armazenamento e operações sobre documentos;
- scanner, ScanCoordinator e descoberta/importação de vídeos;
- SQLite, LibraryStore, migrações e persistência;
- biblioteca, detalhes, busca, Minha Lista, organização e navegação;
- reprodução local com Media3/ExoPlayer e NativePlayerActivity;
- Next/Previous, autoplay e transições entre episódios;
- histórico e persistência do progresso de reprodução;
- NativeMailbox e contratos transitórios ainda usados dentro do código Kotlin;
- thumbnails, artwork, capas e cache;
- metadados, AniList, sinopses, tradução e cache;
- lifecycle, Back, PiP e barras do sistema;
- concorrência, coroutines, asyncio no código legado, callbacks assíncronos e prevenção de eventos duplicados ou obsoletos;
- configuração de build, empacotamento e geração dos artefatos de release.

Durante a migração, mantenha os contratos temporários compatíveis até que todos os consumidores tenham sido migrados. Remova uma ponte, protocolo ou módulo legado somente depois de provar que não há consumidores restantes e que a implementação Kotlin substituta preserva o comportamento.

## Execução segura

- Entenda o código existente, o estado atual do repositório e o escopo da tarefa antes de editar.
- Quando a tarefa pedir implementação, não pare em análise ou recomendações: implemente as mudanças autorizadas no projeto existente.
- Reutilize componentes e serviços que já cumprem o requisito; ajuste-os quando necessário em vez de duplicá-los.
- Altere somente o necessário para cumprir a tarefa e manter a direção arquitetural final.
- Não invente APIs, resultados de testes, commits, artefatos ou comportamento de runtime.
- Diferencie claramente análise estática, testes realmente executados, compilação/build executados e validação em dispositivo.
- Se uma validação não puder ser executada, informe isso sem afirmar que passou.
- Não avance para um prompt ou etapa seguinte sem autorização do usuário.
- Não encerre o trabalho solicitado enquanto houver critérios obrigatórios pendentes; informe bloqueios concretos e evidências verificáveis.

## Validação e conclusão de cada etapa

Após alterações:
1. Revise o diff completo e confira todos os arquivos modificados.
2. Execute os testes não proibidos e relevantes para a alteração.
3. Execute compilação/build quando aplicável.
4. Verifique regressões nos fluxos diretamente afetados.
5. Procure alterações acidentais fora do escopo.
6. Remova código temporário, debug e artefatos de teste antes de finalizar.
7. Verifique os resultados do GitHub Actions para o SHA exato do commit final e forneça os links dos workflows relevantes.
8. Diferencie sucesso de testes locais, sucesso do build Android, geração real de APK/ZIP e validação em dispositivo; um não prova automaticamente os outros.

A etapa só pode ser declarada concluída quando seus critérios de aceitação tiverem sido atendidos e o workflow oficial aplicável estiver concluído com sucesso no SHA final. Se o workflow falhar ou não tiver sido executado, declare a etapa como não concluída e informe o bloqueio. A IA está autorizada a disparar manualmente o workflow Kotlin/JVM quando a validação for pertinente à tarefa, mantendo-o exclusivamente manual e respeitando `workflow_dispatch`.

## Regra de manutenção do runtime Kotlin nativo

- Não reintroduza código Python, gerenciadores de pacotes Python, Flet ou Flutter no repositório ativo.
- Antes de remover ou alterar qualquer comportamento, identifique os consumidores, os contratos de dados, as operações de armazenamento e os efeitos colaterais; preserve a equivalência funcional no fluxo Kotlin.
- Não apague dados, backups, campos do schema v29, testes ou proteções para simplificar a implementação.
- Preserve os identificadores do app, permissões, configuração de release, NativePlayerActivity/Media3, SAF, MediaStore, SQLite, AniList, artwork, cache, progresso e ciclo de vida.
- Os registros históricos em `docs/` não constituem dependências de execução. Quando estiverem materialmente desatualizados, identifique-os como históricos ou atualize-os em vez de ressuscitar código legado.
- Adicione dependências de execução somente pelo Gradle/Kotlin e com justificativa. Prefira a biblioteca padrão/AndroidX já utilizada quando suficiente.
- Se a substituição nativa não preservar o comportamento ou os dados, não remova o recurso; implemente primeiro a equivalência em Kotlin e registre o bloqueio de forma objetiva.
- Após a alteração, revise o diff, execute validações permitidas, compile quando aplicável e confira o workflow oficial do SHA final. Não alegue execução de testes/build nem validação física sem evidência real.

## Prioridade

A prioridade é: integridade e estabilidade do ReiAnix existente > preservação funcional > migração Kotlin incremental com paridade comprovada > correção precisa do problema solicitado > limpeza/refatoração.

Quando houver conflito entre simplificar o código e preservar comportamento existente, preserve o comportamento. Quando houver conflito entre manter permanentemente uma dependência Python e alcançar o objetivo final de 100% Kotlin, migre primeiro todas as responsabilidades e valide a equivalência antes de remover a dependência.
