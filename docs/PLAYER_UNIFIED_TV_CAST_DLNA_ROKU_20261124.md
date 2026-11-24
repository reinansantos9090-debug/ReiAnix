# Transmitir para TV — ReiAnix

Data: 24/11/2026. Implementação em PR, sem merge.

## UX

- Cinco atalhos padrão: Bloquear, Episódios, Transmitir, Próximo episódio, Opções.
- Velocidade continua no menu e como atalho opcional. Migração da ordem padrão antiga com Velocidade, sem sobrescrever personalizações reais.
- Um único botão abre uma lista única de TVs, sem nomes dos protocolos. Dispositivos diferentes com nomes iguais permanecem visíveis, numerados quando necessário. A mesma TV encontrada por dois protocolos aparece uma vez, priorizando transmissão direta.
- Google Cast e renderizadores UPnP/DLNA AVTransport iniciam a reprodução diretamente, com controle de pausa, retomada e desligamento.
- Roku: escolher a TV ativa o servidor ReiAnix, mas o usuário deve abrir Roku Media Player e escolher o episódio. Roku não é um renderizador DLNA padrão.

## Limitações

- Roku ECP atualmente proíbe comandos de terceiros vindos de apps Android. Não simular transmissão automática.
- Arquivos locais são compartilhados pela rede Wi-Fi, via HTTP com Range. SSDP e anúncios DLNA são associados à conexão Wi-Fi/Ethernet, não à rede móvel. O player deve ficar aberto e ambos os aparelhos devem usar a mesma rede.
- Sem transcodificação. MKV, codecs e legendas só funcionam se a própria TV os suportar.
- Compartilhar apenas arquivo atual selecionado pelo usuário via SAF; URL protegida por token e servidor na LAN privada.
- Acesso HTTP local exige permitir cleartext para DLNA na configuração Android.

## Verificações

- Migração e restauração do padrão, ícone na posição central e acesso a Velocidade.
- Descoberta Cast, DLNA e Roku em TV real, sem repetição de nomes, controle remoto e reconexão.
- Roku Media Player encontra servidor ReiAnix e reprodução de MP4 compatível.
- SAF não acessível, falta de Wi-Fi, formatos incompatíveis e troca de episódio.
- Testes Kotlin/JVM manuais não executados; resultado do workflow oficial necessário.

Fontes:
https://developers.google.com/cast/docs/android_sender/integrate
https://developers.google.com/cast/docs/web_receiver
https://developer.roku.com/dev/docs/external-control-api
https://support.roku.com/pt-br/article/share-media-over-usb-or-network
