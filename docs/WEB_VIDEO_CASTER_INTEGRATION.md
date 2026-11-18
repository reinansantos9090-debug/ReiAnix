# Transmitir com Web Video Caster (Roku e outras TVs)

O ReiAnix utiliza a mesma estratégia de **integração externa** do CloudStream. A opção **Transmitir → Web Video Caster** passa o vídeo local para o aplicativo [Web Video Caster](https://www.webvideocaster.com/integrations), que descobre dispositivos e gerencia o envio à Roku. **Não usa Smart View, Google Cast na Roku, nem receptor próprio de desenvolvimento.**

## Como usar

1. Instale **Web Video Caster** no Android. Se não estiver instalado, o ReiAnix oferece abrir sua página na Play Store.
2. No ReiAnix, abra um episódio armazenado localmente e toque em **Transmitir → Web Video Caster**.
3. O Web Video Caster abrirá com o vídeo preparado. Dentro dele, selecione sua **TV Roku** para transmitir.
4. Se o Web Video Caster solicitar o **receptor oficial** na Roku, instale-o pela loja da TV. **Não precisa de modo desenvolvedor, IP, senha ou ZIP.**
5. Deixe o celular na mesma rede Wi-Fi durante a transmissão. Para parar o compartilhamento local, use **Parar compartilhamento** no menu Transmitir ou a notificação do ReiAnix.

## Implementação

- `WebVideoCasterHandoff` cria um **Intent ACTION_VIEW** destinado ao pacote `com.instantbits.cast.webvideo`, com `video/*`, `title`, `secure_uri` e pacote de cabeçalhos HTTP, conforme a integração oficial.
- O vídeo vem de `LocalCastMediaServer.publish()`. Android `content://` e SAF não são legíveis pela Roku; o servidor serve **somente o arquivo selecionado** por uma URL temporária HTTP na LAN, com token não previsível e suporte a requisições de byte range.
- `CastStreamingService` é iniciado em primeiro plano antes de abrir o aplicativo externo e preserva a URL do vídeo enquanto o ReiAnix estiver em segundo plano. O endpoint só é desligado ao usar a notificação/ação parar ou encerrar o serviço.
- O player local é pausado após abrir o aplicativo externo para evitar áudio duplicado. A reprodução e a escolha da TV ficam dentro do Web Video Caster; o ReiAnix **não controla diretamente o estado nem a posição do player externo**.
- O botão Chromecast mantém o SDK nativo, posição e tamanho atual; o menu impede compartilhar com o Web Video Caster enquanto uma sessão Google Cast estiver ativa e vice-versa.
- O antigo Roku SSDP/ECP da PR #365 foi removido. **Nenhum código de descoberta Roku ou receiver próprio foi mantido**.

## Limitações

- O Web Video Caster precisa estar instalado no Android e pode pedir seu receptor oficial na Roku. A instalação do Web Video Caster **não é automática**.
- A reprodução real depende da compatibilidade do arquivo/codec, da rede e do próprio aplicativo externo. Não ocorre transcodificação.
- O link só funciona enquanto o serviço de transmissão do ReiAnix está ativo. Interromper a notificação encerra o acesso remoto ao arquivo.
- A PR contém testes do contrato de Intent, mas para confirmar a transmissão na Roku é indispensável testar com o aplicativo externo e uma TV real.

Código de referência:
- [Integração oficial do Web Video Caster](https://www.webvideocaster.com/integrations)
- [CloudStream — WebVideoCastPackage.kt](https://github.com/recloudstream/cloudstream/blob/master/app/src/main/java/com/lagradost/cloudstream3/actions/temp/WebVideoCastPackage.kt)
