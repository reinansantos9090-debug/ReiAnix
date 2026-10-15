# ReiAnix — Estado da migração nativa de armazenamento

**Estado desta revisão: PARCIAL.** O fluxo SAF de descoberta/indexação já é nativo, mas a coordenação de solicitações ainda possui dependências Python ativas. Não declarar o Prompt 04/29 concluído enquanto essa dependência e a validação do SHA final permanecerem abertas.

## A. Arquitetura e responsabilidades

- **SAF:** a seleção de diretórios ocorre pela API Android em `MainActivity`. `SafScanner` usa URIs de documentos, valida a autorização contra os grants mantidos pelo `ContentResolver`, percorre os documentos e preserva os metadados disponíveis. As URIs não são convertidas arbitrariamente em caminhos físicos.
- **Permissões persistentes:** `StorageAuthorization`, `SafScanner` e `MainActivity` tratam grants, revalidação e estado recuperável. A URI salva em configuração não é considerada prova suficiente de autorização.
- **MediaStore:** `MediaStoreScanner` consulta coleções de vídeo com permissões compatíveis com a versão Android e publica progresso/diagnósticos. Conforme o contrato existente em `main.py`, MediaStore é uma fonte de diagnóstico, não uma fonte global de importação para o catálogo.
- **BroadStorage:** `BroadStorageScanner` só percorre raízes autorizadas pelo Android e publica estado/diagnósticos. O contrato existente também o exclui como fonte global de importação para o catálogo.
- **Armazenamento físico auxiliar:** `NativeIndex` mantém snapshots e gerações de descoberta. Não é o catálogo SQLite e não substitui o banco canônico.
- **Catálogo canônico:** `ReiAnixSqliteLibraryRepository.indexDiscoveredMedia` é o caminho nativo de indexação/reconciliação das descobertas SAF. O banco permanece `filesDir/data/library.sqlite3`, schema v29, sem Room, segundo banco ou alteração de schema.

## B. Fluxo de descoberta

1. `MainActivity` recebe o resultado do seletor SAF e solicita a persistência do grant concedido.
2. A URI e as permissões efetivamente mantidas são revalidadas antes da leitura.
3. `NativeScanController` registra e acompanha a operação; `NativeScanRunner` executa `SafScanner`, `MediaStoreScanner` ou `BroadStorageScanner` fora da thread principal.
4. SAF publica lotes nativos e o `NativeScanRunner` aplica o snapshot verificado ao repositório SQLite canônico. A reconciliação destrutiva só é permitida por um snapshot completo, autorizado e não ambíguo.
5. Snapshots SAF incompletos, cancelados ou com autorização/resultado de transação incertos não autorizam marcar registros como ausentes. A recuperação legada só pode receber um snapshot positivo quando a transação nativa teve rollback confirmado e a transferência foi registrada de forma durável.
6. MediaStore e BroadStorage continuam diagnósticos e não escrevem no catálogo. Seus eventos de lote são marcados como propriedade Kotlin e ignorados pelo writer Python, evitando que a mesma descoberta seja gravada por dois runtimes.
7. `NativeMailbox` ainda transporta progresso, estados terminais, diagnósticos e eventos necessários à compatibilidade híbrida; isso não significa que o fluxo de coordenação tenha sido migrado por completo.

## C. Identidade, URIs e integridade

- As URIs SAF/MediaStore são preservadas como URIs. Caminhos físicos só são usados quando a origem realmente fornece um arquivo acessível.
- A identidade persistida e as observações de mídia são usadas para reconhecer uma localização já conhecida; nome de arquivo sozinho não é chave global.
- Atualizações do scanner não devem apagar progresso, histórico, associações AniList, campos pessoais ou metadados manuais.
- Falha de consulta, snapshot inválido ou permissão revogada não é convertida em biblioteca vazia.
- Uma varredura vazia só pode reconciliar quando o scanner publica um snapshot vazio explicitamente completo e verificável; ausência de snapshot não equivale a uma pasta vazia.

## D. Integração Kotlin/Python — limites reais

Implementado no caminho nativo existente:
- Seleção SAF e validação/persistência dos grants.
- Travessia SAF e publicação de snapshots.
- Indexação/reconciliação SAF no SQLite canônico.
- Descoberta MediaStore e BroadStorage nativas para os cenários diagnósticos já existentes.
- Propagação de cancelamentos e tratamento explícito de `CancellationException`.
- Ownership dos lotes nativos: `NativeScanPublisher` marca os lotes como propriedade Kotlin, e `main.py` não executa uma segunda ingestão Python desses lotes.
- Operações SAF sobre documentos e interfaces nativas de armazenamento existentes.

Ainda híbrido e **não considerado migrado**:
- `core/scan_coordinator.py` continua sendo usado para arbitrar/enfileirar solicitações originadas pelo runtime Compose/Flet e acompanhar eventos terminais.
- `core/storage_access.py`, `core/android_bridge.py` e `main.py` continuam presentes e têm consumidores ativos para compatibilidade, onboarding/estado e transporte de comandos/eventos.
- Não há evidência suficiente nesta revisão para declarar que todas as solicitações, cancelamentos e recuperação de estado de armazenamento são coordenados exclusivamente por Kotlin de ponta a ponta.
- `NativeMailbox` não pode ser removido globalmente enquanto outros subsistemas dependerem dele.

Não remova os módulos Python remanescentes sem primeiro migrar seus consumidores e comprovar a paridade. A existência das classes Kotlin, por si só, não comprova que o runtime deixou de depender deles.

## E. Validação e limitações

- Os testes Python de contrato do repositório foram atualizados para proteger o ownership de lote nativo e impedir que MediaStore/BroadStorage se tornem fontes de importação acidental.
- O workflow Kotlin/JVM continua manual; não deve ser disparado nesta tarefa. Nenhum teste instrumentado/emulador foi adicionado.
- Não se afirma validação em dispositivo real.
- O workflow #5533 corresponde ao SHA histórico `c9271b6c2b1d694bf122eab2fe87435c5a98407a`; ele não certifica esta revisão. A aceitação depende dos workflows obrigatórios associados ao SHA final exato e da verificação dos artefatos quando publicados.
- O status só poderá mudar para concluído após build/testes obrigatórios verdes no commit final e após resolver a coordenação híbrida que permanece ativa.

## F. Pendências técnicas

1. Migrar a arbitragem/serialização de solicitações do `ScanCoordinator` Python para o caminho nativo sem criar um segundo coordenador concorrente.
2. Garantir que os comandos nativos de refresh/cancelamento sejam suficientes para os consumidores Compose e que os fluxos legados não iniciem a mesma operação em paralelo.
3. Validar no workflow oficial do SHA final o build Android, as regressões Python e os artefatos APK/ZIP.
4. Atualizar o roadmap apenas com o estado comprovado; não declarar migração total de Python nem de armazenamento enquanto consumidores ativos ainda dependerem da coordenação Python.
