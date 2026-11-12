# ReiAnix — 34 técnicas de reconhecimento de downloads

As 34 opções selecionadas foram adicionadas como estratégias **condicionais** à detecção de tamanho, nome e formato. **Não executar todas em todo link.** O objetivo é preservar velocidade, bateria e transferência, além de evitar números falsos. A PR só pode ser mesclada após validação dos testes e APK.

## Mapa de implementação

| Nº | Tratamento | Código | Exatidão / limite |
|---|---|---|---|
| 04 | HTTP 416 e `Content-Range: bytes */n` | DownloadAdvancedHttp.unsatisfiedLength | Exato quando o servidor fornece n |
| 05 | `Range: bytes=-1` | DownloadAdvancedHttp.inspect | Exato por Content-Range 206 |
| 07 | `multipart/byteranges` | DownloadAdvancedHttp.multipartTotal | Exato quando todas as partes concordam |
| 10 | `Content-Location` | DownloadAdvancedHttp.resolveContentLocation | Apenas mesma origem HTTPS/HTTP |
| 12 | Transfer-Encoding chunked | DownloadEngine + DownloadAdvancedHttp.chunkedCompleteLength | Exato **somente ao EOF** |
| 14 | ETag + revalidação condicional | DownloadAdvancedHttp, cache 120s | Usa resultado já validado em 304 |
| 16 | Cabeçalhos proprietários de comprimento | DownloadAdvancedHttp.vendorLength | Apenas indicação, não exato |
| 20 | Retentativa curta e limitada | DownloadAdvancedHttp.inspect | No máximo duas por fase |
| 23 | HLS AVERAGE-BANDWIDTH | DownloadSizeProbe.parseMaster | Estimativa |
| 25 | Cabeçalhos de todos os segmentos de playlist pequena | DownloadSizeProbe.inspectHls | 40 segmentos antes do download; playlists maiores amostradas; bytes reais ao terminar |
| 27 | HLS tamanho médio ponderado por duração | DownloadHlsMetadata.weightedEstimate | Estimativa |
| 29 | EXT-X-MAP e BYTERANGE | DownloadHlsMetadata.parse | Tamanho exato *do conteúdo de origem* se todos os ranges conhecidos |
| 31 | Padding AES-CBC, impacto no MP4 final | DownloadSizeProbe.inspectHls | Estimativa, nunca forçar valor exato |
| 32 | Low-Latency HLS EXT-X-PART | DownloadHlsMetadata.parse | Apenas VOD / sem somar duplicado |
| 34 | Cache em memória por segmento | DownloadHlsMetadata | 60 segundos, limite de entradas |
| 35 | Manifestos MPEG-DASH MPD | DownloadDashMetadata.parse/inspect | Somente reconhecimento de metadados; download DASH ainda é independente |
| 36 | SegmentTemplate + SegmentTimeline | DownloadDashMetadata.parse | URLs, números e durações limitadas |
| 37 | SegmentList | DownloadDashMetadata.parse | Soma ranges explícitos |
| 38 | SegmentBase/indexRange | DownloadDashMetadata.parse | Identificação de índice |
| 39 | Análise de caixas SIDX | DownloadDashMetadata.sidxBytes/inspect | Soma referências de mídia; não tamanho exato do MP4 final |
| 42 | Duração e bandwidth MPD | DownloadDashMetadata.Plan.estimateBytes | Estimativa |
| 43 | MP4 atom/mdat/moov extent | DownloadContainerMetadata.mp4AtomExtent | Extensão declarada aproximada |
| 45 | Matroska/WebM EBML Segment | DownloadContainerMetadata.ebmlSegmentExtent | Estimativa de extensão |
| 47 | ASF/WMV File Properties | DownloadContainerMetadata.asfSize | Exato quando cabeçalho consistente |
| 48 | PCR do TS / taxa média | DownloadContainerMetadata.tsPcrBitsPerSecond e DownloadSizeProbe | Estimativa quando duração HLS conhecida |
| 49 | FLV onMetaData filesize | DownloadContainerMetadata.flvSize | Estimativa não autoritativa |
| 50 | Android MediaMetadataRetriever | DownloadContainerMetadata.nativeEstimate e DownloadEngine | Apenas arquivos locais .bin concluídos |
| 51 | Comprimento do DownloadListener do WebView | DownloadBrowserActivity + DownloadsScreen | Exato quando fornecido para arquivo direto |
| 53 | Cookie/Referer/User-Agent da sessão | DownloadBrowserActivity e DownloadHttp | Apenas origem autorizada; sem copiar segredo para outros domínios |
| 54 | Metadados de cloud pública | DownloadSpecialMetadata.inspectCloud + vendor headers | GCS JSON API público, Azure/Cloud por cabeçalhos, sem acesso a objetos privados |
| 55 | Schema.org VideoObject.contentSize | DownloadLinkMetadata.videoPage | Somente uma mídia inequívoca, estimativa |
| 56 | JSON de metadados de provedor compatível | DownloadLinkMetadata.providerJson | Somente formatos conhecidos e endpoint autorizado |
| 59 | WebDAV PROPFIND Depth:0 | DownloadSpecialMetadata.inspectWebDav | Apenas DAV detectado ou caminho webdav |
| 61 | Metalink .meta4 e torrent metainfo | DownloadLinkMetadata.metalink/torrent | Tamanho do arquivo descrito, não do manifesto; não baixa torrents |

## Limitações essenciais

- Servidor que não fornece tamanho e arquivo sem tamanho interno confiável **não permite saber valor exato antes de baixar**.
- O valor de um manifesto HLS/DASH pode não corresponder ao tamanho depois de remux/transcodificação.
- Um metadata endpoint público pode exigir autorização; nunca violar autenticação, proteção anti-bot ou DRM para descobrir o tamanho.
- O Media3 de Android não converte universalmente qualquer vídeo/codec.
- O total HLS de arquivos grandes é amostrado para não gerar centenas de requisições ao abrir uma caixa de diálogo.
- O reconhecimento de metadata de torrent/Metalink não significa implementação de um cliente torrent.
- O método PROPFIND requer OkHttp; só é acionado em fontes WebDAV detectadas.
- Testar com exemplos locais conhecidos (URL HTTP range, HLS VOD simples/segmentado, DASH, AVI, ASF, MP4, MKV, FLV, HTML) e depois com links reais autorizados.

## Referências de protocolo

RFC 9110 (HTTP Range/416/multipart), RFC 8216 (HLS), MPEG-DASH ISO 23009-1, ISO BMFF/14496-12, Matroska EBML, RFC 4918 (WebDAV) e RFC 5854 (Metalink). Estratégias gerais de servidores foram comparadas com Gopeed, Download Navi e AB Download Manager, sem copiar seu código.
