# Migração Kotlin — permissões, armazenamento e onboarding

## Estado real de partida

A MainActivity continua sendo uma FlutterFragmentActivity porque o APK oficial ainda usa o host Android gerado pelo Flet. Esta etapa não remove esse runtime. O seletor SAF, a persistência/inspeção da concessão, as solicitações de permissão do MediaStore, a navegação para configurações de acesso amplo e a revalidação após o retorno já eram nativos.

A lacuna corrigida nesta etapa era a decisão de apresentar o onboarding como uma camada sobre Início/Biblioteca. O host Compose agora observa NativeStorageOnboardingState e, quando a configuração/recuperação é necessária, navega para a rota real de Armazenamento em vez de desenhar outra tela sobre a atual. O estado é recalculado em Kotlin usando as pastas SAF registradas no SQLite canônico e a inspeção da concessão/provedor no Android; a interface não presume que uma string de URI armazenada equivale a uma autorização válida.

## Estratégia de permissões

- **SAF (ACTION_OPEN_DOCUMENT_TREE)** continua sendo o caminho preferido para selecionar uma pasta da biblioteca. O resultado só pode ser registrado após validar a URI como árvore content://, verificar o provedor, persistir a concessão de leitura oferecida pelo sistema e confirmar o acesso persistente. A escrita só é retida quando o sistema efetivamente a concedeu e operações de escrita posteriores continuam exigindo a autorização correspondente.
- **MediaStore** é um acesso distinto. MediaStoreScanner.requiredPermissions() define as permissões runtime aplicáveis por versão; MediaStoreScanner.accessLevel() reconsulta o estado real após o callback. Acesso parcial permite descoberta dentro do escopo autorizado, mas não é tratado como autorização ampla nem como base para reconciliação destrutiva.
- **Armazenamento privado do aplicativo** não depende de MANAGE_EXTERNAL_STORAGE.
- **Acesso amplo ao armazenamento** permanece opcional e isolado ao fluxo explícito de Broad Storage, que enumera caminhos compartilhados fora do escopo de SAF/MediaStore. É por isso que MANAGE_EXTERNAL_STORAGE continua declarado: o recurso Broad Storage existente chama Environment.isExternalStorageManager() e precisa dessa autorização em Android 11/API 30 ou superior. Não é pedido indiscriminadamente, não é requisito para SAF ou MediaStore, e só se torna utilizável depois da ação consciente do usuário em Configurações e de uma nova verificação da API Android.
- READ_EXTERNAL_STORAGE permanece limitado a API 32; READ_MEDIA_VIDEO e READ_MEDIA_VISUAL_USER_SELECTED cobrem os contratos de leitura de mídia das versões correspondentes. O fluxo não presume que o Android 16 requer acesso amplo.

## Onboarding e ciclo de vida

MainActivity.refreshNativeStorageOnboardingState() executa a consulta às pastas no dispatcher IO. Para cada raiz SAF configurada, verifica a URI de árvore, a concessão persistente de leitura e a capacidade de consultar o diretório pelo scanner SAF existente.

- Sem raiz SAF configurada, o estado vira needs_folder, exceto quando o usuário já dispensou explicitamente a orientação.
- Com pelo menos uma raiz configurada e acessível, o estado vira ready; uma raiz inacessível produz um estado de recuperação em vez de apagar o registro.
- Durante a escolha do diretório, o estado folder_picker_open impede que a revalidação do ciclo de vida sobrescreva a ação em andamento.
- Cancelamento libera a guarda do seletor e mantém a opção de tentar novamente. Falha de validação/persistência apresenta erro acionável.
- Ao retornar de Configurações, o app consulta de novo BroadStorageScanner.hasAccess() e MediaStoreScanner.accessLevel(); o código do resultado de atividade ou o simples retorno nunca é tratado como concessão.
- A seleção bem-sucedida só muda o onboarding para ready depois que a concessão SAF foi verificada e a pasta foi gravada no repositório SQLite canônico.
- A dispensa do cartão é uma preferência de interface separada; não marca o armazenamento como pronto nem substitui a URI/permissão.
- A decisão de abrir o gerenciador deixou de usar o estado de onboarding projetado pelo Python. O host navega para uma única rota de Armazenamento, sem sobrepor Início/Biblioteca. Ações e eventos de mailbox continuam onde consumidores legados precisam deles, mas não são a fonte de verdade da autorização.

## Manifesto e arquitetura preservada

