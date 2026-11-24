# Player ReiAnix — AniSkip, atalhos e preferências por anime

Histórico do player. Atualização de 24/11/2026: a captura de imagem foi removida por solicitação do usuário; os cinco atalhos abaixo passaram a ser o padrão.

## Remoção da captura de imagem

- A função de extrair frames e salvar JPEG foi excluída do aplicativo, incluindo menu, atalhos, callbacks, seletor de arquivos e implementação dedicada.
- Imagens já exportadas pelo usuário para a galeria não foram apagadas.
- **Salvar cena favorita** é independente e continua disponível.
- Preferências legadas com o atalho `capture` são normalizadas sem esse item, preservando os demais atalhos selecionados.

## AniSkip (9)

- **Detectar abertura e encerramento** fica em Configurações > Player > Reprodução (não ocupa o menu do player).
- **Ativado por padrão**, persistido pela chave canônica `player.aniskip_enabled` no SQLite.
- Desligar cancela consultas pendentes e esconde somente segmentos remotos;
  marcadores manuais de abertura/encerramento continuam funcionando.
- Reativar permite uma nova consulta em segundo plano quando a duração do episódio está disponível.

## Personalizar atalhos (10)

- Nova página no menu, selecionando até **5** atalhos; botão **Opções** obrigatório.
- Padrão de cinco ícones na mesma ordem solicitada: Bloquear, Episódios, Velocidade, Próximo episódio, Opções.
- Atalhos alternativos: Legendas e Temporizador. Velocidade pode ser desativada ou reorganizada.
- Ativar insere o atalho no **topo**; desativar devolve-o à lista de inativos.
- Os atalhos ativos podem ser reorganizados segurando e arrastando; sem setas de subir/descer.
- O editor de atalhos não mostra os seis pontinhos: segure em qualquer área do nome de um atalho ativo para arrastar; o interruptor fica fora da área de arraste.
- Durante o arraste, o item recebe fundo claro translúcido com cantos arredondados, leve escala e sombra animadas; ao soltar, retorna suavemente ao lugar.
- Ao mudar a ordem, os demais itens deslizam suavemente até suas novas posições, sem movimentar Opções nem permitir arrastar atalhos desativados.
- **Opções** permanece fixo na última posição (4ª ou 5ª) inclusive com preferências antigas.
- Com 5 ícones, os centros permanecem igualmente espaçados e na mesma linha; o conjunto é deslocado para a esquerda com margens assimétricas (1/64 da largura inicial, 3/64 da largura final) para acompanhar a área do vídeo. **Opções** continua na 5ª posição, à direita do grupo.
- Velocidade e os atalhos extras usam escala visual específica quando há cinco ícones.
- Com 4 atalhos selecionados pelo usuário, as dimensões e margens próprias do layout de quatro são preservadas.
- Sem mexer no centro, seekbar ou no player.
- Persistência na linha canônica `player.shortcuts` com validação de IDs
  sem duplicação, teto de 5 e menu sempre acessível.
- No modo paisagem os ícones seguem sem rótulos, preservando o espaço do vídeo.

## Preferências por anime (13)

- **Configurações individuais por anime** fica em Configurações > Player > Reprodução, ligada por padrão.
- Não aparece mais como opção do menu do player; o salvamento passa a ser automático ao alterar preferências do anime.
- Se desligada, usa as preferências gerais sem apagar registros por anime já salvos.
- Salvas por ID numérico existente do anime, na mesma tabela SQLite `preferences`
  com chave `player.anime.<id>` e versão de dados para compatibilidade futura.
- Salva as alterações feitas durante a sessão: velocidade, proporção, autoplay,
  zoom, modo somente áudio, seleção da faixa de áudio, seleção de legendas,
  idioma, habilitar/desabilitar legendas e aparência (fonte, cor, tamanho,
  posição vertical e estilos incorporados).
- Faixas preferidas são salvas semanticamente por idioma, rótulo e MIME,
  **nunca por posição numérica da faixa**, pois o índice pode variar no próximo episódio.
- Quando a faixa exata não existir, utiliza a preferência por idioma/seleção
  automática do Media3, sem falhar a reprodução.
- Reuso da mesma Activity com `onNewIntent` não carrega opções de outro anime:
  a leitura de preferências é assíncrona e ligada ao ID atual.
- As escritas são serializadas e só o valor mais recente prevalece.
- Dados de progresso, vídeos, capas, biblioteca e schema do banco ficam intactos.

## Validação essencial em aparelho

