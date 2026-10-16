# Migração da Home para Kotlin nativo

## Estado e ponto de entrada

A Home visível no Android é `ReiAnixHomeRoute`, em
`android/app/src/main/kotlin/com/reiflix/reiflix_local/ui/home/ReiAnixHome.kt`.
`MainActivity.onCreate` monta um único `ReiAnixComposeLibraryHost` e abre
`ReiAnixRoutes.HOME`. A rota reutiliza o `ReiAnixLibraryViewModel` compartilhado
com Biblioteca, Minha Lista, Search, Details e Player; esta etapa não cria um
segundo ViewModel nem uma segunda fonte de verdade para o catálogo.

## Seções observadas e origem dos dados

A Home Compose atual compõe apenas seções que têm dados locais correspondentes:

- **Destaque:** selecionado da projeção da biblioteca; prioriza um alvo de
  reprodução disponível e depois os estados pessoais existentes.
- **Continuar assistindo:** a projeção Kotlin
  `ReiAnixNativeLibraryCatalogProjector` seleciona episódios com estado
  `IN_PROGRESS` e disponibilidade local `AVAILABLE`, ordenados pela última
  reprodução e identificador estável. A navegação continua usando a rota nativa
  de Player, que encaminha ao fluxo existente Media3/ExoPlayer.
- **Adicionados recentemente:** ordenação por `addedAt` persistido, não por
  modificação do arquivo nem por atualização de metadados.
- **Conteúdo disponível:** títulos com mídia local disponível.
- **Recentemente assistidos:** ordenação por `lastPlayedAt` persistido.
- **Minha lista:** itens com a associação pessoal persistida (`favorite`).
- **Seções por gênero e Filmes:** projeções dos modelos canônicos da biblioteca.

A carga principal começa no `ReiAnixLibraryViewModel`, que lê
`ReiAnixSqliteLibraryRepository.loadLibraryCatalogRows()` e usa
`ReiAnixNativeLibraryCatalogProjector`. O repositório SQLite canônico continua
sendo a fonte de verdade; as seções não consultam SQL dentro de Composables e
não materializam registros fictícios. `canonicalCatalog` e os fluxos derivados
evitam recalcular os mesmos dados para cada emissão não relacionada.

## Serviços e integrações reutilizados

- Biblioteca local: `ReiAnixSqliteLibraryRepository`,
  `ReiAnixLibraryQueryService` e `ReiAnixNativeLibraryCatalogProjector`.
- Histórico e progresso: `ReiAnixConsumptionService` e publicações de progresso
  confirmadas após commit; não existe persistência paralela na Home.
- Minha Lista/favoritos: `ReiAnixPersonalListService`; a interface atualiza após
  o resultado confirmado da mutação. Esta etapa acrescenta feedback de erro
  explícito e a ação de dispensar o aviso.
- Metadados AniList: `ReiAnixAniListIntegrationService` e
  `ReiAnixAniListClient`, inicializados pelo grafo nativo já existente.
- Capas/artwork: `ReiAnixArtworkService`, `ReiAnixLocalArtwork` e os
  componentes de arte compartilhados; não foi criado downloader nem cache
  exclusivo para Home.
- Pesquisa, Details, navegação e reprodução: contratos existentes em
  `ReiAnixNavigation.kt`, `ReiAnixDetailsRoute`, `ReiAnixSearchRoute` e
  `ReiAnixPlayerRoute`.

## Estados e atualização

A Home mantém dados locais disponíveis durante uma atualização. Esta etapa
acrescenta avisos visíveis para erro de operação de Minha Lista, falha de
atualização quando já há conteúdo local e carregamento parcial com dados
anteriores. O aviso de erro oferece nova tentativa quando aplicável; o erro de
Minha Lista pode ser dispensado. Uma falha não converte o catálogo disponível
em uma lista vazia nem bloqueia as demais seções.

O estado da tela continua a ser coletado com
`collectAsStateWithLifecycle`. `rememberSaveable(LazyListState.Saver)` mantém a
posição de rolagem enquanto a instância da rota é preservada pela navegação.
As chaves das seções e dos itens continuam estáveis.

## Python/Flet: o que foi e não foi removido

No fluxo Android com Compose primário, `main.py` mantém a rota lógica Flet
`home` como um recipiente vazio em vez de construir `HomeView`; a Home Flet
completa só é construída no caminho de compatibilidade (`compose_primary_ui`
desativado). Não removi `views/home_view.py`, seus testes ou rotas Python porque
a arquitetura ainda preserva caminhos de compatibilidade e responsabilidades de
outros subsistemas.

A Home Compose ainda oferece o atalho **Abrir Collector**, que navega pelo
contrato legado de navegação. O Collector não corresponde a uma rota nativa
identificada em `ReiAnixRoutes`; substituí-lo arbitrariamente por Organize ou
por outra tela mudaria o significado da ação. Portanto, esse atalho continua
sendo uma dependência legada explícita e não é declarado como migrado nesta
etapa. Ele não alimenta a biblioteca, as capas, os favoritos, a Home nem
Continuar assistindo.

O botão de atualização continua passando pelo mecanismo nativo existente de
`scan_library` de `MainActivity`, que coordena os scanners autorizados; os
itens e o progresso apresentados pela Home são relidos da base canônica Kotlin.
A seleção de pasta também usa o seletor SAF nativo. Não foi feita remoção global
de Python/Flet/Flutter.

## Testes e validação

Nenhum teste instrumentado, teste de emulador ou workflow de emulador foi
adicionado. Os testes Kotlin/JVM continuam reservados para execução manual e
não foram executados manualmente nesta etapa. Os testes e workflows existentes
foram preservados. O resultado efetivo de build, certificação de release e
artefatos será registrado no relatório final após os workflows do commit desta
etapa terminarem.
