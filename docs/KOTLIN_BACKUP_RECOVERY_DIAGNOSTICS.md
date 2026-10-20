# Kotlin nativo — backup, restauração, recuperação e diagnóstico

## Escopo e estado real

O caminho Compose para backup/restauração e recuperação de banco usa
`ReiAnixBackupService`; o relatório de diagnóstico usa
`ReiAnixDiagnosticsService`, ambos em
`android/app/src/main/kotlin/com/reiflix/reiflix_local/data/backup/`.
A integração de seleção/exportação, confirmação e publicação de estado fica em
`MainActivity`, e o Recovery Mode Compose usa
`ReiAnixRecoveryRoute`.

Esses fluxos Kotlin existem e são consumidores reais da tela Compose. Isso não
significa que o aplicativo inteiro já seja independente de Python/Flet/Flutter:
`core/backup.py`, `core/recovery.py`, `core/diagnostic_service.py`,
`core/diagnostics.py` e `core/performance.py` continuam necessários para
telas e caminhos legados ainda ativos.

## Contratos de dados e formato

- Banco canônico: `filesDir/data/library.sqlite3`; schema SQLite v29.
- Formato de transporte: ZIP `rei-flix-backup-v1`, `format_version = 1`.
- O manifesto contém versão de formato, versão do aplicativo, schema, contagens,
  dados de arte manual e SHA-256 por entrada, hash do payload e hash do núcleo
  do manifesto.
- A implementação nativa aceita o formato v1 e o schema v29. Versões de schema
  desconhecidas, mais antigas ou futuras não são tratadas como compatíveis por
  padrão; não há migração Kotlin de schema implementada nesta etapa.
- As entradas são limitadas por nome permitido, caminho normalizado, contagem,
  tamanho, soma de tamanhos descompactados, SHA-256 e validação do banco.
  A entrada `library.sqlite3` pode ocupar o orçamento do arquivo completo
  (até 512 MiB, sujeito também ao limite total do ZIP); outras entradas e capas
  manuais portáteis continuam limitadas individualmente a 64 MiB. O manifesto
  é lido com limite de 8 MiB.

## Dados incluídos e excluídos

O backup contém um snapshot lógico sanitizado do banco canônico: registros e
IDs persistidos cobertos pelo contrato v29, progresso/histórico, associações,
listas e preferências explicitamente exportáveis. O snapshot remove a tabela
`account`, referências `account_id` nas pastas e registros transitórios
`scan_runs`; preferências não permitidas pela fonte canônica de configurações
não são exportadas.

Capas manuais só são incluídas quando o arquivo está dentro do cache gerenciado,
é legível e passa pelos limites e verificações do serviço. Vídeos, thumbs e
caches regeneráveis, segredos de autenticação, tokens e sessão Google não são
incluídos. Caminhos locais e URIs são referências a recursos externos, não cópias
desses recursos. Um backup não recria permissões SAF/MediaStore, não garante que
arquivos externos ainda existam e não restaura grants revogados.

## Criação e consistência SQLite

A criação usa `VACUUM INTO` para gerar uma base autônoma e consistente; não
recorre à cópia simples do arquivo ativo como fallback. O snapshot é sanitizado
em transação e passa por `PRAGMA integrity_check`,
`PRAGMA foreign_key_check`, schema v29 e invariantes de preferências/consumo.
O ZIP é escrito em arquivo temporário, inspecionado e validado antes de ser
publicado. A exportação SAF reabre o destino e compara tamanho e SHA-256 com o
arquivo validado; falha ou divergência não é anunciada como sucesso.

As operações Kotlin usam `ReiAnixSqliteDatabase.withExclusiveFileMaintenance`
quando a instância canônica está disponível. Esse lock serializa as operações
Kotlin que compartilham essa instância e invalida a validação em cache do
contrato após a manutenção. Ele não é, por si só, um mutex compartilhado com
conexões Python independentes; o fluxo nativo de Recovery não chama o writer de
restauração Python, mas a aplicação continua híbrida até a migração global.

## Restauração normal

1. O usuário escolhe um documento por SAF/OpenDocument; cancelar encerra sem
   iniciar restauração. O URI é lido via `ContentResolver`, sem assumir caminho
   físico, e a cópia temporária respeita o limite total de arquivo.
2. `inspectBackup` valida ZIP, membros, manifest, versão/formato, hashes,
   integridade SQLite, constraints, schema e contagens antes da confirmação.
3. A interface exibe metadados e contagens do arquivo específico e solicita
   confirmação explícita.
4. Antes de alterar o catálogo, o serviço cria um backup ZIP de segurança do
   estado atual. A base recebida é extraída em staging e validada novamente.
