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
- Pode falhar com mídia protegida/sem decoder válido; avisa o usuário sem alterar a reprodução.

## AniSkip (9)

- Novo switch **Detectar abertura e encerramento** no menu principal.
- **Ativado por padrão**, persistido pela chave canônica `player.aniskip_enabled` no SQLite.
- Desligar cancela consultas pendentes e esconde somente segmentos remotos;
  marcadores manuais de abertura/encerramento continuam funcionando.
- Reativar permite uma nova consulta em segundo plano quando a duração do episódio está disponível.

## Personalizar atalhos (10)

- Nova página no menu, selecionando até **5** atalhos; botão **Opções** obrigatório.
- Padrão original e na mesma ordem: Bloquear, Episódios, Próximo episódio, Opções.
- Outros: Capturar imagem, Velocidade, Legendas, Temporizador.
- Permite ativar/desativar e ordenar os atalhos com comandos de subir/descer.
- Layout com largura proporcional, sem mexer no centro, seekbar ou no player.
- Persistência na linha canônica `player.shortcuts` com validação de IDs
  sem duplicação, teto de 5 e menu sempre acessível.
- No modo paisagem os ícones seguem sem rótulos, preservando o espaço do vídeo.

## Preferências por anime (13)

- Nova página **Configurações deste anime** com opção **Salvar para este anime**;
  se desligada, o comportamento continua utilizando as preferências gerais.
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
- **Restaurar configurações globais** remove apenas o registro desse anime.
- Dados de progresso, vídeos, capas, biblioteca e schema do banco ficam intactos.

## Validação essencial em aparelho

1. Começar com quatro botões e ativar quinto; verificar alinhamento na horizontal.
2. Retirar um botão (exceto Opções), trocar a ordem, fechar/reabrir e confirmar persistência.
3. Pular abertura com AniSkip desligado (marcador remoto oculto) e confirmar que marcador manual segue disponível.
4. Capturar imagem durante reprodução local e conferir JPG na galeria sem os controles.
5. Ativar perfil do anime A: velocidade 1,25×, áudio japonês, legenda português
   amarela; testar episódios seguintes, fechar/reabrir e voltar ao mesmo anime.
6. Abrir anime B, verificar uso das preferências gerais, restaurar A e confirmar
   que B e o restante do aplicativo não mudaram.
7. Testar episódio sem faixa portuguesa: fallback sem crash ou áudio errado.
8. Verificar PiP, Voltar, playlist, trocar de episódios, progresso de reprodução,
   velocidade das capas e aparecimento inicial dos controles.

Os testes Kotlin/JVM manuais não são disparados por este trabalho; o workflow
oficial de PR pode executar seus próprios checks.
