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
5. Crie a pasta indicada por `VIDEO_PATH` se for indexar uma coleção própria autorizada.
6. Inicie a infraestrutura: `docker compose up -d`.
5. Crie a pasta indicada por `VIDEO_PATH` se for indexar uma coleção própria autorizada.
6. Inicie a infraestrutura: `docker compose up -d`.

Caddy solicitará e renovará o certificado TLS automaticamente quando o DNS e as portas 80/443 estiverem corretamente configurados.

## Popular a base visual

A API upstream consegue construir um índice a partir de arquivos de vídeo organizados em subpastas pelo ID AniList dentro de `VIDEO_PATH`. Use somente material que você tenha autorização para indexar e mantenha essa coleção adequada a todas as idades.

Também é possível importar um dump de descritores MPEG-7 Color Layout em formato compatível com as tabelas `files` e `anilist` do servidor. O arquivo de dump não acompanha o ReiAnix. Antes de carregar qualquer vetor no Milvus, importe os metadados, execute o filtro de segurança abaixo e só então solicite a carga dos vetores.

### Filtragem obrigatória antes de carregar o Milvus

O banco precisa estar inicializado pelo serviço API. Importe apenas um dump de metadados/descritores compatível e que você tenha autorização para usar; por exemplo, se o arquivo estiver disponível no host em `./data/visual-index.sql.zst`:

```bash
zstd -dc ./data/visual-index.sql.zst | docker compose exec -T postgres psql -U "${POSTGRES_USER:-postgres}" -d postgres -v ON_ERROR_STOP=1
```

Depois de importar o dump no PostgreSQL, execute:

```bash
docker compose exec -T postgres psql -U "${POSTGRES_USER:-postgres}" -d postgres -v ON_ERROR_STOP=1 < filter-adult-entries.sql
```

O script remove os IDs AniList marcados `isAdult=true` e os respectivos registros de vídeo, remove órfãos e falha caso algum registro adulto permaneça. Não pule esta etapa.

Depois da filtragem, acione a reconstrução do índice vetorial:

```bash
printf 'UPDATE files SET loaded = NULL;\n' | docker compose exec -T postgres psql -U "${POSTGRES_USER:-postgres}" -d postgres -v ON_ERROR_STOP=1
```

Acompanhe a fila de indexação nos logs do serviço API e consulte os estados da tabela `files` no PostgreSQL. A busca só deve ser liberada depois que os registros filtrados estiverem carregados no Milvus.

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

- Não contém vídeos nem um catálogo de anime pré-carregado.
- Não contém credenciais reais, domínio, servidor ou dump de dados.
- Não faz deploy em nuvem: isso requer a conta e o servidor do mantenedor.
- Não certifica a precisão do índice; é necessário testar com thumbnails reais conhecidas antes de configurar o APK de produção.

## Referências de implementação

- API upstream e seu contrato de busca: https://github.com/soruly/trace.moe-api (licença MIT; a infraestrutura não copia o código-fonte para o APK)
- Descritor Color Layout usado no cliente: https://github.com/soruly/trace.moe-id
