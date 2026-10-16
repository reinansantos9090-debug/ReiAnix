# Migração nativa do Organizar — ReiAnix

## Objetivo e limite

Esta etapa move para Kotlin a identidade/persistência de gêneros e a classificação heurística local usada pela jornada Organizar, mantendo a tela Compose existente e os serviços nativos de armazenamento. A referência do trabalho é a main observada em 23e587d7d8cc87664f8166d0bfec03ec9f739428. O escopo não é uma remoção geral de Python/Flet: ainda existem consumidores legados reais, portanto os módulos Python não são apagados nem têm seus caminhos Flet desativados nesta etapa.

## Componentes nativos

- ReiAnixGenreClassifier porta as sete famílias de regras locais de core/genre_classifier.py, mantendo os nomes em português e a ordem determinística. O classificador é offline, não faz chamadas de rede e não grava por conta própria.
- normalizeReiAnixGenre reproduz a normalização NFKD/acentos, separadores e caracteres especiais da identidade de gênero legada.
- ReiAnixGenreRegistry gerencia registros, aliases e relações anime–gênero nas tabelas já existentes genres, genre_aliases e anime_genres. A inicialização explícita semeia as sete identidades/aliases de sistema e faz backfill idempotente do JSON de gênero presente em anime.genres para a tabela de relações, sem substituir relações de outra origem.
- ReiAnixAppDependencies.genreRegistry compartilha a mesma instância de ReiAnixSqliteDatabase usada pelo repositório nativo. Não há outro arquivo SQLite, repositório concorrente, DDL novo ou alteração do schema v29.
- ReiAnixAniListIntegrationService mantém o cliente AniList existente, sincronizando relações quando uma resposta traz gêneros não vazios. Lista remota vazia não apaga os gêneros já materializados. Se a sincronização das relações falhar depois do commit editorial, o serviço registra a falha sem alegar rollback da operação que já foi confirmada.
- ReiAnixLibraryViewModel expõe uma ação explícita para classificar somente metadados de gênero ausentes. O processo pula obras associadas ao AniList, fontes autoritativas e campos de gênero manuais, e deixa títulos sem correspondência sem rótulos artificiais. A operação usa transação no banco canônico e apresenta estado carregando/sucesso/erro. A seleção usa paginação por ID em lotes e continua avançando sobre registros inelegíveis; o limite configurado controla quantos candidatos elegíveis serão avaliados, não limita previamente o conjunto bruto antes dos filtros.
- ReiAnixOrganizeRoute continua usando os filtros, coleções e modelos Compose existentes; a nova ação é parte da tela nativa, não uma segunda tela de organização.

## Fontes, aliases e preservação

Os aliases de sistema mantêm o mapeamento legado: Action → Ação, Adventure → Aventura, Fantasy → Fantasia, Comedy → Comédia, Horror → Terror e Sports → Esporte. Romance conserva o nome canônico sem alias traduzido. Relações são deduplicadas por ID de gênero e origem; contagens usam animes distintos, não episódios/arquivos.

A classificação automática se restringe a registros sem JSON de gênero e sem relações existentes. Ela não substitui genres preenchido, associações de gênero provenientes de outra origem, IDs AniList, favoritos, pins, etiquetas pessoais, notas, progresso ou identidade dos arquivos. Gêneros inferidos são apenas sugestões locais, e títulos desconhecidos continuam sem gênero.

## Armazenamento e operações de arquivos

O matching de candidatos AniList permanece no ReiAnixAniListMatcher existente. O gerenciador SAF continua sendo SafFileOperations, com validação de árvore/autorização e falhas tipadas. Renomeação e movimentação continuam passando pela reconciliação nativa de identidade no ReiAnixLibraryService antes do evento de atualização de projeção. Esta etapa não substitui o scanner, o SAF, o MediaStore, o serviço de catálogo, o pipeline de artwork ou o player.

O evento native_genres_committed é exclusivamente um sinal para atualizar a projeção atual do catálogo híbrido. Seu handler Python consulta o registro e solicita nova projeção; não repete a escrita de gêneros. Essa ponte de visualização permanece necessária enquanto o runtime híbrido tiver consumidores ativos.

## Validação

- ReiAnixGenreClassifierTest cobre as famílias de regras, título sem inferência, normalização e leitura do JSON legado. O teste foi adicionado ao source set JVM existente, mas não deve ser executado manualmente; a política de testes Kotlin/JVM continua sendo workflow_dispatch.
- tests/test_prompt14_native_organizer_contract.py verifica, no conjunto automático existente, a ligação entre o registry, o banco canônico, a UI Compose e a publicação de eventos sem mutação duplicada no Python.
- Não foram adicionados testes instrumentados/emulador nem alterada a política de workflows.
- A etapa só será validada depois do workflow obrigatório do SHA final terminar com sucesso e dos artefatos reais esperados (APK/ZIP, quando publicados pelo workflow) serem confirmados. Um resultado de outro commit não é evidência para esta mudança.

## Limitações conhecidas e migração futura

core/genre_classifier.py e core/genre_registry.py permanecem para telas/serviços Python ainda ativos. core/collector_journey.py também não é removido nem declarado migrado aqui: métricas de jornada/conquistas são outro consumidor de domínio, distinto dos filtros/coleções da tela Organizar e precisa de migração própria com paridade de dados. A tela Compose e a movimentação de arquivos já eram nativas antes desta etapa; sua existência não é apresentada como implementação nova. O cutover completo do restante do runtime híbrido continua pendente.