1. Instalação nova: confirmar 5 ícones na ordem Bloquear, Episódios, Velocidade, Próximo episódio, Opções; validar alinhamento e o menu fixo à direita. Desativar um atalho e confirmar que o usuário pode manter só quatro.
2. Retirar um botão (exceto Opções), ativar outro (vai ao topo), segurar o nome (não os seis pontinhos), arrastar e conferir fundo claro, sombra, transição dos vizinhos e encaixe ao soltar. Confirmar que tocar no switch não arrasta, depois fechar/reabrir e verificar persistência.
3. Desligar AniSkip nas configurações, reabrir o player e confirmar que marcador remoto desaparece e o manual segue disponível.
4. Confirmar que **Capturar imagem** não aparece no menu nem em Personalizar atalhos e que **Salvar cena** continua funcionando.
5. Com salvamento automático ligado por padrão, alterar velocidade 1,25×, áudio japonês, legenda portuguesa amarela no anime A; testar episódios seguintes, fechar/reabrir e voltar ao mesmo anime.
6. Abrir anime B e verificar preferências independentes. Desligar salvamento por anime em Configurações, confirmar uso das gerais, reativar e confirmar que os registros anteriores voltam a ser usados.
7. Testar episódio sem faixa portuguesa: fallback sem crash ou áudio errado.
8. Verificar PiP, Voltar, playlist, trocar de episódios, progresso de reprodução,
   velocidade das capas e aparecimento inicial dos controles.

Os testes Kotlin/JVM manuais não são disparados por este trabalho; o workflow
oficial de PR pode executar seus próprios checks.

O salvamento bem-sucedido de cenas favoritas também não exibe toast de confirmação.

## Ajustes posteriores de 24/11/2026 — PR #438

- No menu principal, **Personalizar atalhos** aparece imediatamente antes de **Velocidade**; a antiga opção **Capturar imagem** abaixo de **Outras opções** foi removida em 24/11/2026.
- Para eliminar tremidas, o arraste mantém a ordem visual original enquanto o dedo se move. Apenas as opções vizinhas deslizam para abrir espaço; nenhuma lista é reordenada a cada movimento.
- A posição prévia usa histerese para evitar oscilações ao cruzar a metade de uma linha, com limite que impede deslocar **Opções**.
- Ao soltar, o item se acomoda e a nova ordem é persistida numa única atualização, sem salto intermediário; o fundo claro, a elevação e o switch independente são mantidos.
- Validação em aparelho: arrastar repetidas vezes em ambos os sentidos, alternar perto do limite entre posições, conferir encaixe sem cruzamento/tremida, testar switches e confirmar a ordenação do menu.

## Padrão de cinco atalhos e migração (24/11/2026)

- **Restaurar 5 atalhos padrão** redefine a ordem para Bloquear, Episódios, Velocidade, Próximo episódio, Opções.
- Instalações novas recebem cinco ícones; configurações salvas anteriormente (inclusive de quatro ícones) continuam personalizadas.
- Um atalho de captura salvo em versões antigas é eliminado da configuração com os demais atalhos preservados, sem reativação automática de opções desativadas.
- A remoção não apaga nenhum arquivo de imagem que já esteja na galeria.

## Alinhamento do trio de controles centrais (24/11/2026)

- Com 5 atalhos, deslocar Play/Pause e os dois botões de ±10 segundos como **um único grupo**, 1/64 da largura visível à esquerda. Esse é exatamente o deslocamento do centro da terceira célula da barra inferior para o alinhamento com Velocidade (padrão).
- Preservar a distância simétrica entre Play e cada controle de 10 segundos, dimensões, áreas de toque e posições verticais.
- Com 4 atalhos, manter as posições anteriores. O deslocamento acompanha a largura disponível sem valores fixos em pixels.
- Conferir visualmente na paisagem e retrato, inclusive com mudança de orientação, buscando alinhamento dos centros e nenhuma piscada ou alteração dos gestos.

## Centralizar Play/Pause na bolinha azul nativa — retrato (24/11/2026)

- O vídeo do usuário mostra o Play ligeiramente deslocado em relação ao indicador azul de carregamento. Esse indicador nativo é centralizado no PlayerView (Gravity.CENTER), enquanto o trio Compose usava -1/64 da largura também no retrato.
- Com **5 atalhos em retrato**, usar deslocamento horizontal **0** para o grupo de três botões, coincidindo o centro do Play/Pause com o centro geométrico do indicador azul; os dois botões de ±10 segundos acompanham o grupo sem alterar espaçamento, altura, tamanho ou gestos.
- **Paisagem**: preservar exatamente o deslocamento anterior de -1/64. Com **4 atalhos**: preservar o centramento original.
- A PR #441 foi fechada sem merge, então o ajuste anterior de +4 dp não integra a main nem deve ser carregado para esta PR.

## Correção de CI para Robolectric na PR #443 (24/11/2026)

- A build original desta PR falhou **antes de executar testes**, por HTTP 429 do Maven Central durante resolução de org.robolectric:nativeruntime-dist-compat:1.0.16.
- A configuração anterior do espelho Google Cloud abrangia apenas android-all-instrumented. O espelho agora abrange todo o grupo org.robolectric, preservando Google e Maven Central como repositórios existentes para os demais artefatos.
- HEAD do artefato nativeruntime-dist-compat:1.0.16 no espelho respondeu HTTP 200. Nenhum teste foi removido, alterado ou ignorado, sem alterar o código de execução do player.
