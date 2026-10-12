# Prompt 14.1 — Finalização do gerenciamento nativo SAF

## Estado real

A implementação acrescenta um navegador de arquivos Compose acessível pela tela **Organizar**, endurece a autorização SAF e continua usando a persistência canônica existente. Esta etapa **não deve ser declarada integralmente concluída** até que o workflow oficial do APK termine com sucesso no HEAD final e que a reconciliação de identidade do catálogo esteja comprovada.

## Implementação

- `SafFileOperations` lista documentos por consulta SAF explícita via `ContentResolver` e `DocumentsContract.buildChildDocumentsUriUsingTree`, distinguindo pasta inválida, ausência de permissão, metadados mínimos indisponíveis e falha do provedor. Tamanho, data e MIME são opcionais quando não fornecidos.
- A autorização exige concessão de árvore persistida com leitura, escrita quando exigida, autoridade compatível e confirmação da relação do documento com a árvore por `SafScanner.isAuthorizedDocumentForTree`. Uma concessão de leitura não autoriza mutações.
- A raiz da árvore é distinguida de uma URI de documento com escopo de árvore. O código não converte URIs em caminhos físicos nem interpreta IDs de documento como caminhos.
- O seletor SAF solicita leitura, escrita, persistência e prefixo. `SafScanner.persistPermission` persiste a escrita somente se o seletor/provedor a concedeu de fato.
- A criação usa `DocumentsContract.createDocument` e confirma por nova consulta. A renomeação usa `DocumentsContract.renameDocument` e valida a URI retornada contra o item encontrado no pai. A movimentação usa `DocumentsContract.moveDocument`, confirma o item no destino e não simula suporte por cópia/exclusão. A exclusão exige escrita e uma consulta posterior; resultados ambíguos retornam falha recuperável.
- Operações na mesma árvore são serializadas por lock derivado da concessão SAF. Movimentações que envolvem duas árvores adquirem locks em ordem estável. O cancelamento de corrotinas é propagado.
- A tela `ReiAnixSafFileManagerRoute` é aberta a partir de Organizar. Lista pastas/arquivos e metadados disponíveis, navega entre pastas, cria pastas, renomeia, permite escolher o destino navegando, e exige confirmação explícita para excluir. A interface mostra erros reais e avisa que renomeação/movimentação pode alterar a identidade do catálogo.
- Após mutação confirmada, a interface solicita a atualização existente do catálogo com `viewModel.refresh()`. Isso é uma solicitação de atualização, não uma prova de sincronização nem de preservação de progresso.

## Persistência canônica e limite conhecido

O catálogo continua tendo como fonte de verdade o `LibraryStore`/`LibraryService` Python e os mecanismos de reconciliação existentes. Não foi criada outra base de dados nem um escritor SQLite concorrente.

O contrato nativo de comando disponível não oferece uma operação explícita que associe atomicamente a URI antiga à nova após renomear/mover. Para provedores genéricos, a identidade pode depender da URI/ID do documento; em árvores de armazenamento compartilhado, pode depender do caminho relativo. Assim, esta etapa não pode garantir que progresso, histórico, metadados, capa e associação AniList sejam preservados após qualquer renomeação/movimentação. A UI informa essa limitação e solicita atualização canônica, mas **a reconciliação da identidade permanece pendente de validação/integracão específica**. Uma exclusão só é apresentada como confirmada quando a consulta posterior indica ausência; estados ambíguos exigem reconciliação.

Movimentações entre provedores/autoridades diferentes são recusadas explicitamente. Não é implementada cópia seguida de exclusão.

## Testes

- `SafFileOperationsTest` contém sete testes JVM/Robolectric para URIs de sistema de arquivos recusadas, nomes inválidos, ausência de concessão, política de leitura versus escrita, escopo da árvore e cancelamento. Os cenários de concessão são verificados por política pura; não simulam um provedor SAF real.
- `tests/test_saf_professional.py` e `tests/test_build_tools.py` foram atualizados para validar os contratos de autorização, a integração da tela, os avisos sobre identidade e a concessão opcional de escrita.
- Não foram adicionados testes instrumentados nem testes de emulador.

## Validação oficial

O workflow [Build ReiAnix Android APK](https://github.com/reinansantos9090-debug/ReiAnix/actions/workflows/build_apk.yml) é disparado automaticamente no push para `main`. Inclui a suíte Python, preparação do host Flet/Android, compilação Kotlin integrada, verificação do APK e publicação de artefatos quando o job passa.

Não executei manualmente o workflow nem a suíte Kotlin/JVM. A conclusão exige verificar o resultado do workflow que corresponda ao SHA final exato. A validação física em dispositivo Android também permanece pendente até ser executada.
