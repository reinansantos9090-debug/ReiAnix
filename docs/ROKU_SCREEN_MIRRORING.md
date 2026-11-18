# Transmitir para Roku sem instalação na TV

O ReiAnix oferece **duas opções no mesmo botão Transmitir**:

- **Chromecast / Google Cast:** transmite o arquivo de vídeo para o dispositivo Cast, usando a integração existente.
- **Roku / Espelhamento (Smart View/Miracast):** abre o espelhamento **do próprio Android**. A Roku não é um Chromecast e não aceita o protocolo Google Cast nativamente.

## Como usar (sem ZIP, login ou modo desenvolvedor)

1. Deixe celular e Roku com os recursos de Wi-Fi/espelhamento ativados.
2. Na TV Roku, abra **Configurações > Sistema > Espelhamento de tela** e selecione **Perguntar** ou **Sempre permitir** (a localização exata depende do modelo).
3. Abra um vídeo local no player do ReiAnix e toque **Transmitir**.
4. Selecione **Roku (Smart View/espelhamento)**. O aplicativo poderá localizar a Roku pela rede, mas a **conexão é feita pelo sistema Android**, não por comandos Roku ECP.
5. Nas opções do Android ou no botão **Smart View** do painel de ajustes rápidos do Samsung, escolha sua Roku e aceite a solicitação na TV.
6. Volte ao ReiAnix e reproduza o episódio. O vídeo aparece espelhado na TV enquanto o celular transmite a tela.

Caso o atalho do Android não liste sua Roku, use **Smart View** pelo painel rápido. O ReiAnix não tem permissão pública para iniciar uma sessão Miracast silenciosamente: o sistema exige confirmação do usuário.

## Limitações e compatibilidade

- Este modo **espelha toda a tela**; ele não mantém o vídeo tocando na Roku se você desligar a tela do celular, e não é transmissão independente do arquivo.
- Suporte a Miracast/Smart View depende do **modelo e firmware do Android** e do **modelo Roku**. Apenas encontrar o aparelho pela rede não garante que ele seja espelhável.
- Durante a reprodução espelhada, os comandos de play/pause/avançar permanecem no **player do celular**.
- Não há conversão de arquivos ou receptor Roku embutido. **Nenhum canal Roku, ZIP, senha de desenvolvedor ou login é solicitado.**
- O antigo **PR #363**, que utilizava um canal sideloaded de desenvolvimento, **não é usado** nesta implementação.
- A pesquisa de Roku roda **somente quando o usuário abre o seletor**, nunca na Home, na Biblioteca, no carregamento de capas ou na leitura de metadados.
- A função Chromecast continua utilizando o SDK nativo e o servidor HTTP local existente, sem alteração no fluxo de vídeo.

Se no futuro desejar **envio de arquivo independente para Roku, como o Web Video Caster**, será necessário disponibilizar um receptor próprio na **loja oficial da Roku**, publicar o aplicativo e desenvolver o protocolo de comunicação correspondente. A disponibilidade desse receptor não pode ser substituída por uma PR Android.

Referência: suporte oficial Roku para espelhamento de tela:
https://support.roku.com/pt-br/article/screen-mirror-your-phone-tablet-or-computer
