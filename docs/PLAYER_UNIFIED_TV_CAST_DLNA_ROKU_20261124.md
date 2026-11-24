# Transmitir para TV — ReiAnix

Data: 24/11/2026. Implementação em PR, sem merge.

## UX

- Cinco atalhos padrão: Bloquear, Episódios, Transmitir, Próximo episódio, Opções.
- Velocidade continua no menu e como atalho opcional. Migração da ordem padrão antiga com Velocidade, sem sobrescrever personalizações reais.
- Um único botão abre uma lista única de TVs, sem nomes dos protocolos. Dispositivos diferentes com nomes iguais permanecem visíveis, numerados quando necessário. A mesma TV encontrada por dois protocolos aparece uma vez, priorizando transmissão direta.
- Google Cast e renderizadores UPnP/DLNA AVTransport iniciam a reprodução diretamente, com controle de pausa, retomada e desligamento.
- Roku: selecionar a TV abre o episódio no **Web Video Caster** do celular (único aplicativo externo integrado). O usuário conclui a escolha da Roku dentro dele. O fluxo não utiliza Roku Media Player nem Castify.

## Limitações

- Roku ECP restringe comandos externos de terceiros. A Roku continua detectada na lista única; o envio usa a integração oficial Android ACTION_VIEW do Web Video Caster, fixada ao pacote com.instantbits.cast.webvideo. Não afirmar que a TV começou a reproduzir só porque o app externo abriu.
- O compartilhamento de arquivos SAF/MediaStore aplica permissão temporária de leitura (ClipData e FLAG_GRANT_READ_URI_PERMISSION), convertendo file:// via FileProvider já existente; não incorpora código proprietário.
- Se o Web Video Caster estiver ausente, pedir ao usuário antes de abrir sua página no Google Play, sem instalar aplicativos automaticamente.
- A integração pública do Web Video Caster não documenta escolha remota da Roku por outro aplicativo: seleção e reprodução continuam no Web Video Caster, cujo canal receptor na Roku pode ser exigido. A TV precisa suportar o codec.
- Arquivos locais são compartilhados pela rede Wi-Fi, via HTTP com Range. SSDP e anúncios DLNA são associados à conexão Wi-Fi/Ethernet, não à rede móvel. O player deve ficar aberto e ambos os aparelhos devem usar a mesma rede.
- Sem transcodificação. MKV, codecs e legendas só funcionam se a própria TV os suportar.
- Compartilhar apenas arquivo atual selecionado pelo usuário via SAF; URL protegida por token e servidor na LAN privada.
- Acesso HTTP local exige permitir cleartext para DLNA na configuração Android.

## Verificações

- Migração e restauração do padrão, ícone na posição central e acesso a Velocidade.
- Descoberta Cast, DLNA e Roku em TV real, sem repetição de nomes, controle remoto e reconexão.
- Roku: botão selecionado abre diretamente Web Video Caster com o episódio, com grant do arquivo; se faltar o app, exibe opção explícita de instalação. Validar reprodução na Roku pelo aplicativo externo, sem prometer início imediato na TV.
- SAF não acessível, falta de Wi-Fi, formatos incompatíveis e troca de episódio.
- Testes Kotlin/JVM manuais não executados; resultado do workflow oficial necessário.

Fontes:
https://developers.google.com/cast/docs/android_sender/integrate
https://developers.google.com/cast/docs/web_receiver
https://developer.roku.com/dev/docs/external-control-api
https://www.webvideocaster.com/integrations
https://www.webvideocast.app/receivers
