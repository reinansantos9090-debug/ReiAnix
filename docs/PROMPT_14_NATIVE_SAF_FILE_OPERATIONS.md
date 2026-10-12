# Prompt 14.2 — Correção do workflow e reconciliação canônica SAF

## Estado real

A implementação corrige a sintaxe dos testes Python, acrescenta o fluxo de reconciliação de URI SAF por meio da NativeMailbox e do `LibraryService`/`LibraryStore` canônicos, e mantém o navegador Compose em Organizar. Esta etapa **não deve ser declarada integralmente concluída** até que o workflow oficial do APK termine com sucesso no SHA final e os resultados da reconciliação sejam validados pelo CI.

## Implementação

- `SafFileOperations` lista documentos por consulta SAF explícita via `ContentResolver` e `DocumentsContract.buildChildDocumentsUriUsingTree`, distinguindo pasta inválida, ausência de permissão, metadados mínimos indisponíveis e falha do provedor. Tamanho, data e MIME são opcionais quando não fornecidos.
- A autorização exige concessão de árvore persistida com leitura, escrita quando exigida, autoridade compatível e confirmação da relação do documento com a árvore por `SafScanner.isAuthorizedDocumentForTree`. Uma concessão de leitura não autoriza mutações.
- A raiz da árvore é distinguida de uma URI de documento com escopo de árvore. O código não converte URIs em caminhos físicos nem interpreta IDs de documento como caminhos.
- O seletor SAF solicita leitura, escrita, persistência e prefixo. `SafScanner.persistPermission` persiste a escrita somente se o seletor/provedor a concedeu de fato.
- A criação usa `DocumentsContract.createDocument` e confirma por nova consulta. A renomeação usa `DocumentsContract.renameDocument` e valida a URI retornada contra o item encontrado no pai. A movimentação usa `DocumentsContract.moveDocument`, confirma o item no destino e não simula suporte por cópia/exclusão. A exclusão verifica o estado posterior mesmo quando o provedor retorna `false`. Uma exceção explícita de recurso não suportado no próprio método de mutação retorna `UNSUPPORTED`; depois de uma mutação tentada, falhas posteriores de autorização/consulta/validação retornam `PARTIAL` recuperável, evitando uma falha definitiva que incentivaria repetição destrutiva.
- Operações na mesma árvore são serializadas por lock derivado da concessão SAF. Movimentações que envolvem duas árvores adquirem locks em ordem estável. O cancelamento de corrotinas é propagado.
- A tela `ReiAnixSafFileManagerRoute` é aberta a partir de Organizar. Lista pastas/arquivos e metadados disponíveis, navega entre pastas, cria pastas, renomeia, permite escolher o destino navegando, e exige confirmação explícita para excluir. A interface mostra erros reais e avisa que renomeação/movimentação pode alterar a identidade do catálogo.
- Após mutação confirmada, a interface solicita a atualização existente do catálogo com `viewModel.refresh()`. Isso é uma solicitação de atualização, não uma prova de sincronização nem de preservação de progresso.

## Reconciliação canônica SAF

Renomeações e movimentações confirmadas pelo provedor enfileiram o evento durável `saf_document_identity_change` na NativeMailbox existente, incluindo a árvore SAF autorizada. O processo Python encaminha o evento a `LibraryService.reconcile_saf_document_identity_change`, que serializa a operação com os scans, delega a `LibraryStore` e atualiza a URI/nome da linha de episódio existente e as observações SAF correspondentes em uma única transação. Após o commit, o host agenda em segundo plano um novo scan da árvore, sem bloquear o consumo/ack da mailbox. Não há escrita SQLite no Kotlin nem banco paralelo.

A reconciliação exige URIs `content://` e uma linha canônica antiga de origem SAF. Se o provedor mantiver a mesma URI após renomear/mover, atualiza o nome sem trocar a identidade. Se a URI mudar, exige o mesmo authority. Ela não cria uma linha se a origem não for encontrada, não sobrescreve outra linha que já use a URI de destino e trata a repetição como idempotente quando a URI nova já está registrada. Como a linha de episódio não é recriada, o ID do episódio/anime, progresso, estado assistido, horário de reprodução, identidade de mídia, metadados, artwork e associações externas permanecem ligados ao registro original. As observações SAF são reendereçadas na mesma transação. Um evento que não prove a correspondência não remove registros; falhas excepcionais são reprocessáveis pela fila.

O estado mostrado pela UI é **reconciliação enfileirada**, não confirmação de que o catálogo já processou o evento. Se a publicação na mailbox falhar depois da mutação física, a UI apresenta um resultado recuperável e orienta a verificar a biblioteca. Exclusão e criação não são usadas para reescrever a identidade de um episódio. A movimentação entre authorities continua recusada.