O Manifest declara somente os contratos usados pelos fluxos atuais: acesso à rede, leitura compatível de mídia, acesso amplo opcional para Broad Storage e recursos opcionais. A declaração ampla não autoriza nada sozinha: a verificação runtime continua sendo Environment.isExternalStorageManager(). Não foi adicionada permissão extra para SAF, que usa concessão por URI.

O código usa o repositório existente ReiAnixSqliteLibraryRepository.listFolders() para descobrir as raízes configuradas; não cria outro banco, outro esquema, nem outra fonte de verdade. SafScanner, MediaStoreScanner, BroadStorageScanner, NativeScanController, NativeScanRunner e o mecanismo de publicação de resultados não são reimplementados neste prompt. A Activity mantém as solicitações e a coordenação de execução atuais e só inicia a varredura SAF selecionada depois da validação e persistência.

## Compatibilidade Python/Flet restante

core/storage_access.py, core/android_bridge.py, NativeMailbox e os handlers Python permanecem para telas Flet/integrações legadas ainda ativas e para projeções compatíveis. Nesta etapa, a decisão do onboarding da sobreposição Compose e suas ações de seleção/dispensa são nativas. Isso não equivale a remover globalmente Python/Flet/Flutter nem a migrar todo o ScanCoordinator.

## Tratamento de falhas e preservação de dados

Uma URI configurada sem concessão atual, uma URI malformada ou um provedor indisponível gera estado de recuperação. O código não apaga registros SQLite, histórico de reprodução, progresso, favoritos, metadados nem capas por causa de uma falha de acesso. Ao trocar pasta, a antiga configuração só é substituída segundo a transação validada já existente após a seleção do usuário. Um cancelamento não inicia uma varredura e não substitui as fontes válidas.

## Validação e limitações

A suíte Python de regressão recebe contratos de arquitetura para o estado nativo e sua integração no host e no hook de empacotamento. A compilação APK e os workflows obrigatórios da branch/PR são a validação final da mudança. Na etapa separada de verificação de código Kotlin, o workflow executa `:app:compileReleaseKotlin` sem as tarefas por ABI de download, extração e divisão de payload Python/Dart (`downloadDartBridge_*`, `downloadDistArchive_*`, `untarFile_*` e `splitStdlib_*`). Esses artefatos não são entradas do compilador Kotlin e algumas dessas tarefas exigem os payloads que a verificação isolada deliberadamente não baixa. O passo anterior de build APK continua responsável por montar e validar o runtime da ABI escolhida. Isso evita que falhas de cache/download do plugin Python sejam confundidas com falha de compilação Kotlin, sem pular a compilação Kotlin nem enfraquecer a validação do APK. Testes Kotlin/JVM continuam manuais e não são executados por esta etapa; nenhum teste de emulador/instrumentado nem workflow desse tipo foi adicionado.

O acesso real por provedor SAF/MediaStore, inclusive estados de revogação de permissões definidos pelo SO, depende do contrato e do estado de runtime do dispositivo. O workflow de CI comprova compilação e verificações automatizadas, não substitui testes físicos no aparelho. O runtime híbrido e os consumidores Python mencionados acima continuam sendo pendências separadas na migração geral.


## Seleção manual da pasta no primeiro início

Quando o bootstrap liberar o uso normal e a tabela canônica `folders` não contiver uma raiz SAF, o ReiAnix abre a rota única de Armazenamento, mas **não abre o seletor Android automaticamente**. O seletor `ACTION_OPEN_DOCUMENT_TREE` só é iniciado depois de uma ação explícita em **Selecionar pasta** nessa tela.

- **Voltar** ao sair da tela de Armazenamento dispensa somente a orientação de onboarding e permite navegar sem configurar uma pasta. Essa decisão de apresentação é persistida e não equivale a uma concessão SAF nem a um estado `ready`.
- A rota Armazenamento só é aberta automaticamente quando a validação termina e identifica uma ação necessária: nenhuma pasta configurada ou erro de recuperação ainda não dispensado. Não há painel de onboarding desenhado sobre outra tela.
- A dispensa permanece válida entre aberturas enquanto não houver uma nova seleção explícita. Uma seleção manual reabre o fluxo; a autorização só é marcada como pronta depois da validação da concessão e do registro no SQLite canônico.
- Cancelar o seletor Android retorna à escolha de onboarding, mas nunca relança o seletor automaticamente. O usuário pode tentar novamente pelo botão ou escolher uma pasta mais tarde na tela de armazenamento.
- A mera presença de permissão MediaStore/Broad Storage não equivale a uma raiz SAF. O app não dispara scan geral sem uma fonte autorizada; scan de uma nova raiz começa somente após seleção, validação e persistência bem-sucedidas.
