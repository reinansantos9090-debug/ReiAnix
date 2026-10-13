# Controles de mídia Android — decisões de integração

A sessão Media3 e a notificação de reprodução são propriedade da instância ativa de NativePlayerActivity. PlayerMediaControls recebe o mesmo Player utilizado pela PlayerView; não cria nem mantém um segundo player. A Activity libera a notificação e a sessão em onDestroy(), antes de liberar o ExoPlayer. Não há MediaSessionService: o aplicativo pausa ao sair definitivamente do player e só mantém reprodução no comportamento de PiP já suportado.

## Ação de conteúdo da notificação

createCurrentContentIntent() retorna null intencionalmente. A sessão é proprietária de um player de vídeo e de uma URI que pode ter sido concedida por SAF ou por uma origem local. O contrato atual não fornece ao adaptador de notificação um mecanismo que prove, após a destruição da Activity, que a URI ainda pode ser aberta nem que as permissões transitórias originais podem ser reconstruídas. Criar um Intent apenas com a URI ou reutilizar cegamente os extras da Activity poderia abrir uma sessão nova inválida ou duplicar a reprodução. A notificação não tenta restaurar uma sessão encerrada; ao liberar os controles, a notificação é removida.

Para oferecer reabertura segura, será necessário um contrato já validado de restauração com identidade de mídia, estado de acesso à URI e deduplicação coordenada pela fonte de verdade do player. Essa infraestrutura não foi introduzida neste prompt para evitar uma arquitetura paralela.

## Capas

O adaptador de notificação não fornece uma capa (getCurrentLargeIcon() retorna null). A infraestrutura de arte atual é centrada no ArtworkEngine Python e no renderer de arte da interface Compose. A NativePlayerActivity não recebe um identificador de cache local ou uma URI de capa estável no contrato atual de NativePlayerRequest que permita resolver a imagem diretamente, sem cruzar a ponte legada ou iniciar busca/download. Por isso, nenhuma leitura de disco, requisição de rede ou decodificação especulativa é feita durante a reprodução. A notificação continua funcional usando título, episódio/arquivo e o estado real do player.

## Metadados e foco de áudio

Os metadados são definidos no MediaItem criado pela Activity e são consumidos pela notificação a partir do próprio Player. O título e o texto secundário têm alternativas para valores ausentes. O ExoPlayer permanece responsável pelo foco de áudio via setAudioAttributes(..., true); esta integração não cria um segundo gerenciador de foco.
