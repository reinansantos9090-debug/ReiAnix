# ReiAnix — Configurações avançadas 2.0

## Objetivo

As opções avançadas usam CloudStream, Nova Video Player e Animiru/AnIyomi somente como referências de comportamento e de organização de opções. Nenhum branding, tela ou código desses projetos foi copiado.

CloudStream mantém um player configurável, mas seu projeto é GPL-3.0 e seu foco inclui extensões/fontes/streaming; essas partes não foram incorporadas ao ReiAnix.  
Nova Video Player documenta gestos, velocidade, aspect ratio e modos de reprodução para mídia local; seu repositório é Apache-2.0.  
Aniyomi/Animiru documentam categorias de player para controles, gestos, decoder, legendas, áudio e configurações avançadas; Aniyomi é Apache-2.0.  
A implementação do ReiAnix continua própria e usa SettingsStore, LibraryService, ArtworkEngine e NativePlayerActivity existentes.

## Matriz de auditoria

| Referência | Configuração | Compatibilidade ReiAnix | Estado | Ação |
|---|---|---|---|---|
| CloudStream / Nova / Aniyomi | velocidade padrão | Media3 | Já existente e aplicada | JÁ EXISTE |
| Aniyomi / Nova | velocidade disponível | Media3 | Lista no player já existe | JÁ EXISTE |
| Aniyomi / Nova | double tap ±N segundos | Media3 + GestureDetector | Antes fixa em 10s; agora configurável | ADAPTAR |
| Aniyomi / Nova | pressão longa com velocidade temporária | GestureDetector + Media3 | Antes fixa em 2x; agora configurável | ADAPTAR |
| Aniyomi | gesto vertical volume/brilho | Android AudioManager/Window | Já existente e opt-in | JÁ EXISTE |
| Aniyomi | seek horizontal por swipe | Contraria requisito do ReiAnix | Bloqueado | NÃO IMPLEMENTAR |
| Aniyomi | auto-hide dos controles | Player custom existente | Persistido e aplicado | JÁ EXISTE |
| Aniyomi | orientação | ActivityInfo | Persistido e aplicado | JÁ EXISTE |
| Aniyomi / Nova | immersive/system bars/PiP | Android API existente | Persistido e aplicado | JÁ EXISTE |
| Aniyomi / Nova | Fit/Fill/Zoom | Media3 AspectRatioFrameLayout | Ajustado para Fit/Preencher sem stretch | ADAPTAR |
| Nova | Original/Auto/forced ratios/projector mode | Não há benefício suficiente no player atual | Não incorporado | NÃO IMPLEMENTAR |
| Media3 / Aniyomi | limite de resolução | TrackSelectionParameters | Novo e aplicado | IMPLEMENTAR |
| Media3 / Aniyomi | limite de FPS | TrackSelectionParameters | Novo e aplicado | IMPLEMENTAR |
| Media3 / Aniyomi | limite de canais de áudio | TrackSelectionParameters | Novo e aplicado | IMPLEMENTAR |
| Aniyomi | decoder hardware/software/GPU/MPV | ReiAnix usa ExoPlayer/Media3 | Não há segunda engine compatível | NÃO COMPATÍVEL |
| Aniyomi | MPV scripts/config | Arquitetura proibiria player paralelo | Sem infraestrutura e indesejado | NÃO IMPLEMENTAR |
| Aniyomi | idioma de áudio | TrackSelectionParameters | Já existente e aplicado | JÁ EXISTE |
| Aniyomi | idioma de legenda | TrackSelectionParameters | Já existente e aplicado | JÁ EXISTE |
| Aniyomi | ligar/desligar legenda | Media3 track selection | Já existente e aplicado | JÁ EXISTE |
| Aniyomi | escala de legenda | SubtitleView | Novo e aplicado | IMPLEMENTAR |
| Aniyomi | margem da legenda | SubtitleView | Novo e aplicado | IMPLEMENTAR |
| Aniyomi | estilo embutido | SubtitleView | Novo e aplicado | IMPLEMENTAR |
| Aniyomi / CloudStream | delay de legenda | Exigiria política persistente de offset da cue/player | Sem infraestrutura segura neste Media3 1.11.1 | REQUER INFRAESTRUTURA |
| Aniyomi | delay de áudio | Exigiria offset de reprodução por track | Sem infraestrutura equivalente no player atual | REQUER INFRAESTRUTURA |
| Aniyomi | filtros de vídeo | Não há pipeline de filtros Media3 no player atual | Não suportado sem nova infraestrutura | NÃO COMPATÍVEL |
| Aniyomi | marcação como assistido em percentual | Sistema de consumo do ReiAnix usa 0.90 como contrato | Não alterado nesta fase | JÁ EXISTE |
| Aniyomi | preservar posição | Sistema de progresso já existente | Já existente | JÁ EXISTE |
| Aniyomi / CloudStream | autoplay próximo episódio | Biblioteca + NativePlayerActivity | Já existente e aplicado | JÁ EXISTE |
| Aniyomi / Nova | informações técnicas | Decoder/track diagnostics | Já existente | JÁ EXISTE |
| Aniyomi | sleep timer | Exigiria estado temporizado do player | Não necessário para objetivo desta fase | NÃO IMPLEMENTAR |
| CloudStream / Aniyomi | streaming/provider/source/downloader | Fonte de verdade do ReiAnix é local | Arquiteturalmente incompatível | NÃO COMPATÍVEL |
| Nova / Aniyomi | ordenação/densidade/tamanho da biblioteca | Home/LibraryService existentes | Agora persistido e aplicado | IMPLEMENTAR |
| Aniyomi / biblioteca | paginação e lazy loading | Home existente | page_size passou a ser configurável sem nova fonte de dados | IMPLEMENTAR |
| Artwork/cache | limite de cache | ArtworkEngine existente | Novo limite aplicado ao único cache | IMPLEMENTAR |
| Metadata | AniList ligado/desligado | AniListClient existente | Agora bloqueia/permite chamadas remotas | IMPLEMENTAR |
| Metadata | auto-match | AniList Matching existente | Agora respeita preferência | IMPLEMENTAR |
| Privacidade | analytics/telemetria | Filosofia local/offline/privacy-first | Não há tracking e nenhum foi adicionado | NÃO IMPLEMENTAR |

