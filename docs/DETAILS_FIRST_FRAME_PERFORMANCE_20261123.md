# Otimização de entrada em Detalhes — 23/11/2026

## Gargalos encontrados no código e correções
- Assinatura de *todos* os episódios para detectar atualização de thumbnails
  era calculada em `remember(state.anime)`, durante a composição no thread de UI.
  Agora `snapshotFlow` observa o estado, o cálculo é feito em
  `Dispatchers.Default` e `distinctUntilChanged` evita novos pedidos por
  mudanças apenas de progresso/estado assistido. Continua sem debounce.
- A busca do anime no catálogo e a seleção de todos os episódios elegíveis
  a thumbnails ocorriam sincronamente no callback de abertura. Agora acontecem
  em `Dispatchers.Default` dentro da rotina assíncrona que já existia; o
  gerenciamento in-flight, reruns e a ordem dos episódios foram mantidos.
- `ReiAnixSeasonUiModel.stableKey` ordenava os episódios repetidamente
  mesmo nas temporadas numeradas, embora a assinatura não fosse usada.
  Agora utiliza o número diretamente e guarda por `lazy` a chave de fallback.
- Metadados resumidos do hero são memorizados por seus campos efetivos em vez
  de criar uma string em toda recomposição.
- Durante transições que envolvem Detalhes locais ou AniList, dois conteúdos
  pesados deixam de ser escalados simultaneamente; o fade de 150 ms permanece.
  A navegação entre outras abas mantém a animação anterior.

## Contratos preservados
Sem mudanças de visual de capas, resolução, download, consulta AniList,
extração de thumbnails, fontes, cachês, ordem da Biblioteca, armazenamento,
associações, navegação de Voltar, player ou reprodução. Sem criação de emulador
ou disparo manual de testes Kotlin/JVM.

## Verificação em dispositivo
Comparar o tempo da entrada em Detalhes e o pico do gráfico de renderização
ao abrir One Piece, um anime com muitos episódios e outra aba; repetir
entrar/voltar diversas vezes. Validar banners, capas, recomendações, thumbnails,
personagens, favoritos, temporada selecionada e Voltar entre recomendações.

Sem medições Perfetto/JankStats **no aparelho**, ainda não é possível provar
ganho percentual de FPS ou ausência completa de engasgos.
