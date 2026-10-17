# Migração da Jornada do Colecionador para Kotlin

## Comportamento anterior observado

A implementação em Python (core/collector_journey.py e core/library_service.py) reconstrói a jornada a partir da consulta canônica de episódios associados aos títulos da biblioteca. Ela calcula 1 XP por episódio concluído; nível a cada 10 XP; títulos do colecionador; conquistas por quantidade de episódios/animes, coleção local, gêneros, décadas, formatos, dias de consumo, maratona por título e duração concluída.

Um episódio é considerado concluído quando watched é verdadeiro ou quando o consumo atinge pelo menos 90% de uma duração positiva. Uma atividade conta como dia ativo quando watched é verdadeiro ou o progresso atinge pelo menos 5% da duração positiva. Um título só conta como anime concluído quando todos os episódios disponíveis associados a ele estão concluídos. Os gêneros, o ano e o formato usados nas métricas derivam dos metadados canônicos do anime.

XP, nível, estatísticas, títulos desbloqueados e conquistas são projeções reconstruíveis e não devem ser persistidos separadamente. A única preferência explícita da jornada é collector.active_title. Ao encontrar um título salvo bloqueado ou inexistente, a implementação anterior seleciona o primeiro título desbloqueado e reconcilia a preferência.

## Implementação Kotlin

- ReiAnixSqliteLibraryRepository.collectorSnapshot consulta episodes e anime numa única leitura do SQLite canônico, mantendo o filtro de ausência e a ordenação da implementação anterior.
- ReiAnixCollectorJourneyCalculator calcula conclusão, atividade, XP/níveis, estatísticas, conquistas e títulos sem mutações ao banco.
- ReiAnixCollectorJourneyService usa ReiAnixSqliteSettingsRepository para ler, gravar ou remover somente collector.active_title. Não cria tabela, arquivo, cache permanente nem repositório paralelo; as operações são serializadas com Mutex.
- ReiAnixAppDependencies compartilha o serviço através das mesmas instâncias de ReiAnixSqliteLibraryRepository e ReiAnixSqliteSettingsRepository usadas pelos demais serviços nativos.
- ReiAnixCollectorJourneyViewModel expõe loading, resultado confirmado, salvamento e erro via StateFlow. Falhas não convertem a jornada em vazia nem confirmam uma gravação não persistida.
- ReiAnixCollectorJourneyRoute e ReiAnixNavigation registram a tela Compose. “Abrir Collector” navega diretamente pelo Navigation Compose e não solicita que Python crie/troque a superfície visual.
- A tela observa mudanças emitidas pela projeção nativa da biblioteca e por Continuar Assistindo, além de oferecer atualização explícita. Não usa polling nem inicia uma varredura.

## Persistência e compatibilidade

A preferência collector.active_title mantém a chave e os valores já usados. Todas as métricas e conquistas são derivadas; não há alteração do schema SQLite v29. Se a consulta falhar, o ViewModel preserva a última jornada válida ou informa indisponibilidade sem produzir valores fictícios. A seleção de título é confirmada no SQLite antes de atualizar o estado da UI.

## Consumidores Python preservados

O caminho Android Compose não chama a jornada Python. A projeção compose_navigation_changed inclui collector para manter a rota lógica do controlador Python alinhada, sem renderizar outra tela Flet. O ramo Flet de CollectorView é guardado para ambientes de fallback onde o host Compose não está disponível.

core/collector_journey.py, views/collector_view.py e os métodos antigos em core/library_service.py permanecem para o fallback Flet e os contratos de regressão desktop. Não são fonte de verdade para a tela nativa Android. A remoção definitiva desses módulos depende da retirada global do runtime Flet e da comprovação de que não há outros consumidores.

## Testes e verificação

- Os testes de regressão existentes da semântica Python foram preservados para o caminho Flet legado.
- tests/test_compose_cutover.py verifica a rota Compose e que o fallback Flet fica guardado.
- ReiAnixCollectorJourneyCalculatorTest.kt contém casos Kotlin/JVM de limiar de conclusão, progresso de atividade, agregação por anime e desbloqueio de gênero; permanece para execução manual e não foi executado nesta tarefa.
- tests/test_prompt20_collector_journey_migration.py verifica contratos da consulta canônica, persistência, rota e ausência de armazenamento alternativo.
- A CI existente executa a suíte de regressão Python e compila/empacota o APK conforme o workflow. Nenhum teste Kotlin/JVM foi executado manualmente; nenhum teste instrumentado/emulador nem workflow novo foi criado.

## Limitações

O runtime híbrido e o fallback Flet permanecem no repositório; este prompt não remove Python/Flet/Flutter globalmente. A validação final de compilação e artefatos depende do workflow associado ao commit definitivo.
