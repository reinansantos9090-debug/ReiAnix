# ReiAnix — Scan Coordinator

## Responsabilidade
ScanCoordinator é o ponto lógico único para decidir quando uma descoberta da biblioteca deve acontecer. Ele não acessa SQLite, não navega, não chama Flet e não implementa a descoberta física dos arquivos.

Separação mantida:
- ScanCoordinator: quando/o quê, prioridade, deduplicação, coalescing, cancelamento e estado lógico.
- MediaStoreScanner / BroadStorageScanner / SafScanner: como descobrir.
- LibraryService: como aplicar a descoberta ao domínio.
- LibraryStore: como persistir.
- NativeIndex: gerações/snapshots da descoberta nativa.
- UI: apresenta estado e decide quando atualizar a tela.

## Fontes
As origens reconhecidas são:
- STARTUP
- USER_REFRESH
- MEDIA_CHANGE
- SAF_CHANGE
- PERMISSION_CHANGE
- VOLUME_MOUNT
- VOLUME_UNMOUNT
- RECOVERY
- EXPLICIT_FULL_RESCAN

onResume() não é uma origem de scan. Ele apenas publica o estado de permissões; uma transição real de capacidade pode gerar PERMISSION_CHANGE. A descoberta de novos vídeos SAF vem do monitor de alterações, nunca da simples abertura/retomada do app.

## Estados
O estado lógico é:
- IDLE
- QUEUED
- RUNNING
- CANCELLING
- COMPLETED
- CANCELLED
- FAILED
- PARTIAL
- BLOCKED

A UI Python mantém ScanUiState como projeção desses estados.

## Prioridade
A ordem é explícita no código:
1. EXPLICIT_FULL_RESCAN
2. USER_REFRESH
3. PERMISSION_CHANGE
4. RECOVERY
5. STARTUP
6. VOLUME_MOUNT
7. SAF_CHANGE
8. MEDIA_CHANGE

VOLUME_UNMOUNT fica com prioridade alta para registrar rapidamente uma mudança de disponibilidade, mas não dispara uma varredura de um volume que acabou de ficar inacessível.

## Deduplicação
Requests equivalentes não criam um segundo scanner.

Exemplos:
USER_REFRESH + USER_REFRESH
MEDIA_CHANGE(mediastore) + MEDIA_CHANGE(mediastore)

enquanto o primeiro está ativo.

Os IDs são preservados separadamente:
- eventId: evento do NativeMailbox.
- requestId: correlação da requisição nativa.
- scanId: identificador persistido de execução/generation já usado pelo pipeline existente.

## Coalescing
Enquanto um scan está executando, requests diferentes entram numa fila lógica pequena.

O Coordinator conserva a solicitação necessária com maior prioridade e remove uma solicitação pendente que já esteja coberta por outra mais abrangente.

Isso permite:
MEDIA_CHANGE → MEDIA_CHANGE → USER_REFRESH

sem iniciar três scanners simultâneos.

O scan atual termina primeiro; depois o request pendente apropriado é despachado.

## NativeMailbox
Eventos nativos continuam usando NativeMailbox.

O novo caminho é:
Activity/Observer/permissão
→ scan_request
→ ScanCoordinator
→ AndroidBridge
→ scanner nativo
→ NativeMailbox
→ ScanCoordinator.handle_native_event()
→ LibraryService/LibraryStore
→ uma atualização lógica da UI.

O NativeMailbox continua responsável pela fila atômica, deduplicação e acknowledgement. Ele não decide sozinho iniciar scanner.

## Lifecycle
O comportamento de MainActivity.onResume() foi separado da indexação.

Retornar do player, background ou outra tela não inicia scan nem reconciliação completa.
A seleção inicial de uma pasta executa seu scan validado; a varredura manual continua
disponível; mudanças efetivas de permissão/volume continuam usando seus fluxos próprios.

O MediaStore ContentObserver produz uma solicitação MEDIA_CHANGE após o debounce
existente. Para SAF, `SafTreeChangeMonitor` observa as árvores autorizadas, filtra
notificações para conteúdo de vídeo, persiste alterações por árvore e solicita
`SAF_CHANGE` somente para a pasta que recebeu conteúdo novo. A simples retomada
do app nunca cria `BACKGROUND_RECONCILIATION`.

## Cancelamento
ScanCoordinator.cancel() muda para CANCELLING, limpa requests pendentes e chama o cancelamento nativo existente.

O scanner nativo continua tendo oportunidade de observar a flag de cancelamento e publicar um resultado CANCELLED.

Cancelamento não é equivalente a IDLE artificial e não implica reconciliação destrutiva.

## Falhas e scans parciais
FAILED, PARTIAL, UNAVAILABLE e CANCELLED não são tratados como uma varredura completa válida.

O LibraryService.finish_ingest_documents() já exige uma generation completa e sem erros para reconciliar o scope. Isso é preservado.

Assim, um scan parcial ou cancelado não significa que tudo que não apareceu foi removido.

O endurecimento definitivo de permissões, volumes e acesso parcial permanece documentado no fluxo de storage.

## Refresh da UI
O resultado de cada scanner pode conter vários lotes/volumes, mas o Coordinator só sinaliza logical_finished ao terminar a execução lógica inteira.

A camada Python usa esse ponto para executar on_catalog_changed() e refresh_settings_if_active() uma vez por execução lógica.

A camada de scan não chama page.update(), não abre tela e não altera rotas.

## MediaStore observer
MediaStoreScanner já possuía uma instância process-wide de ContentObserver, com registro único, unregisterContentObserver e debounce no host.

Nesta fase o callback deixou de iniciar scanMediaStore() diretamente.

Agora ele publica um MEDIA_CHANGE para o Coordinator.

## Startup
A primeira seleção de uma pasta SAF executa a varredura inicial validada; a ação manual continua disponível em qualquer momento.

Depois que uma pasta está configurada, o startup apenas registra os observadores e reconcilia o inventário leve de metadados de vídeos com o último snapshot persistido. Um snapshot inicial é silencioso. Em processos seguintes, somente IDs de documentos de vídeo novos ou metadados de vídeo alterados criam trabalho SAF_CHANGE. Exclusões isoladas, imagens, legendas, NFO e a simples abertura do app não disparam varredura.

O inventário é comprimido e persistido por árvore. O pedido de scan é gravado antes de avançar o snapshot e permanece pendente após scan parcial, cancelado ou com falha; somente uma geração SUCCEEDED consome a alteração. Assim, vídeos copiados enquanto o processo estava encerrado são detectados na próxima inicialização sem executar uma varredura completa em cada onResume.

## Escopo mantido
Esta fase não implementa:
- Genre Registry
- AniList matching
- Artwork Engine 2.0
- lazy library completa
- redesign CloudStream-like
- player UI
- gestures
- Settings Center
- backup/restore

Storage e permissões são mantidos pelo fluxo de storage e seus contratos.