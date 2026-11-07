# Índice visual independente do ReiAnix

## Estado e arquitetura

O aplicativo mantém a API pública do Trace.moe como primeira fonte de identificação. Quando ela retorna uma lista válida sem correspondências ou falha por transporte/HTTP, o cliente consulta opcionalmente uma instância HTTPS própria compatível com a API do Trace.moe.

A instância própria não é criada nem hospedada por esta aplicação. Ela precisa ser provisionada pelo mantenedor em uma infraestrutura remota. O endereço é injetado no APK pelo Gradle:

- Propriedade Gradle: `reianixSelfHostedTraceMoeBaseUrl`
- Variável de ambiente equivalente: `REIANIX_SELF_HOSTED_TRACE_MOE_BASE_URL`
- Valor: origem/base path HTTPS da instalação, sem `/search`, query ou fragmento.

Exemplo de build:

```bash
gradle -p android \
  -PreianixSelfHostedTraceMoeBaseUrl=https://visual-index.example.net \
  :app:assembleRelease
```

Sem essa configuração, o aplicativo continua usando o Trace.moe público e o cache local; não existe uma instância independente ativa no APK.

## Base e mecanismo

O projeto oficial do Trace.moe documenta um sistema de pesquisa por recuperação de imagem baseada em conteúdo (CBIR), com vetores/características visuais e um índice Milvus. A documentação não descreve isso como um modelo que entende personagens ou como um classificador treinável.

Referências oficiais:
- https://github.com/soruly/trace.moe
- https://github.com/soruly/trace.moe-api
- https://github.com/soruly/trace.moe-id
- https://trace.moe/database

O repositório principal documenta a execução com Docker Compose, o monitoramento de uma pasta de vídeos organizados por AniList ID e a opção de importar o dump pré-processado publicado pelo projeto.

**Requisito de infraestrutura:** a documentação oficial consultada informa que carregar o dump completo (114.514 arquivos na documentação) requer aproximadamente 140 GB de RAM. O carregamento dos hashes no Milvus pode levar cerca de 24 horas e a otimização posterior pode durar alguns dias. Portanto, a base completa não deve ser empacotada nem carregada no celular; ela precisa permanecer em servidor remoto.

A disponibilização do dump e do código upstream não elimina a obrigação de analisar as licenças aplicáveis, termos de serviço e direitos sobre os dados/vídeos que serão indexados. O ReiAnix não redistribui o dump nem vídeos protegidos.

## Contrato HTTP esperado

O app envia somente o JPEG da thumbnail local, usando multipart/form-data no campo `image`, para:

```
POST {BASE_URL}/search?anilistInfo&cutBorders=1
```

A resposta deve ser compatível com o formato público do Trace.moe, contendo um array `result` com `anilist` (ID numérico ou objeto com `id`) e `similarity` entre 0 e 1. O servidor próprio deve estar exposto via HTTPS.

## Comportamento no aplicativo

1. A identificação usa a thumbnail já gerada pelo ReiAnix. O arquivo de vídeo não é enviado.
2. O Trace.moe público é consultado primeiro, mantendo a latência normal quando ele retorna uma correspondência.
3. Se a pesquisa pública não encontrar correspondência ou falhar, a instância independente configurada é consultada.
4. Resultados válidos são guardados em cache local pelo SHA-256 exato da thumbnail. Resultados positivos expiram após sete dias; respostas válidas sem correspondência após seis horas.
5. Falhas simultâneas dos dois serviços não são gravadas como uma resposta válida sem correspondência. O episódio permanece elegível para uma nova tentativa.
6. Os resultados continuam passando pela política de consenso existente do ReiAnix. Esta implementação não altera os limiares, o número máximo de episódios usados, a escolha da capa, as associações manuais ou o SQLite canônico.

O cache é apenas um cache de respostas para imagens idênticas; ele não é uma cópia do índice remoto, não faz busca aproximada local e não torna o reconhecimento geral disponível offline.

## Limitação desta entrega

Esta alteração entrega o cliente Android, a configuração de endpoint, o fallback, o cache local e a documentação de hospedagem. Ela **não provisiona um servidor, não baixa/importa o dump e não hospeda a base**. Enquanto `REIANIX_SELF_HOSTED_TRACE_MOE_BASE_URL` não apontar para uma instância funcional, a identificação independente não poderá ser testada nem usada no dispositivo. O build padrão permanece compatível com o uso do Trace.moe público.
