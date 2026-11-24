# Player — Abrir episódios em outros aplicativos

Em **Configurações → Player → Reprodução → Reprodutor padrão**, escolha:

- **Reprodutor interno** (padrão): abre diretamente o player Media3 do ReiAnix.
- **Reprodutor externo**: ao tocar em um episódio, abre diretamente o seletor nativo “Abrir com...” do Android, sem iniciar o player interno. Escolha um aplicativo compatível a cada abertura.

O ReiAnix não memoriza o aplicativo escolhido. O antigo modo “Perguntar sempre” volta ao padrão interno. O menu durante a reprodução não contém mais “Abrir com...”; Transmitir continua independente.

## Segurança e limitações

- O seletor usa `Intent.createChooser` com `ACTION_VIEW` e MIME `video/*`. A aparência, a lista de aplicativos e os perfis exibidos são controlados pelo Android.
- URIs SAF/MediaStore são compartilhadas com permissão temporária de leitura e ClipData. Arquivos locais usam o FileProvider existente.
- O player interno conserva o progresso, as configurações e a identidade do episódio. Players externos não têm integração garantida com o progresso, legendas ou avanço automático do ReiAnix.
- Cenas favoritas continuam no player interno para preservar os recortes.
- A compatibilidade deve ser validada em dispositivo real.
