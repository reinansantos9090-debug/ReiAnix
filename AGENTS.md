# AGENTS.md — ReiAnix

## Objetivo
Este é um projeto existente e em estabilização. Trabalhe sobre o código atual do ReiAnix. Não reinicie, recrie ou substitua o projeto por uma implementação nova.

## Regras obrigatórias de preservação
- Preserve todas as funcionalidades existentes que já funcionam.
- Não remova arquivos, classes, funções, testes, proteções, integrações ou fluxos apenas porque parecem antigos, redundantes ou não utilizados.
- Antes de remover ou alterar algo, verifique referências, call sites, dependências, fluxo Python/Flet ↔ Kotlin/Android, testes e comportamento em runtime.
- Se houver dúvida sobre a necessidade de um código existente, NÃO remova.
- Não faça refatorações amplas ou mudanças arquiteturais fora do escopo da tarefa.
- Prefira mudanças mínimas, locais, reversíveis e compatíveis com a arquitetura existente.
- Não transforme uma correção de bug em uma nova funcionalidade.
- Não remova testes existentes para fazer a suíte passar.
- Não desative validações, tratamento de erros, concorrência, lifecycle, cache, armazenamento, player ou proteções existentes sem evidência técnica de que são incorretos e sem preservar o comportamento necessário.


## Proibição absoluta de testes/emuladores adicionados pelo agente
- É PROIBIDO adicionar ao repositório qualquer workflow, script, job, etapa de CI ou infraestrutura que crie, inicialize ou execute um emulator Android especificamente para validar uma tarefa, stage ou alteração.
- NÃO adicionar testes de emulator ao repositório em hipótese alguma.
- NÃO criar workflows temporários ou permanentes de validação que usem emulator Android.
- NÃO adicionar etapas como `Run ... instrumentation test on Android ...`, `android-emulator-runner`, matrizes de APIs/emuladores, Android TV emulator ou qualquer mecanismo equivalente.
- NÃO adicionar upload de artifacts específico de uma validação com emulator.
- Se uma validação em emulator for necessária, ela deve ser tratada fora do repositório e sem adicionar infraestrutura de emulator ao projeto.
- Os workflows e testes permanentes existentes devem ser preservados conforme já definidos; esta regra impede que o agente adicione nova infraestrutura de emulator, não autoriza remover infraestrutura existente sem justificativa e sem escopo explícito.

## Fluxos críticos
Trate como áreas de alto risco:
- seleção e persistência de pasta/permissão;
- SAF, MediaStore e acesso ao armazenamento;
- ScanCoordinator e descoberta/importação de vídeos;
- SQLite, migrações e persistência;
- biblioteca, detalhes e navegação;
- reprodução local com Media3/Kotlin;
- Next/Previous e autoplay;
- progresso de reprodução;
- comunicação Python ↔ Kotlin/NativeMailbox;
- thumbnails, artwork e metadata;
- AniList e tradução/cache;
- lifecycle, Back, PiP e barras do sistema;
- concorrência, asyncio, callbacks assíncronos e prevenção de eventos duplicados/stale.

## Execução segura
- Primeiro entenda o código existente e o contexto da alteração.
- Altere somente o necessário para cumprir a tarefa.
- Mantenha compatibilidade com os fluxos já existentes.
- Não invente APIs, resultados de testes ou comportamento de runtime.
- Diferencie claramente análise estática, testes realmente executados, build executado e validação em dispositivo/emulador.
- Se uma validação não puder ser executada, informe isso em vez de afirmar que passou.

## Validação
Após alterações:
1. Revise o diff completo.
2. Confira todos os arquivos modificados.
3. Execute os testes relevantes.
4. Execute build/compilação quando aplicável.
5. Verifique regressões nos fluxos diretamente afetados.
6. Procure alterações acidentais fora do escopo.
7. Remova código temporário, debug e artefatos de teste antes de finalizar.

## Regra de remoção
Se a solução parecer exigir remover uma funcionalidade existente, pare e procure uma solução compatível primeiro. Não remova a funcionalidade sem evidência clara e sem verificar todos os consumidores.

## Git
- Não reescreva histórico.
- Não faça reset/checkout destrutivo.
- Não force push.
- Não altere commits anteriores para esconder mudanças.
- Preserve o estado atual do repositório e produza mudanças rastreáveis.

## Prioridade
A prioridade é: estabilidade e preservação do ReiAnix existente > correção precisa do problema solicitado > limpeza/refatoração.

Quando houver conflito entre "simplificar o código" e "preservar comportamento existente", preserve o comportamento existente.
