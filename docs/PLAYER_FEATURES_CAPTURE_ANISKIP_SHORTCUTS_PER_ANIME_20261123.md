# Player ReiAnix — capturar cena, AniSkip, atalhos e preferências por anime

Implementação planejada e autorizada em 23/11/2026. Alterações apenas na PR, sem merge.

## Capturar imagem (4)

- Aparece no menu principal, em **Capturar imagem**, ou opcionalmente na barra de atalhos.
- Captura sob demanda a imagem do vídeo local no instante atual, sem overlays.
- Decodifica e compacta JPEG com limite de aproximadamente 1920×1080, somente em `Dispatchers.IO`.
- Android 10+: salva em `Pictures/ReiAnix` por MediaStore, de maneira compatível com scoped storage, sem permissões extras.
- Android 7–9: abre o seletor do sistema para que o usuário escolha onde guardar a imagem.
- Uma captura não é iniciada durante troca de episódio, cena favorita, fonte indisponível ou com captura pendente.
- Sem extração periódica nem decodificação no primeiro frame. Arquivos temporários são limpos.
- Captura e salvamento bem-sucedidos não exibem avisos temporários. Falhas técnicas continuam informadas sem alterar a reprodução.

## AniSkip (9)

- **Detectar abertura e encerramento** fica em Configurações > Player > Reprodução (não ocupa o menu do player).
- **Ativado por padrão**, persistido pela chave canônica `player.aniskip_enabled` no SQLite.
- Desligar cancela consultas pendentes e esconde somente segmentos remotos;
  marcadores manuais de abertura/encerramento continuam funcionando.
- Reativar permite uma nova consulta em segundo plano quando a duração do episódio está disponível.

## Personalizar atalhos (10)

- Nova página no menu, selecionando até **5** atalhos; botão **Opções** obrigatório.
- Padrão original e na mesma ordem: Bloquear, Episódios, Próximo episódio, Opções.
- Outros: Capturar imagem, Velocidade, Legendas, Temporizador.
- Ativar insere o atalho no **topo**; desativar devolve-o à lista de inativos.
- Os atalhos ativos podem ser reorganizados segurando e arrastando; sem setas de subir/descer.
- O editor de atalhos não mostra os seis pontinhos: segure em qualquer área do nome de um atalho ativo para arrastar; o interruptor fica fora da área de arraste.
- Durante o arraste, o item recebe fundo claro translúcido com cantos arredondados, leve escala e sombra animadas; ao soltar, retorna suavemente ao lugar.
- Ao mudar a ordem, os demais itens deslizam suavemente até suas novas posições, sem movimentar Opções nem permitir arrastar atalhos desativados.
- **Opções** permanece fixo na última posição (4ª ou 5ª) inclusive com preferências antigas.
- Com 5 ícones, os centros permanecem igualmente espaçados e na mesma linha; o conjunto é deslocado para a esquerda com margens assimétricas (1/64 da largura inicial, 3/64 da largura final) para acompanhar a área do vídeo. **Opções** continua na 5ª posição, à direita do grupo.
- Capturar imagem e os atalhos extras recebem escala visual específica no modo de 5 ícones para não parecerem maiores que os quatro ícones já aprovados.
- Com 4 atalhos, dimensões, margens, escalas e ordem continuam exatamente como antes.
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

1. Começar com quatro botões (sem alteração visual), ativar quinto e verificar centros igualmente espaçados, escala da câmera normalizada, alinhamento horizontal, deslocamento leve do grupo à esquerda e Opções como 5º ícone à direita.
2. Retirar um botão (exceto Opções), ativar outro (vai ao topo), segurar o nome (não os seis pontinhos), arrastar e conferir fundo claro, sombra, transição dos vizinhos e encaixe ao soltar. Confirmar que tocar no switch não arrasta, depois fechar/reabrir e verificar persistência.
3. Desligar AniSkip nas configurações, reabrir o player e confirmar que marcador remoto desaparece e o manual segue disponível.
4. Capturar imagem durante reprodução local e conferir JPG na galeria sem os controles.
5. Com salvamento automático ligado por padrão, alterar velocidade 1,25×, áudio japonês, legenda portuguesa amarela no anime A; testar episódios seguintes, fechar/reabrir e voltar ao mesmo anime.
6. Abrir anime B e verificar preferências independentes. Desligar salvamento por anime em Configurações, confirmar uso das gerais, reativar e confirmar que os registros anteriores voltam a ser usados.
7. Testar episódio sem faixa portuguesa: fallback sem crash ou áudio errado.
8. Verificar PiP, Voltar, playlist, trocar de episódios, progresso de reprodução,
   velocidade das capas e aparecimento inicial dos controles.

Os testes Kotlin/JVM manuais não são disparados por este trabalho; o workflow
oficial de PR pode executar seus próprios checks.

O salvamento bem-sucedido de cenas favoritas também não exibe toast de confirmação.

## Ajustes posteriores de 24/11/2026 — PR #438

- No menu principal, **Personalizar atalhos** aparece imediatamente antes de **Velocidade**; **Capturar imagem** aparece imediatamente depois de **Outras opções**.
- Para eliminar tremidas, o arraste mantém a ordem visual original enquanto o dedo se move. Apenas as opções vizinhas deslizam para abrir espaço; nenhuma lista é reordenada a cada movimento.
- A posição prévia usa histerese para evitar oscilações ao cruzar a metade de uma linha, com limite que impede deslocar **Opções**.
- Ao soltar, o item se acomoda e a nova ordem é persistida numa única atualização, sem salto intermediário; o fundo claro, a elevação e o switch independente são mantidos.
- Validação em aparelho: arrastar repetidas vezes em ambos os sentidos, alternar perto do limite entre posições, conferir encaixe sem cruzamento/tremida, testar switches e confirmar a ordenação do menu.
