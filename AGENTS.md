# AGENTS.md — ReiAnix

## Objetivo e destino arquitetural

Este é um projeto existente em estabilização e migração incremental. Trabalhe sobre o código atual do ReiAnix; não reinicie, recrie nem substitua o projeto por uma implementação nova.

**Objetivo final obrigatório: migrar a aplicação para 100% Kotlin no Android, com interface nativa em Jetpack Compose e sem dependência de Python/Flet em runtime.** Essa meta deve ser alcançada por etapas pequenas, verificáveis e compatíveis, não por uma reescrita geral de uma só vez.

- O estado intermediário pode continuar usando Python/Flet enquanto responsabilidades ainda não tiverem sido migradas com equivalência funcional comprovada.
- Não trate Python/Flet, pontes Python ↔ Kotlin, NativeMailbox ou a UI Flet como dependências permanentes da arquitetura final.
- Antes de remover qualquer componente Python, identifique todas as responsabilidades, consumidores, contratos IPC, persistência e efeitos colaterais; migre-os para Kotlin e valide a paridade funcional primeiro.
- O destino é uma aplicação Android nativa em Kotlin/Jetpack Compose. Não crie uma segunda arquitetura paralela nem mantenha dois caminhos de execução definitivos para a mesma responsabilidade.
- A migração só está concluída quando o código e o empacotamento da aplicação não dependerem mais de Python/Flet em runtime e todas as funcionalidades exigidas tiverem sido preservadas e validadas.
- Não confunda percentuais de linguagens no GitHub com progresso funcional da migração. Registre explicitamente o que foi migrado, o que continua dependente de Python e quais critérios ainda faltam para removê-lo.

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
- Não dispare manualmente a suíte Kotlin/JVM por iniciativa própria; siga os gatilhos já configurados nos workflows existentes. Se o workflow oficial executar esses testes automaticamente por um gatilho já existente (por exemplo, `pull_request`), não tente impedir essa execução nem descreva a CI como manual. A validação manual adicional fora dos gatilhos existentes fica reservada ao momento definido pelo usuário.

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
- comunicação Python ↔ Kotlin/NativeMailbox enquanto ainda existir código legado;
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

A etapa só pode ser declarada concluída quando seus critérios de aceitação tiverem sido atendidos e o workflow oficial aplicável estiver concluído com sucesso no SHA final. Se o workflow falhar ou não tiver sido executado, declare a etapa como não concluída e informe o bloqueio. Não execute manualmente o workflow Kotlin/JVM enquanto estiver reservado para a validação final.

## Regra de remoção e migração

Se a solução parecer exigir remover uma funcionalidade existente, pare e procure primeiro uma solução compatível. A meta de 100% Kotlin não autoriza perda de comportamento.

Para cada responsabilidade migrada:
1. Identifique o comportamento e os consumidores atuais.
2. Implemente a substituição Kotlin no fluxo existente, evitando arquitetura paralela.
3. Preserve os dados, identificadores e associações existentes.
4. Adicione ou atualize testes compatíveis com as restrições deste arquivo.
5. Confirme compilação, regressões e workflow oficial.
6. Só então remova a implementação Python/Flet correspondente se nenhum consumidor restante depender dela.
7. Mantenha um registro claro das responsabilidades migradas e das dependências Python ainda restantes.

Se um contrato legado for insuficiente, estenda-o de forma mínima e compatível durante a transição; não invente uma segunda fonte de verdade. Se não for seguro migrar/remover uma responsabilidade na etapa atual, preserve-a e documente exatamente o que falta.

## Git e colaboração

- Não reescreva histórico.
- Não faça reset ou checkout destrutivo.
- Não force push.
- Não altere commits anteriores para esconder mudanças.
- Preserve o estado atual do repositório e produza mudanças rastreáveis.
- Prefira branches e pull requests para mudanças documentais ou de código, mantendo a revisão e a validação visíveis.

## Prioridade

A prioridade é: integridade e estabilidade do ReiAnix existente > preservação funcional > migração Kotlin incremental com paridade comprovada > correção precisa do problema solicitado > limpeza/refatoração.

Quando houver conflito entre simplificar o código e preservar comportamento existente, preserve o comportamento. Quando houver conflito entre manter permanentemente uma dependência Python e alcançar o objetivo final de 100% Kotlin, migre primeiro todas as responsabilidades e valide a equivalência antes de remover a dependência.
