# Escolha da fonte de mídia: Navi x ReiAnix

## Problema reproduzido

Quando o mesmo link direto é copiado do Download Navi para o ADM e para o ReiAnix, os gerenciadores conseguem reconhecer o arquivo. Links selecionados anteriormente pelo ReiAnix podem apontar para uma página/player intermediário diferente da URL do vídeo final. **Isso indica uma diferença de escolha da fonte, não apenas uma falha de Content-Length.**

## O que mudou

- O compartilhamento Android agora coleta URLs de Intent.data, EXTRA_TEXT, EXTRA_SUBJECT, ClipData e campos de URL explícitos, não apenas a primeira URL do texto.
- A seleção prioriza URLs explicitamente identificáveis como vídeo (.mp4, .mkv etc), playlists de vídeo e fontes assinadas plausíveis; páginas e gateways vêm depois.
- Quando há várias URLs, o usuário pode escolher a fonte. O aplicativo **não inventa** links alternativos se o compartilhamento contém apenas um intermediário.
- O editor mostra um aviso para páginas reconhecíveis e uma ação para abrir a página no navegador interno, onde a fonte usada pelo player pode ser descoberta legitimamente.
- O navegador interno ordena os links de vídeo diretos antes de gateways e captura URLs de respostas `video/mp4` e equivalentes carregadas por fetch/XHR para players `blob:`, mas só as associa a um player visível de longa duração. Não lê respostas DRM, outras aplicações nem tráfego protegido de terceiros.
- O mecanismo mantém a URL inteira (inclusive parâmetros assinados), usa cookies/Referer da origem observada e **não encaminha credenciais a outro domínio quando o usuário escolhe outra URL**.
- O diagnóstico da fila mostra somente os domínios de origem e do servidor efetivo, código HTTP e fase de falha, sem caminhos, queries, tokens ou cookies.

## Limites e testes necessários

- Um aplicativo externo pode compartilhar só a URL do intermediário; o ReiAnix **não pode extrair automaticamente** a URL da sessão privada de outro aplicativo. Para obter a mídia, ela deve estar em dados compartilhados, na resposta de um redirecionamento HTTP legítimo ou em uma página que o próprio navegador do ReiAnix consiga carregar e observar.
- Extensões e nomes sugerem tipo de fonte, mas não provam autorização nem acesso; a transferência continua validando HTTP e o conteúdo recebido.
- URLs de uso único não são inspecionadas por sondagens extras antes de iniciar a transferência.
- Testes unitários verificam ordenação, URLs assinadas, destinos embutidos e páginas. É necessário testar o mesmo episódio no aparelho, mesma fonte, rede e sessão para confirmar a correção prática.

## Teste manual recomendado

1. Compartilhar o endereço usado pelo Navi, verificar se o ReiAnix o mantém intacto e se reconhece o tamanho.
2. Compartilhar um texto com player e mídia final, verificar o seletor e escolher a URL direta.
3. Abrir uma página no navegador ReiAnix, identificar fontes/qualidades e verificar que o player não é tratado como MP4.
4. Reproduzir vídeo legítimo num player `blob:` e verificar se a URL de vídeo observada aparece no seletor quando a página usa fetch/XHR com tipo `video/*`.
5. Testar o caso de 0 B; copiar o diagnóstico e comparar os hosts, sem compartilhar tokens.

Não promete equivalência universal com o ADM, acesso a sessões de outros apps ou contorno de autenticação.
