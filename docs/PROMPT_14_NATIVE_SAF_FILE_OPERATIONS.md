# Prompt 14.1 — Finalização do gerenciamento nativo SAF

## Estado real

A implementação publicada acrescenta um navegador de arquivos Compose à tela **Organizar**, endurece a autorização SAF e mantém a persistência canônica existente. Esta etapa **não está integralmente concluída**: a integração de identidade após renomeação/movimentação/exclusão ainda precisa de contrato canônico verificável, e o workflow oficial do APK precisa terminar com sucesso no HEAD final.

## Implementação

- `SafFileOperations` aceita URIs SAF de conteúdo e valida a concessão persistida de leitura separadamente da concessão de escrita.
- A autorização exige uma concessão de árvore com leitura, escrita quando exigida, autoridade compatível e confirmação de que o documento pertence à árvore pelo `SafScanner.isAuthorizedDocumentForTree` / contrato do provedor. Uma concessão de leitura não autoriza mutações.
- A URI da raiz de árvore é tratada de forma diferente de uma URI de documento com escopo de árvore. O código não deriva caminhos físicos de URIs nem interpreta document IDs como caminhos.
- O seletor SAF solicita leitura, escrita, persistência e prefixo; `SafScanner.persistPermission` persiste a escrita somente quando o seletor/provedor realmente a concedeu.
- A listagem inclui metadados opcionais; falhas de provedor, autorização e resultados ambíguos retornam estados explícitos.
- A criação confirma o resultado por nova consulta ao pai; renomeação procura a URI resultante no pai; movimentação usa `DocumentsContract.moveDocument`, valida o destino e não simula suporte com cópia/exclusão; exclusão requer escrita e uma consulta posterior, retornando resultado parcial quando a confirmação não é confiável.
- As operações serializam chamadas pela árvore SAF autorizada, usando ordenação de locks para movimentações entre árvores e propagando cancelamento de corrotinas.
- A tela `ReiAnixSafFileManagerRoute` é acessível a partir de Organizar. Exibe itens e metadados disponíveis, navega por pastas, cria pastas, renomeia, move com escolha navegável do destino e exige confirmação explícita para excluir. Resultados parciais são apresentados como ambíguos.
- Depois de uma mutação confirmada, a interface solicita a atualização existente do catálogo via `viewModel.refresh()`; isso não é apresentado como prova de preservação do histórico.

## Persistência e limite de segurança

O catálogo continua tendo como fonte de verdade o `LibraryStore`/`LibraryService` Python e a reconciliação já existente. Não foi criado outro banco nem um escritor SQLite concorrente.

O comando atual de atualização do catálogo não oferece, no contrato nativo verificado nesta etapa, uma operação explícita que relacione atomicamente a URI antiga à URI nova após renomear/mover. Para provedores genéricos, a identidade pode depender da URI/document ID; para árvores de armazenamento compartilhado, pode depender do caminho relativo. Por isso, não é possível garantir nesta etapa que progresso, histórico, metadados, capa e associação AniList sejam preservados após qualquer renomeação/movimentação. A interface deixa essa limitação explícita e solicita a atualização canônica, mas **não se declara a reconciliação de identidade concluída**. A exclusão só informa sucesso quando a consulta posterior confirma a ausência; estados ambíguos preservam a necessidade de reconciliação.

Movimentações entre provedores/autoridades diferentes não são implementadas por cópia seguida de exclusão. São recusadas explicitamente para evitar perda de conteúdo.

## Testes

- `SafFileOperationsTest` cobre URIs de sistema de arquivos recusadas, nomes inválidos, ausência de concessão, política de leitura versus escrita, escopo da árvore, URI inacessível e propagação de cancelamento.
- `tests/test_saf_professional.py` cobre contratos de autorização/integração por inspeção de código, a presença da interface Compose e o pedido de concessão de escrita.
- `tests/test_build_tools.py` foi atualizado para o novo contrato de persistência das concessões.
- Testes com URIs sintéticas não demonstram o comportamento de provedores SAF reais. Não foram adicionados testes instrumentados ou de emulador.

## Validação de build

O workflow oficial `.github/workflows/build_apk.yml` executa no push para `main` e inclui a suíte Python, preparação do host Flet/Android, compilação Kotlin integrada e geração/verificação do APK. O workflow Kotlin/JVM permanece sem execução manual nesta etapa.

**Não declarar build, testes ou artefatos aprovados até obter o resultado do workflow referente ao HEAD final exato.** Validação física em dispositivo também permanece pendente até ser executada explicitamente.
