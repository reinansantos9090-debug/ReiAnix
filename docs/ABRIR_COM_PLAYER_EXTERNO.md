# Player — Abrir com aplicativos externos

O ReiAnix organiza vídeos locais e permite escolher outro aplicativo Android para reproduzir um episódio, sem transmissão integrada no player.

## Como usar

1. Abra um anime e entre no player do ReiAnix.
2. Toque em **Opções (⋮)**, depois em **Abrir com...**, logo abaixo de **Gestos**.
3. Escolha qualquer aplicativo compatível listado pelo Android, como **Web Video Caster**, **Castify**, **VLC** ou outro player instalado.
4. Se escolher um aplicativo de transmissão, a descoberta e seleção da TV acontecem dentro desse aplicativo.

O canto inferior esquerdo contém **Bloquear**, no lugar do antigo botão **Transmitir**; o cadeado foi removido do topo. O desbloqueio do player continua disponível quando a tela estiver bloqueada.

## Implementação e segurança

- O seletor é o `Intent.ACTION_CHOOSER` do Android, contendo um `ACTION_VIEW` com MIME `video/*`.
- Mídia `content://` (SAF/MediaStore) é encaminhada com `FLAG_GRANT_READ_URI_PERMISSION` e `ClipData`.
- Caminhos `file://` são convertidos para `content://` por `FileProvider` não exportado; nenhum arquivo é copiado ou modificado.
- O ReiAnix não escolhe automaticamente um app e exclui seu próprio receptor do seletor, evitando uma abertura recursiva.
- O player local pausa ao abrir o seletor. A biblioteca, as capas, os metadados e sua velocidade não são alterados.
- O Google Cast/Chromecast integrado, seu servidor HTTP e o receptor antigo foram removidos.

## Limitações

- O Android só mostra apps que declararam suporte a vídeos no seletor.
- A capacidade de reproduzir MKV/MP4, interpretar a URI e transmitir à Roku depende do aplicativo escolhido, da rede e dos codecs da TV; não há transcodificação pelo ReiAnix.
- A compatibilidade real deve ser confirmada no celular com os aplicativos desejados. Testes automatizados validam o contrato Android, não uma conexão com TV real.
