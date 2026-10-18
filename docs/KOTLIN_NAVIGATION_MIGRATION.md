# Migração da navegação para Kotlin — Prompt 21/29

## Resultado arquitetural

A navegação visível do Android é controlada pelo único NavHost existente em
ReiAnixNavigation.kt, mantido dentro de ReiAnixAppShell e da instância persistente
de ComposeView em ReiAnixComposeLibraryHost. A implementação não cria um segundo
NavHost, um segundo NavController concorrente nem uma cópia permanente do back stack.

Antes desta etapa, ReiAnixNavigationHost enviava toda mudança de rota para
ReiAnixComposeLibraryHost.publishComposeRouteChanged, que publicava
compose_navigation_changed na NativeMailbox. main.py recebia o evento, projetava
novamente a rota no NavigationController Python e iniciava hidratação assíncrona
de Details. Havia também eventos de retorno compose_library_navigation e
compose_settings_navigation para encaminhar Back/navegação de volta ao Flet.
Esses caminhos de navegação foram removidos: os destinos Compose não precisam de um
round trip Python para decidir ou refletir sua rota.

## Rotas e argumentos existentes

As rotas seguintes foram encontradas no grafo atual; esta etapa não cria destinos de domínio:

| Destino | Contrato |
| --- | --- |
| Home | home |
| Biblioteca | library |
| Minha Lista | my_list |
| Buscar | search |
| Ajustes | settings |
| Armazenamento | storage |
| Organizar | organize |
| Recuperação | recovery |
| Jornada do Colecionador | collector |
| Details local | details/{animeId}?origin={origin} |
| Details AniList remoto | anilist_details/{anilistId}?origin={origin} |
| Rota de handoff do player | player/{episodeId}?animeId={animeId}&origin={origin} |

Os builders ReiAnixRoutes.details, aniListDetails e player validam argumentos
não vazios (e ID AniList positivo) e aplicam Uri.encode aos valores textuais. Details
local mantém separado o identificador canônico local e a rota AniList usa o ID externo;
não se cria um registro de biblioteca apenas para abrir um resultado remoto. Player
permanece um destino de handoff para NativePlayerActivity/Media3, não um segundo motor
de reprodução.

## Back stack, abas e retorno

- rememberNavController é criado no único host Compose; a instância é mantida enquanto
  a ComposeView persiste e o ID estável da view permite restauração rememberSaveable.
- A navegação inferior Home/Biblioteca/Buscar/Ajustes utiliza launchSingleTop,
  restoreState e popUpTo(findStartDestination) com saveState.
- Minha Lista, Organizar, Collector, Storage, Recovery, Details e as rotas de player
  continuam destinos secundários reais; Details seleciona a aba de origem quando
  aplicável e o back stack retorna à origem com popBackStack.
- Back no Compose visível é tratado pelo host nativo. Destinos aninhados removem uma
  entrada; destinos secundários sem origem têm retorno de segurança. Storage volta a
  Ajustes quando for uma raiz. Home e Recovery preservam a regra de confirmação nativa:
  primeiro Back mostra “Pressione voltar novamente para sair”; novo Back dentro de
  dois segundos encerra a Activity.
- O callback Android único e o guard contra despacho duplicado foram preservados.
  navigationChannel.popRoute() permanece só como fallback quando a superfície Compose
  está escondida e uma tela Flet legada está ativa.

## Details, player e argumentos

Home, Biblioteca, Busca e Minha Lista chamam os builders Compose existentes com
identificadores estáveis e a origem. Os dados da tela Details continuam a ser carregados
por ReiAnixLibraryViewModel/serviços canônicos; a camada de navegação não consulta SQL,
não baixa metadados e não grava favoritos/progresso. O player conserva os IDs e o
contrato de sessão existentes e abre a Activity nativa; o progresso continua sob os
serviços de consumo/player existentes. Ao retornar, o destino de handoff é removido
pelo lifecycle já implementado, sem inserir uma Home/Details adicional.

## Settings, Storage, Organize e onboarding

Settings, Storage e Organize são destinos Compose no mesmo grafo. Os callbacks de Back
dessas telas agora chamam o mesmo controlador nativo e não emitem comandos de navegação
ao Flet. O onboarding continua controlado pela NativeStorageOnboardingState e pelas
ações Android existentes; selecionar, cancelar ou revalidar SAF não altera a navegação
para fingir que o armazenamento foi configurado. Serviços e permissões permanecem donos
das operações de domínio.

## Runtime híbrido e pontes preservadas

A remoção abrangeu o evento compose_navigation_changed, os eventos de navegação
legada compose_library_navigation e compose_settings_navigation, o estado-sombra
compose_route_state, sua tarefa de hidratação de Details, a função Python
NavigationController.sync_top_level e os métodos Kotlin que publicavam essas mensagens.
Foram mantidos NativeMailbox, AndroidBridge, ComposeLibraryBridge e
ComposeSettingsBridge para comandos/estados de domínio que ainda possuem consumidores,
como operações de biblioteca, configurações, SAF e atualizações.

O ramo Flet e core/navigation.py continuam para fallback não-Compose/desktop. A
MainActivity continua herdando de FlutterFragmentActivity, e o workflow de APK
continua preparando o host Flet; portanto, o aplicativo ainda não é um runtime 100% Kotlin
e este prompt não declara a migração global concluída.

## Testes e verificação

Os testes de contrato Python existentes foram atualizados para exigir ausência da projeção
de rota para Python e para verificar o ownership nativo do Back, mantendo as verificações
dos comandos de domínio. Foram ajustados delimitadores de teste que antes usavam os
eventos de navegação removidos. Nenhum teste instrumentado/emulador foi criado e nenhum
teste Kotlin/JVM foi executado manualmente por esta etapa.

A verificação final de compilação, regressões Python e artefatos depende dos workflows
associados ao commit desta etapa; consulte os resultados vinculados ao SHA final antes
de declarar o prompt concluído.