## Persistência canônica e limite conhecido

O catálogo continua tendo como fonte de verdade o `LibraryStore`/`LibraryService` Python e os mecanismos de reconciliação existentes. Não foi criada outra base de dados nem um escritor SQLite concorrente.

A reconciliação por URI agora está integrada ao fluxo canônico e coberta por testes de unidade Python adicionados nesta etapa. Ainda não é possível garantir o comportamento de todos os provedores SAF reais: alguns podem retornar URIs/document IDs instáveis, e o evento pode ser interrompido após a mutação física ou antes de a mailbox ser processada. Nesses casos, o registro antigo é preservado quando a correspondência não pode ser provada, e a UI não declara a reconciliação concluída. Exclusão e falhas ambíguas não apagam o estado canônico automaticamente.

Movimentações entre provedores/autoridades diferentes são recusadas explicitamente. Não é implementada cópia seguida de exclusão.

## Testes

- `SafFileOperationsTest` contém sete testes JVM/Robolectric para URIs de sistema de arquivos recusadas, nomes inválidos, ausência de concessão, política de leitura versus escrita, escopo da árvore e cancelamento. Os cenários de concessão são verificados por política pura; não simulam um provedor SAF real.
- `tests/test_saf_professional.py`: corrigida a sequência literal `\\n` antes de uma declaração de método, substituindo-a por quebra de linha real; adicionado contrato para o evento de reconciliação canônica.
- `tests/test_build_tools.py`: corrigido o separador do payload DEX sintético de `b"\\\\n"` para `b"\\n"`, garantindo bytes de newline reais no APK de teste.
- `tests/test_reconciliation.py`: adicionados testes de reconciliação idempotente com preservação de ID, progresso, estado assistido, timestamp e identidade de mídia, além de conflito de URI que deve preservar ambos os registros.
- `tests/test_saf_professional.py` verifica que mutações ambíguas ficam recuperáveis, que o teste de descendência não mascara exceções de `DocumentsContract.isChildDocument` e que a identidade usa o fluxo de mailbox canônico.
- Não foram adicionados testes instrumentados nem testes de emulador.

## Validação oficial

A execução oficial do APK para o SHA de implementação `b514dcacaf7a278fe3483b8a122152e80856053b` terminou com `success`:

- [Build ReiAnix Android APK — run 38315555627](https://github.com/reinansantos9090-debug/ReiAnix/actions/runs/38315555627): sucesso no job obrigatório. `Run Python regression suite`, `Validate Python application`, `Build APK`, `Explicitly verify release Kotlin compilation`, verificações do APK e `Final Git integrity check` concluídos com sucesso.
- [Release Evidence Certification — run 38315555728](https://github.com/reinansantos9090-debug/ReiAnix/actions/runs/38315555728): sucesso, incluindo a matriz de 201 verificações; o relatório de certificação foi publicado como artefato.
- SHA-256 do APK gerado: `c624ccd64987ec48e98ac0f99ee6809e00c334a659be65033ee28b58fac43ce7`.
- Artefatos Actions confirmados: `ReiAnix-apk-instalacao-direta-5289` (32.335.761 bytes) e `ReiAnix-ZIP-extraia-primeiro-5289` (32.290.129 bytes). O workflow de push usa retenção curta de artefatos; ambos estavam disponíveis ao verificar a execução.
- O certificado de assinatura observado no APK foi `CN=Android Debug`; este resultado não deve ser descrito como uma assinatura de distribuição privada de produção.
- O passo `Publish APK and ZIP to rolling GitHub Release` foi `skipped` porque o workflow só o executa em `workflow_dispatch`. Portanto, este push não atualizou os assets da Release persistente; os APK/ZIP validados estão nos artefatos da execução do Actions.

Após o commit de documentação `e069b576c62b0b64d86cd1cf153527ccfe9a0bcb`, o build do APK foi gerado, mas a segunda invocação de Gradle falhou em `:serious_python_android:downloadDartBridge_armeabi-v7a`: o arquivo JSON de ETags do plugin continha bytes NUL e não pôde ser analisado. O erro é do cache compartilhado das tarefas de download por ABI, não de código Kotlin. O workflow agora valida o JSON do cache, remove apenas arquivos de ETags malformados e executa a verificação explícita de Kotlin sem paralelismo; a tarefa `:app:compileReleaseKotlin` continua obrigatória e não foi ignorada.

Esta evidência pertence aos SHAs mencionados e não substitui a validação do HEAD que contém esta correção de workflow. Não executei manualmente a suíte Kotlin/JVM e não foram adicionados testes instrumentados ou de emulador. A validação física em dispositivo Android e em provedores SAF reais permanece pendente.
