# ReiAnix — Inventário Python após o Prompt 1.3/9

**Snapshot de `main` após a migração aditiva do schema v29.**

## Situação

- **Arquivos `.py` removidos nesta etapa: 0.** Os módulos de persistência e domínio mantêm consumidores Flet/desktop e scripts de build/validação; removê-los agora quebraria rotas que ainda não têm paridade Kotlin completa.
- **Último inventário integral verificado:** 218 arquivos `.py` no commit-base `22181a30cc6f9489b50fc75b69fbefa69a0fc324` (raiz 2, `core/` 31, `views/` 6, `scripts/` 15, `tests/` 164). Esta etapa não remove nem adiciona arquivos Python, portanto os totais ficam inalterados até uma nova contagem integral.
- **Persistência migrada nesta etapa:** inicialização de instalação Android nova, inclusão aditiva de tabelas/colunas faltantes compatíveis, criação/verificação de índices e constraints no arquivo canônico, normalização do status AniList, registro v29 e recuperação de varreduras interrompidas. O banco e os dados existentes não são substituídos.
- **Persistência ainda dependente de Python:** contrato de schema gerado pelos workflows a partir de `core/library_store.py`; `LibraryStore` continua como owner para jornadas legadas que ainda usam Python e no gerador de contrato.
- **Domínio parcialmente migrado antes desta etapa:** operações nativas de consulta, indexação SAF, favoritos/fixados/notas/tags, identificação manual, AniList, progresso e estado assistido já usam o repositório/serviços Kotlin compartilhados. A paridade completa de `core/library_service.py` e dos seus consumidores Flet não está concluída.
- **Dependências diretas de `core/library_store.py` e `core/library_service.py` preservadas:** consultas e mutações do catálogo, scanner e ingestão legados, produção de artwork, hidratação de metadados e AniList nos caminhos Flet, backup/recovery legados, ponte de snapshots Compose e a geração do contrato SQLite continuam exigindo esses módulos.
- **Testes/workflows:** nenhum teste foi adicionado/removido e nenhum workflow de testes foi alterado; os workflows existentes continuam incluindo a geração Python do contrato e a regressão da fonte.

## Critério para remover os módulos

Remover `core/library_store.py`, `core/library_service.py` ou seus imports só após migrar todos os produtores/consumidores das operações e efeitos colaterais, verificar a paridade do schema/backup, e substituir a geração Python do contrato por uma definição e verificação nativas independentes. A meta global Python-zero segue pendente.
