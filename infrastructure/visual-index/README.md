# ReiAnix — servidor de índice visual próprio

Esta pasta contém a infraestrutura de referência para hospedar o endpoint remoto que o aplicativo chama em paralelo com Trace.moe. Ela usa uma instalação privada compatível com a API de busca do Trace.moe (`/search`), com Caddy terminando HTTPS e os bancos permanecendo somente na rede interna do Docker.

## Componentes

- Caddy: HTTPS público, encaminhando somente `POST /search`.
- API de pesquisa: motor que extrai/consulta descritores MPEG-7 Color Layout.
- PostgreSQL: metadados e descritores comprimidos.
- Milvus: pesquisa vetorial.
- etcd e MinIO: dependências do Milvus.

Nenhuma porta de PostgreSQL, Milvus, etcd ou MinIO é publicada no host. Somente 80/443 do Caddy são expostas.

## Requisitos

Use uma máquina Linux dedicada, com Docker Engine e Docker Compose plugin, domínio próprio apontado para o IP público e armazenamento persistente. A capacidade necessária depende do tamanho do catálogo. Um índice completo do porte do Trace.moe é muito pesado: a documentação upstream relata aproximadamente 140 GB de RAM para carregar todo o dump publicado em memória, além do tempo de reindexação/otimização. Não dimensione uma hospedagem pequena supondo que o catálogo completo caberá nela.

O serviço não está implantado automaticamente por esta PR. É necessário ter um servidor, domínio, DNS e credenciais de hospedagem sob controle do mantenedor do ReiAnix.

## Preparar o servidor

1. Copie `.env.example` para `.env`.
2. Aponte `REIANIX_INDEX_DOMAIN` para um domínio cujo DNS A/AAAA chegue ao servidor.
3. Gere senhas exclusivas para `TRACE_API_SALT`, `POSTGRES_PASSWORD` e `MINIO_SECRET_KEY`; não reutilize as credenciais de exemplo.
4. Valide a configuração com `docker compose config --quiet`.
5. Crie a pasta indicada por `VIDEO_PATH` e mantenha-a vazia nesta configuração baseada em descritores pré-calculados. O ReiAnix não envia vídeos para esse servidor.
6. Inicie apenas os bancos e a API, mantendo o proxy público desligado durante a importação: `docker compose up -d postgres etcd minio milvus api`.

Caddy solicitará e renovará o certificado TLS automaticamente quando o DNS e as portas 80/443 estiverem corretamente configurados.

## Popular a base visual

A configuração do ReiAnix usa um catálogo de descritores MPEG-7 Color Layout pré-calculados, não vídeos. O dump de referência precisa conter registros compatíveis com a tabela `files` (incluindo `anilist_id`, `media_info`, `scene_changes` e `color_layout`). O arquivo de dados não acompanha esta PR.

1. Com os serviços internos iniciados e o Caddy ainda desligado, importe o dump SQL dos descritores. Por exemplo, se o mantenedor tiver um dump compatível em `./data/visual-index.sql.zst`:

```bash
zstd -dc ./data/visual-index.sql.zst | docker compose exec -T postgres psql -U postgres -d postgres -v ON_ERROR_STOP=1
```

2. Preencha a tabela AniList para os IDs referenciados pelos registros importados. O script consulta metadados em lotes de até 50 IDs, incluindo a propriedade `isAdult`:

```bash
bash ./hydrate-anilist-metadata.sh
```

3. Aplique o filtro de conteúdo geral. Ele mantém somente registros cujo AniList declara explicitamente `isAdult=false`; itens adultos, sem classificação ou com classificação inválida são removidos:

```bash
docker compose exec -T postgres psql -U postgres -d postgres -v ON_ERROR_STOP=1 < filter-adult-entries.sql
```

4. Recarregue somente os vetores já pré-calculados no Milvus. Isso não executa FFmpeg nem cria vetores a partir de vídeos:

```bash
docker compose exec -T postgres psql -U postgres -d postgres -v ON_ERROR_STOP=1 -c 'UPDATE files SET loaded = NULL;'
docker compose exec -T api node script/bulk-load-milvus.ts 500
```

Acompanhe a saída do processo e confira se todos os registros elegíveis foram marcados `loaded=true`. Só depois de concluir essa etapa exponha o endpoint HTTPS:

```bash
docker compose up -d caddy
```

O ReiAnix envia apenas a thumbnail JPEG que já gerou no aparelho. A pasta `VIDEO_PATH` fica vazia e `SCAN_INTERVAL=0`; não há envio de vídeo nem reextração de quadros no dispositivo.
## Configurar o ReiAnix

Quando o endpoint HTTPS estiver realmente ativo e a pesquisa tiver sido validada, construa o APK com a origem/base URL (sem `/search`, sem query e sem fragmento):

```bash
gradle -p android \
  -PreianixSelfHostedTraceMoeBaseUrl=https://visual-index.seu-dominio.example \
  :app:assembleRelease
```

Ou defina `REIANIX_SELF_HOSTED_TRACE_MOE_BASE_URL` no ambiente de build. O app envia a mesma thumbnail JPEG que o pipeline já gerou ao Trace.moe público e ao servidor próprio em paralelo; não envia o arquivo de vídeo.

## Verificação operacional

- `curl -i https://SEU_DOMINIO/search` deve retornar `405` para GET (a rota aceita POST).
- A API deve responder a uma thumbnail JPEG real com JSON contendo `result`, `anilist` e `similarity`.
- Verifique que um resultado vazio é retornado somente quando a base está carregada e a busca conclui normalmente.
- Monitore a memória, disco, fila do Milvus, logs, backups e certificados TLS.
- Não exponha os serviços PostgreSQL/Milvus/etcd/MinIO diretamente à internet.

## Limites desta pasta

- Não contém vídeos nem um catálogo de anime pré-carregado. O índice pré-calculado precisa ser importado pelo mantenedor.
- Não contém credenciais reais, domínio, servidor ou dump de dados.
- Não faz deploy em nuvem: isso requer a conta e o servidor do mantenedor.
- Não certifica a precisão do índice; é necessário testar com thumbnails reais conhecidas antes de configurar o APK de produção.

## Referências de implementação

- API upstream e seu contrato de busca: https://github.com/soruly/trace.moe-api (licença MIT; a infraestrutura não copia o código-fonte para o APK)
- Descritor Color Layout usado no cliente: https://github.com/soruly/trace.moe-id