## Configurações implementadas

| Configuração | Categoria | Default | Efeito real |
|---|---|---:|---|
| Tamanho dos cards | Biblioteca | medium | altera dimensões dos cards da Home |
| Mostrar miniaturas | Biblioteca | true | evita carregamento/renderização de imagens na Home quando desativado |
| Ordenação padrão | Biblioteca | added_desc | define a ordenação inicial da Home |
| Densidade da grade | Biblioteca | medium | altera espaçamento da grade |
| Itens por página | Biblioteca | 36 | altera page_size da consulta existente |
| Salto no double tap | Player/Gestos | 10 s | altera o seek do double tap; swipe horizontal continua desativado |
| Velocidade da pressão longa | Player/Gestos | 2.0x | aplica velocidade temporária durante long press |
| Resolução máxima | Vídeo | automática | limita a seleção de vídeo do Media3 |
| FPS máximo | Vídeo | automático | limita a taxa da faixa de vídeo |
| Canais de áudio máximos | Áudio | automático | limita a faixa de áudio selecionada |
| Escala da legenda | Legendas | 100% | ajusta o tamanho via SubtitleView |
| Margem inferior da legenda | Legendas | 8% | ajusta o bottom padding via SubtitleView |
| Estilo embutido da legenda | Legendas | true | controla uso dos estilos/fontes declarados pela faixa |
| Usar AniList | Metadata | true | bloqueia/permite chamadas remotas do AniListClient |
| Auto-match AniList | Metadata | true | bloqueia novas buscas automáticas quando desativado |
| Artwork remoto | Artwork | true | bloqueia/permite downloads remotos pelo ArtworkEngine |
| Limite do cache de artwork | Artwork/Desempenho | 128 MB | altera o limite do único ArtworkEngine |

Media3 permite alterar TrackSelectionParameters antes e durante a reprodução, inclusive por restrições de seleção.

## Configurações removidas por falta de efeito real

Estas chaves existiam, mas não tinham uma aplicação efetiva no runtime desta arquitetura e foram retiradas do SettingsStore para não manter controles ou preferências falsas:

- app.start_screen
- app.animations
- appearance.show_badges
- metadata.keep_local
- artwork.offline_cache
- privacy.external_sync

Não foram adicionadas opções de streaming, provider, scraper, downloader, source remoto, Google Drive, MPV scripts ou outro motor de reprodução.

## Persistência e migração

O SettingsStore passou para schema 2. Exports/imports schema 1 continuam aceitos. O valor legado de aspect ratio `zoom`, `auto` ou `original` é normalizado para o modelo atual sem stretch: `fill` significa Preencher por crop preservando proporção, e `fit` significa Ajustar.

## Busca

A busca do Settings Center indexa agora chave, nome e descrição de cada opção, permitindo buscas como “legenda”, “resolução”, “cache”, “AniList”, “double tap” e “biblioteca”.

## Limites desta fase

Não foi implementado um seletor de decoder alternativo, filtros de vídeo, delay persistente de A/V ou de legenda, scripts MPV, fontes de vídeo remotas ou controle de provider. Esses itens exigiriam infraestrutura que não existe no player Media3 atual ou entrariam em conflito com o modelo local-first do ReiAnix.

## Referências

### CloudStream
- Repositório: https://github.com/recloudstream/cloudstream
- Licença indicada pelo repositório: GPL-3.0.

### Nova Player
- FAQ sobre aspect ratio e playback speed: https://github.com/nova-video-player/aos-AVP/blob/nova/faq/index.html
- FAQ sobre touch zones e gestures: https://github.com/nova-video-player/aos-AVP/blob/nova/faq/faq.md
- Repositório e licença: https://github.com/nova-video-player/aos-AVP

### Animiru / Aniyomi
- Aniyomi player settings: https://aniyomi.org/docs/guides/player-settings/
- Gestures: https://aniyomi.org/docs/guides/player-settings/gestures
- Internal player: https://aniyomi.org/docs/guides/player-settings/internal-player-settings
- Decoder: https://aniyomi.org/docs/guides/player-settings/decoder
- Audio: https://aniyomi.org/docs/guides/player-settings/audio
- Subtitles: https://aniyomi.org/docs/guides/player-settings/subtitles
- Advanced: https://aniyomi.org/docs/guides/player-settings/advanced

Animiru forks of the Aniyomi lineage document a configurable mpv-based player and local watching, but that player engine was not copied into ReiAnix.

### Android / Media3
- Track selection parameters: https://developer.android.com/media/media3/exoplayer/track-selection
- SubtitleView APIs used by this phase: AndroidX Media3 SubtitleView documentation was consulted for embedded styles, fractional text size and bottom padding.

## Validation status

Este documento registra decisões e implementação. Os resultados finais de Python, Android unit, build APK, Manifest empacotado, SHA-256 e dispositivo físico devem ser determinados pelo CI e pelas validações efetivamente executadas no commit final.