5. As tabelas são aplicadas ao banco canônico em uma transação SQLite, na ordem
   compatível com as relações. A implementação valida integridade, foreign
   keys, preferências/consumo e invariantes de domínio antes do commit.
6. Uma falha anterior ao commit reverte a transação; o backup de segurança não é
   removido pelo fluxo de restauração. O resultado só é publicado depois de a
   operação retornar.

## Recuperação de banco ausente, corrompido ou incompatível

A rota de recuperação distingue uma instalação nova sem banco de um banco
preexistente que falhou na validação. Um backup ZIP v1 é inspecionado antes de
qualquer promoção. Como a base ativa pode ser ilegível, o serviço mantém um
snapshot bruto de segurança com o banco disponível, sidecars SQLite
(`-wal`, `-shm`, `-journal`) e remanescentes de promoção interrompida,
registrando tamanhos e SHA-256.

O banco e a arte são preparados em staging; o candidato SQLite é copiado para
arquivo adjacente ao destino canônico, sincronizado e validado. Sidecars ativos
são isolados antes da promoção. Depois do rename, integridade, foreign keys,
schema v29 e contrato nativo são revalidados. Em caso de falha, o código tenta
colocar o candidato em quarentena e devolver a base anterior e seus sidecars.
O snapshot bruto permanece disponível para diagnóstico; não é um ZIP importável
por `restoreBackup`.

As linhas locais não secretas da tabela `account` só são preservadas na
recuperação se forem legíveis. Se não forem, o serviço não inventa estado de
conta e sinaliza que uma nova autenticação pode ser necessária.

## Preferências e referências externas

As preferências exportadas são derivadas de
`ReiAnixSettingsContract.definitions` mais `settings.schema_version`, no
banco canônico; não existe segunda fonte persistente de configurações para o
backup. A restauração valida os valores permitidos antes de aplicá-los. Os
caminhos e URIs de episódios continuam sendo referências: a reconciliação nativa
marca disponibilidade de caminhos locais comuns, mas deixa URIs SAF/MediaStore
sob os scanners e grants existentes.

## Diagnóstico e privacidade

`ReiAnixDiagnosticsService.exportJson()` cria o relatório
`reianix-diagnostic-v1`, com versão do app e Android, schema suportado,
integridade SQLite, contagens resumidas de biblioteca/artwork/arquivos,
duplicatas, órfãos, consumo, AniList, player, scans, grants SAF persistidos,
uso de cache, até 100 eventos recentes e métricas agregadas de performance.
A timeline fica limitada a 300 eventos em memória.

O relatório não deve incluir títulos de animes, caminhos brutos de vídeos, URIs
de documentos, tokens, credenciais, estado de autenticação ou identificadores
desnecessários do dispositivo. Erros no diagnóstico são representados como
indisponibilidade/erro; informações não verificadas não devem ser interpretadas
como confirmação de integridade. O arquivo é salvo no destino escolhido pelo
usuário via seletor Android `CreateDocument`.

## Testes, workflows e limites de validação

- `tests/test_kotlin_backup_recovery_contract.py` verifica por contrato a
  ordem de inspeção, snapshot, staging, promoção/revalidação, lock SQLite e
  roteamento Compose da recuperação; agora também protege o limite por tamanho
  separado para a base canônica.
- `tests/test_prompt16_kotlin_diagnostics_contract.py` verifica a integração
  da timeline, exportação privacy-first e serviços de diagnóstico nativos.
- Esses testes de contrato são Python e fazem parte da suíte existente; não
  substituem testes dinâmicos de comportamento da implementação Android.
- Testes Kotlin/JVM não foram executados manualmente nesta etapa e nenhum teste
  instrumentado/de emulador foi adicionado.
- O workflow de build existente mantém o empacotamento híbrido Flet/Flutter e a
  suíte Python. A compilação Android, resultados do CI e execução em dispositivo
  só podem ser declarados após evidência associada ao commit final.
- Os módulos Python de backup/restauração/diagnóstico continuam como fallback
  desktop e consumidores das telas Flet. A remoção global dessas dependências
  não faz parte deste escopo.

## Alterações desta etapa

Esta etapa amplia o limite individual de dados permitido para `library.sqlite3`
até o limite global do arquivo, sem ampliar o limite de 64 MiB de capas manuais
ou de outras entradas; mantém o teto de 512 MiB para o conjunto descompactado.
O teste contratual correspondente foi acrescentado a
`tests/test_kotlin_backup_recovery_contract.py`. Não foi alterado o schema
SQLite, não foi criado banco paralelo e não foram removidos componentes Python
legados.
