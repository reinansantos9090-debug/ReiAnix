# Base visual híbrida do ReiAnix

## Arquitetura implementada no aplicativo

Cada thumbnail JPEG já gerada pelo ReiAnix é consultada em paralelo por:

1. **Trace.moe público:** permanece ativo como fonte independente de identificação.
2. **Índice remoto próprio:** uma instalação HTTPS separada, compatível com a API de pesquisa do Trace.moe. Ela é consultada ao mesmo tempo que a API pública; nunca é um fallback sequencial.
3. **Índice visual local:** uma base persistente no aparelho, consultada em paralelo com as fontes remotas.

Os resultados válidos são agrupados por ID AniList, preservando a melhor similaridade por anime. Em seguida, o ReiAnix aplica a política progressiva de consenso que já existia. Nenhum resultado sozinho altera a proteção das associações manuais nem substitui a fonte de verdade SQLite.

### Índice local do aparelho

O índice local fica em:

\`filesDir/data/visual-index/references.json\`

Ele armazena somente o ID AniList já confirmado, o descritor visual MPEG-7 Color Layout de 33 coeficientes da thumbnail e a data de inclusão. O descritor divide a imagem em uma grade 8×8, converte as médias de cor para Y/Cb/Cr, aplica DCT, varredura zig-zag e quantização. Ele não armazena os vídeos e não extrai novos quadros.

Uma referência só é adicionada **depois** que o ReiAnix confirma e grava a associação usando o consenso visual existente. O índice mantém até 5.000 referências recentes; a busca usa a soma dos quadrados das diferenças (L2 ao quadrado, sem pesos) sobre os 33 coeficientes e o mesmo mapeamento da API (`(255 - distância²) / 255`), aceitando correspondências com pontuação local de pelo menos 0,90. Imagens sem detalhe suficiente são ignoradas, em vez de gerar palpites.

A extração do descritor segue a implementação pública trace.moe-id (MIT). A distância e a normalização local seguem a métrica L2 ao quadrado e a fórmula usadas pela API/Milvus upstream. Pequenas diferenças ainda podem ocorrer por causa do redimensionamento/corte da imagem no Android versus Sharp no servidor.

Este índice local aprende com as correspondências que já foram confirmadas naquele aparelho. Ele acelera reconhecimentos repetidos ou imagens visualmente muito próximas, mas não contém, na instalação inicial, um catálogo universal de todas as cenas de anime. O índice remoto é necessário para a pesquisa ampla de cenas ainda desconhecidas pelo aparelho.

### Execução paralela e falhas

- Trace.moe público, índice remoto próprio e índice local começam a consulta em paralelo assim que a thumbnail utilizável fica disponível.
- Os dois serviços remotos recebem a mesma thumbnail JPEG já produzida pelo ReiAnix. O arquivo de vídeo não é enviado.
- Uma fonte que retorna resultado positivo pode contribuir mesmo que outra esteja indisponível.
- Resultados de diferentes fontes são combinados por ID AniList; a pontuação mais alta do mesmo ID é mantida.
- Uma resposta vazia somente é armazenada como ausência de correspondência quando todas as fontes configuradas concluem a consulta. Uma falha parcial sem resultado positivo permanece pendente para nova tentativa.
- Cancelamento de coroutine continua sendo propagado; não é convertido em resultado vazio.
- O cache de respostas por SHA-256 é separado do índice Color Layout local. Ele possui limite de 200 entradas e validade de sete dias para resultados positivos e seis horas para ausência de correspondência confirmada. Consultas incompletas não são gravadas no cache. A versão do cache foi incrementada ao migrar do formato de hash antigo para o descritor Color Layout.

## Configuração do índice remoto próprio

O endereço é injetado no APK por:

- Propriedade Gradle: \`reianixSelfHostedTraceMoeBaseUrl\`
- Variável de ambiente equivalente: \`REIANIX_SELF_HOSTED_TRACE_MOE_BASE_URL\`
- Valor: origem/base path HTTPS da instalação, sem \`/search\`, query ou fragmento.

Exemplo:

\`\`\`bash
gradle -p android \\
  -PreianixSelfHostedTraceMoeBaseUrl=https://visual-index.example.net \\
  :app:assembleRelease
\`\`\`

**O endereço de exemplo não é um servidor real.** O valor padrão continua vazio porque esta alteração não tem acesso a uma conta de hospedagem, servidor remoto ou credenciais de implantação. Sem uma URL real apontando para uma instância funcional, o APK consulta Trace.moe público e o índice local aprendido no aparelho; a fonte remota independente não fica ativa.

### Como disponibilizar a base remota

O serviço upstream é usado como motor de consulta vetorial, mas o fluxo do ReiAnix importa descritores MPEG-7 Color Layout pré-calculados e metadados compatíveis com a API. Não envia vídeos do telefone e não extrai quadros dos arquivos locais durante a consulta. O diretório `VIDEO_PATH` permanece vazio, pois existe somente como requisito do processo upstream.

No servidor Linux, a ordem segura é:
1. iniciar PostgreSQL, etcd, MinIO e Milvus;
2. iniciar a API privada; na primeira inicialização, o servidor upstream cria o esquema PostgreSQL ausente usando o `sql/1.init.sql` da própria imagem;
3. importar um dump de **dados** compatível com a tabela `files`, incluindo descritores pré-calculados e IDs AniList;
4. preencher os metadados AniList, filtrar todas as entradas adultas ou sem classificação explícita, carregar os vetores e executar o verificador antes de publicar o HTTPS.

A PR não contém o dump de dados nem cria a hospedagem. Consulte `infrastructure/visual-index/README.md` para os comandos completos, validações e variáveis de ambiente. O carregador upstream procura linhas com `loaded IS NULL`; quando uma falha marca linhas como `loaded=false`, uma nova execução não as retoma automaticamente. Não repita uma carga parcialmente falha sem reconciliar os vetores já inseridos no Milvus.

Referências de código upstream:
- https://github.com/soruly/trace.moe
- https://github.com/soruly/trace.moe-api
- https://github.com/soruly/trace.moe-id

A configuração operacional pronta para revisão está em `infrastructure/visual-index/README.md`. Ela mantém os bancos fora da internet e inclui um filtro obrigatório para remover registros AniList marcados como adultos antes da carga no Milvus. O servidor precisa ser provisionado em uma máquina controlada pelo mantenedor; esta PR não publica um endpoint.

A disponibilidade do código ou de um dump público não concede automaticamente direitos sobre os vídeos ou imagens indexados. Antes de alimentar ou redistribuir uma base, é necessário verificar as licenças, os termos aplicáveis e a autorização para usar os dados.

## Contrato HTTP remoto

O aplicativo envia apenas a thumbnail JPEG, em multipart/form-data no campo \`image\`, para:

\`\`\`
POST {BASE_URL}/search?anilistInfo&cutBorders=1
\`\`\`

A resposta deve ser compatível com o formato da API pública, contendo um array \`result\` com \`anilist\` (ID numérico ou objeto com \`id\`) e \`similarity\` entre 0 e 1. A instância deve estar acessível por HTTPS.

## O que esta alteração não afirma

- Não existe servidor próprio publicado ou ativo nesta PR.
- Não existe dump universal incluído no APK ou no repositório do ReiAnix.
- O índice local do aparelho não substitui a base remota universal; ele aprende apenas com thumbnails de associações confirmadas. Embora use o mesmo tipo de descritor Color Layout publicado pelo Trace.moe, não contém o catálogo remoto de quadros nem a mesma cobertura.
- Um build aprovado não prova a precisão da busca com uma base remota real. A validação ponta a ponta exige provisionar o servidor, importar/indexar os dados autorizados, configurar a URL no build e testar com thumbnails reais.
