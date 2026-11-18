# ReiAnix Receiver para Roku (Roku OS)

O botão **Transmitir** do player oferece duas tecnologias diferentes:

- **Chromecast / Google Cast:** usa o SDK Google Cast já existente, sem receptor Roku.
- **Roku:** descoberta SSDP e comandos ECP (HTTP 8060), com este canal **ReiAnix Receiver** instalado na Roku. Roku não é um receptor Google Cast.

## Como instalar o receptor de desenvolvimento

1. Conecte Roku e Android à **mesma rede local Wi-Fi**, sem isolamento entre clientes.
2. Ative o **modo desenvolvedor** na Roku. Na tela inicial, use as teclas do controle: **Home ×3, Cima ×2, Direita, Esquerda, Direita, Esquerda, Direita**. Aceite o contrato, anote o IP e defina a senha de desenvolvedor.
3. Gere o ZIP deste canal com o conteúdo de `roku-channel` na raiz do arquivo (ou use o artefato **ReiAnix-Roku-Receiver** do GitHub Actions): `manifest`, `source/main.brs` e `components/MainScene.xml` / `MainScene.brs`.
4. No navegador de um computador conectado à mesma rede, abra `http://IP_DA_ROKU`, entre com usuário `rokudev` e a senha criada, envie o ZIP no **Development Application Installer** e instale.
5. Em **Configurações > Sistema > Configurações avançadas do sistema > Controle por apps móveis** da Roku (nome sujeito a modelo/versão), habilite controle por aplicativos, caso necessário.
6. No ReiAnix, abra um **vídeo local**, toque **Transmitir**, escolha a Roku encontrada e aguarde a TV abrir o ReiAnix Receiver.

**Limitações reais:** o receptor instalado é de desenvolvimento, não uma publicação na Roku Streaming Store. A Roku aceita somente formatos e codecs suportados por seu modelo; a transmissão não faz transcodificação. Arquivos MP4/MKV compatíveis podem funcionar, mas alguns MKV, áudios e legendas não serão suportados. É necessário manter o celular na mesma rede e o serviço de transmissão ativo. O progresso é consultado periodicamente em `query/media-player`; quando a Roku não disponibiliza esse estado, o celular mostra uma estimativa até a próxima confirmação. Um HTTP 200 ao lançar o canal confirma o comando, **não** a decodificação do arquivo.

## Arquitetura

- `RokuEcpClient`: encontra dispositivos por SSDP, verifica `/query/apps` para assegurar que o canal `ReiAnix Receiver` está instalado como app de desenvolvimento `dev`, envia `launch/dev` e `input`.
- `LocalCastMediaServer`: expõe exclusivamente o arquivo local selecionado, pela rede local, com URL imprevisível e suporte a byte ranges; não acessa bibliotecas ou faz buscas.
- `CastStreamingService`: mantém o servidor ativo em primeiro plano até a transmissão terminar. A notificação permite interromper a transmissão.
- `components/MainScene.*`: recebe a URL e usa o Video node da Roku; controles básicos enviados via ECP.

Documentação oficial: https://developer.roku.com/dev/docs/external-control-api e https://developer.roku.com/dev/docs/developer-setup .

## Verificação necessária em hardware

1. Ver Roku descoberta, instalação ausente tratada sem travar e retry funcionando.
2. MP4 compatível do armazenamento local, assistir até o fim, pausar/retomar, avançar, voltar, encerrar pela notificação, retornar ao player.
3. Trocar de episódio durante transmissão; mudar Wi-Fi; bloquear a tela; sair do app e retornar.
4. Garantir que Chromecast continue disponível no mesmo botão e que capas/metadados da Biblioteca não tenham mudanças.

**Não considerar hardware validado apenas pelo CI Android.** A execução real precisa de Roku OS com o receptor instalado.
