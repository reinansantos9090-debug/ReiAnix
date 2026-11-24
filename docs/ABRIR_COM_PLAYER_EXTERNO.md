# Player — Abrir episódios em outros aplicativos

Em **Configurações → Player → Reprodução → Reprodutor padrão**, escolha:

- **Reprodutor interno** (padrão): abre diretamente o player Media3 do ReiAnix.
- **Reprodutor externo**: usa o aplicativo escolhido em **Aplicativo externo**. Se nenhum estiver selecionado ou o aplicativo não estiver mais disponível, permite escolher novamente.
- **Perguntar sempre**: oferece o ReiAnix e os aplicativos compatíveis instalados antes de abrir o episódio.

O menu de opções durante a reprodução não contém mais “Abrir com...”. A opção Transmitir continua independente.

## Segurança e limitações

- Somente aplicativos instalados que aceitam `ACTION_VIEW` com `video/*` são oferecidos; o próprio ReiAnix é excluído da lista externa.
- URIs SAF/MediaStore são encaminhadas com uma permissão temporária de leitura. Arquivos locais usam o FileProvider existente.
- A reprodução interna continua utilizando a identidade canônica do episódio, o progresso e as configurações existentes. Players externos não têm integração garantida com o progresso, as legendas ou o avanço automático do ReiAnix.
- Cenas favoritas continuam no player interno para preservar seus recortes.
- A compatibilidade depende do aplicativo escolhido e precisa ser validada em dispositivo real.
