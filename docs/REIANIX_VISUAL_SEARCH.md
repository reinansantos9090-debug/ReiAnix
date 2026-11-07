# ReiAnix — identificação por busca visual de thumbnails

## Fluxo no aplicativo

1. O ReiAnix reutiliza a thumbnail JPEG que já foi gerada para o episódio.
2. Envia essa mesma imagem ao Trace.moe, serviço especializado em encontrar cenas de animes.
3. Quando o Google Cloud Vision Web Detection estiver configurado, envia a mesma thumbnail em paralelo para obter possíveis rótulos e títulos encontrados na Web.
4. O ReiAnix consulta o AniList usando esses textos. Uma sugestão do Google só vira candidata quando corresponde exatamente a um título ou sinônimo de um anime retornado pelo AniList.
5. O resultado passa pela política existente de confirmação de identidade. Uma correspondência forte do Trace.moe pode manter o caminho rápido de um único episódio; uma sugestão proveniente apenas da busca web precisa aparecer em pelo menos duas thumbnails de episódios diferentes.
6. Depois da identificação confirmada, o AniList continua sendo a fonte dos dados de anime, sinopse e capas. SQLite continua como fonte de verdade da biblioteca local.

O aplicativo **não extrai quadros adicionais**, não percorre o vídeo para criar amostras novas e não envia o arquivo de vídeo. O único conteúdo visual enviado por esses serviços é a thumbnail JPEG existente.

## Por que há duas fontes

O Trace.moe é a fonte principal: sua API foi criada para encontrar o anime e a cena a partir de uma imagem. A busca Web Detection do Google é genérica. Ela pode encontrar páginas, imagens correspondentes, entidades e rótulos, mas não é um reconhecedor especializado de cenas de anime e não garante que cada thumbnail tenha um título útil.

Por isso, o ReiAnix não interpreta a pontuação de entidade do Google como similaridade de imagem. Ele usa apenas os textos devolvidos como pistas, consulta o AniList e exige que o texto seja um título/sinônimo exato. As pistas Google sozinhas precisam de concordância em dois episódios distintos antes de identificar automaticamente a série.

Se nenhuma chave do Google estiver configurada, o aplicativo continua funcionando com o Trace.moe, sem servidor próprio, catálogo vetorial ou configuração adicional.

## Ativar Google Cloud Vision Web Detection (opcional)

1. Crie ou escolha um projeto no Google Cloud.
2. Ative a Cloud Vision API e configure faturamento/limites de uso do projeto.
3. Crie uma chave de API dedicada ao ReiAnix.
4. Restrinja a chave à Cloud Vision API. Configure também a restrição para aplicativos Android com o package name do ReiAnix e o SHA-1 do certificado que assina o APK instalado.
5. Monitore o uso, os erros e os limites de faturamento no Google Cloud.

A chave é fornecida ao processo de build; ela não deve ser commitada no repositório:

```bash
gradle -p android \
  -PreianixGoogleCloudVisionApiKey=YOUR_API_KEY \
  :app:assembleRelease
```

Ou use a variável de ambiente:

```bash
REIANIX_GOOGLE_CLOUD_VISION_API_KEY=YOUR_API_KEY gradle -p android :app:assembleRelease
```

O aplicativo envia os cabeçalhos de identificação Android (nome do pacote e SHA-1 do certificado instalado) para que a restrição Android da chave possa ser aplicada. A chave ainda pode ser extraída de um APK; a restrição de aplicativo, a restrição de API e o monitoramento reduzem o risco, mas não tornam uma chave embutida em cliente um segredo inviolável. Use uma chave dedicada e limites de orçamento/uso.

A configuração padrão da chave é vazia. Sem a chave, não há chamadas à Cloud Vision e o Trace.moe continua ativo.

## Custos e privacidade

A Web Detection é uma chamada faturável do Cloud Vision. Consulte a [página oficial de preços](https://cloud.google.com/vision/pricing?hl=pt) antes de ativar e confirme os valores e a franquia vigentes no projeto e na região. Não habilite a integração sem configurar faturamento e limites que você aceite.

Com a integração ativa, a thumbnail do episódio é transmitida ao Google para Web Detection. O vídeo completo não é enviado. Não configure a integração se não concordar com esse envio da imagem.

## Limitações esperadas

- O Google pode retornar rótulos amplos, nomes de personagens ou nenhuma pista útil. Só títulos/sinônimos exatos retornados pelo AniList são aproveitados.
- A API do AniList pode não retornar o anime desejado para uma pista genérica. Isso não é tratado como confirmação.
- O Trace.moe é especializado em cenas de anime e deve continuar sendo a fonte visual principal.
- Nenhum serviço garante identificação para todas as thumbnails, especialmente imagens cortadas, muito pequenas, editadas, de conteúdo recente ainda não indexado ou que não correspondam ao material suportado pelo serviço.
- O build e os testes automatizados não substituem testes reais com thumbnails conhecidas no aparelho. Uma chave Google real também é necessária para validar a integração em produção.

## Referências oficiais

- [Trace.moe — sobre e escopo do serviço](https://trace.moe/about)
- [Google Cloud Vision — Web Detection](https://cloud.google.com/vision/docs/detecting-web?hl=pt-BR)
- [Google Cloud — restrições de chaves de API](https://cloud.google.com/docs/authentication/api-keys)
- [Google Cloud Vision — preços](https://cloud.google.com/vision/pricing?hl=pt)
