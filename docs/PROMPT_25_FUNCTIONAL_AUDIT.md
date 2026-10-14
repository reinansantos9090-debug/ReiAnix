# Prompt 25/31 — Auditoria funcional do ReiAnix

Data da auditoria: 2026-10-14 (UTC).
Repositório: `reinansantos9090-debug/ReiAnix`.
Branch auditada: `main`.
Commit de referência: `8da0a37e1a3845d7c608ef455629049cdf8d7959`.

## Escopo e evidências

A auditoria foi feita sobre a árvore GitHub da branch e os arquivos Kotlin, Python, manifesto, Gradle, testes e workflows existentes. O clone local não pôde ser obtido neste ambiente por falha de resolução DNS para `github.com`; portanto, não foram executados comandos locais de teste/build nesta sessão.

O último workflow de build APK observado para o commit de referência terminou com sucesso: [Build ReiAnix Android APK](https://github.com/reinansantos9090-debug/ReiAnix/actions/runs/38493090300). O workflow de certificação de evidências também terminou com sucesso: [Release Evidence Certification](https://github.com/reinansantos9090-debug/ReiAnix/actions/runs/38493090275). O job de build informa explicitamente que instalou Python, Flet e Flutter, executou a suíte Python, construiu o APK e verificou as classes Kotlin no DEX. Isso comprova o pacote híbrido validado por CI, não independência do runtime Python/Flet/Flutter.

## Matriz de cobertura

| Área | Estado observado | Evidência / lacuna | Decisão nesta auditoria |
|---|---|---|---|
| Biblioteca e identidade de mídia | Parcialmente funcional | Existem modelos Kotlin, parser de nomes, identidade nativa, índice/staging e consultas SQLite. A ingestão/reconciliação canônica ainda passa por `LibraryService` / `LibraryStore` Python em fluxos Android. | Preservar a única autoridade SQLite atual; não criar um segundo escritor nem trocar a identidade de mídia sem testes de paridade. |
| Pesquisa, filtros e ordenação | Parcialmente funcional | Existem filtros Compose, motor de pesquisa Kotlin e consultas parametrizadas SQLite; há também regras legadas Python. A paridade completa entre os dois contratos não está comprovada apenas pela presença das classes. | Não remover a pesquisa Python nem substituir todas as consultas sem uma verificação de paridade completa. |
| Detalhes, listas pessoais e acompanhamento | Parcialmente funcional | Existem telas Compose, serviços SQLite para listas pessoais e integração AniList nativa; outras projeções e mutações ainda utilizam o adapter/mailbox. | Manter os serviços existentes e evitar fontes de verdade concorrentes. |
| Configurações | Parcialmente funcional | Compose/StateFlow e contrato de preferências nativo existem, mas `ReiAnixSettingsRepository` envia comandos pela `NativeMailbox` ao `SettingsStore` Python. | Não trocar por DataStore nem habilitar gravação concorrente em SQLite; falta cutover de domínio validado. |
| SAF, MediaStore e organização | Parcialmente funcional | Scanners nativos, grants SAF persistentes, operações por URI e estados de erro existem. A ingestão/reconciliação final do catálogo ainda cruza a bridge Python. | Preservar URIs e staging; não converter `content://` em caminho físico nem remover a coordenação existente. |
| AniList e capas | Parcialmente funcional | Cliente GraphQL, serviço de integração, associação SQLite e testes de contrato Kotlin existem. O aplicativo legado ainda contém cliente/cache Python e o empacotamento mantém runtime Flet. | Não remover o fluxo antigo até confirmar todos os consumidores e a paridade de cache/offline. |
| Player, histórico e retomada | Parcialmente funcional | Media3 e uma única `NativePlayerActivity` existem; o contrato do player encaminha eventos pela mailbox, e a persistência canônica de consumo ainda passa pelo fluxo Python. | Não criar outro player nem histórico. A migração da persistência precisa preservar conclusão, retomada e marcadores. |
| SQLite e integridade | Parcialmente funcional | Há repositórios Kotlin sobre o banco existente, validação de contrato/schema, transações e testes Robolectric; a fonte de escrita canônica ainda inclui `LibraryStore` Python. | Nenhuma alteração de esquema ou dados nesta auditoria. Não recriar nem apagar o banco. |
| Navegação e interface | Funcional nas evidências de CI disponíveis; validação física não comprovada | Navigation Compose, shell, estados de UI e testes de contrato existem. Os commits recentes corrigem o contrato Back e o workflow mais recente do APK concluiu com sucesso. | Preservar a navegação atual; não declarar validação em aparelho físico. |
| Python/Flet/Flutter | Bloqueio arquitetural conhecido | `MainActivity` deriva de `FlutterFragmentActivity`; o README e o Gradle descrevem o Android como overlay do host Flet. O workflow exige Python, Flet e Flutter. Há módulos Python usados por catálogo, configurações, scan e consumo. | Não remover runtime/bridge neste prompt sem substitutos nativos equivalentes e testados. Registrar como pendência de migração, não como funcionalidade concluída. |
| Build e workflows | Validado pelo último CI consultado | Build APK e certificação concluíram com sucesso no commit de referência. O workflow Kotlin/JVM é manual/PR; não há evidência de execução dele para este commit nos resultados consultados. | Não alterar gatilhos de workflow nem declarar testes Kotlin/JVM executados nesta sessão. |

## Pendências concretas

1. Substituir, com paridade e escrita única, a ingestão/reconciliação de `LibraryService` e `LibraryStore` por implementação Kotlin antes de remover os fluxos Python.
2. Concluir o contrato nativo de configurações e suas validações mantendo compatibilidade com os valores existentes.
3. Confirmar a persistência canônica de eventos do Media3, progresso, conclusão, retomada e marcadores sem duplicar histórico.
4. Fechar a paridade de parsing/identidade e de pesquisa/filtros entre Python e Kotlin antes de remover as implementações antigas.
5. Migrar backup/recuperação e demais serviços legados somente depois de substituir e validar suas invariantes de integridade.
6. Executar o workflow Kotlin/JVM e as verificações disponíveis após mudanças de código; validar em dispositivo os fluxos que exigem Android real.

## Alterações desta auditoria

Este commit registra a matriz de cobertura e os bloqueios com base nas evidências consultadas. Não altera código de runtime, esquema SQLite, dados do usuário, manifesto, dependências ou gatilhos de CI. Nenhuma pasta foi removida; a nova documentação fica em `docs/`.

A migração nativa independente **não está certificada** por esta auditoria: o APK aprovado no CI continua sendo construído pelo host Flet/Flutter. O Prompt 25 não deve ser declarado integralmente concluído enquanto as integrações essenciais listadas acima permanecerem dependentes do runtime Python.
