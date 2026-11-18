# ReiAnix — transmissão direta de vídeo para Roku

## O que mudou na PR #365

O botão **Transmitir** oferece Chromecast e Roku. A opção Roku agora **envia o arquivo de vídeo local para o receptor ReiAnix Receiver instalado na TV**, sem abrir Smart View nem espelhar a tela inteira.

1. Ligue a Roku e o celular na mesma rede Wi-Fi.
2. Abra qualquer vídeo local no ReiAnix.
3. Toque em **Transmitir → Roku** e selecione a TV encontrada.
4. O ReiAnix publica **somente aquele vídeo** no servidor HTTP local temporário e envia o link pela rede. O receptor Roku inicia a reprodução na TV.
5. Continue controlando pelo ReiAnix (play/pause, busca, próximo episódio) ou pelo controle da TV. A notificação permite parar a transmissão.

**Não precisa repetir a instalação feita anteriormente** pelo usuário: a Roku já possui o ReiAnix Receiver da PR #363 instalado como aplicativo de desenvolvimento. Esta PR adiciona o **emissor** Android necessário para falar com ele; o código Roku da PR #363 não foi mesclado à main.

## Limitação obrigatória para outras Roku

O Roku OS **não expõe Google Cast**. O antigo canal interno **Play on Roku (id 15985)** teve o acesso de terceiros bloqueado desde o Roku OS 11.5. Um link de vídeo enviado ao Roku pelo ECP **não é suficiente**, pois o ECP pode lançar um app, mas precisa de um receptor que saiba interpretar o link, exibir o vídeo e tratar os comandos.

Por isso:
- Esta implementação funciona **somente se o ReiAnix Receiver já estiver instalado e o ECP da TV permitir o comando**. Ela não instala o receptor automaticamente na TV.
- Para distribuir a mesma experiência a outras pessoas sem modo desenvolvedor, será necessário disponibilizar o **ReiAnix Receiver na loja oficial da Roku**. Um APK Android ou uma PR no GitHub não substitui a distribuição na TV.
- A Roku pode bloquear comandos ECP originados de aplicativos de terceiros conforme suas regras e versão de firmware; verificação real numa TV é obrigatória.
- Alguns codecs MKV/MP4 podem não ser suportados pela TV. Não há transcodificação. A velocidade das capas/metadados da Biblioteca não foi alterada.

## Como diagnosticar

- **Roku não aparece:** TV/celular devem estar na mesma rede e não pode haver isolamento de clientes Wi-Fi; aguarde a varredura de até 3 segundos.
- **Receptor não instalado:** a transmissão falha com mensagem explicativa; a Roku encontrada na rede não implica que já tenha o app instalado.
- **Roku não responde (HTTP 403 etc.):** verifique as opções de controle por apps móveis nas configurações da Roku; alguns firmwares restringem ECP.
- **Vídeo não inicia:** tente primeiro arquivo MP4 H.264/AAC; confirmação de comando HTTP 200 não garante decodificação no aparelho.
- **Para a transmissão:** botão Transmitir → Parar transmissão Roku ou notificação Android.

## Arquitetura

- `RokuEcpClient`: descoberta SSDP (on-demand); consulta `/query/apps` para conferir que o aplicativo `dev` se chama `ReiAnix Receiver`; envio do link por `/launch/dev`; controles via ECP; consulta de estado de reprodução `/query/media-player`.
- `RokuCastController`: coordena sessão, pausa local, publica arquivo, transfere reprodução para a TV, monitora estado e restaura reprodução local após desconexão/falha.
- `LocalCastMediaServer`: compartilha o mesmo servidor de bytes HTTP com o Chromecast, de forma restrita e com URL aleatória.
- `CastStreamingService`: mantém o arquivo disponível durante transmissão, mesmo com tela bloqueada.
- O botão Google Cast existente, tamanho/alinhamento e recursos da Biblioteca permanecem intactos.

## Validação
- Build APK e testes ECP no GitHub Actions.
- Testar em Roku físico: descobrir, abrir vídeo MP4 compatível, pausar/retomar, seek, troca de episódio, parar transmissão, reabrir o app, manter servidor no Android.
- **Compilação e testes unitários não comprovam reprodução real na Roku.**

Fontes:
- https://developer.roku.com/dev/docs/external-control-api
- https://www.home-assistant.io/integrations/roku
- https://forum.developer.roku.com/t/how-to-handle-cast-video-playback-support-on-roku/10307
