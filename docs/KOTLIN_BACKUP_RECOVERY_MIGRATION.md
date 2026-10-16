# Migração Kotlin — Backup, restauração e recuperação da biblioteca

## Escopo desta etapa

- Repositório: `reinansantos9090-debug/ReiAnix`.
- Base canônica: `filesDir/data/library.sqlite3`.
- Contrato persistido: SQLite schema v29.
- Formato de backup compatível: ZIP `rei-flix-backup-v1`, `format_version = 1`.
- Implementação nativa: `ReiAnixBackupService`, `MainActivity`, `ReiAnixSqliteDatabase` e a rota Compose `ReiAnixRecoveryRoute`.

A etapa reutiliza o formato e os serviços existentes; não cria outro banco operacional, catálogo, formato de exportação principal ou arquitetura paralela de persistência.

## Dados presentes no backup v1

O manifesto existente é mantido. A cópia lógica da base contém as tabelas canônicas elegíveis ao contrato de backup, com registros e IDs persistidos, associações AniList, animes, episódios/observações, favoritos, Minha Lista (pins), tags/notas, metadados, gêneros, configurações permitidas, histórico e progresso de reprodução. O serviço remove dados de autenticação/conta e registros transitórios `scan_runs` do snapshot exportado, limpa referências de conta das pastas e mantém apenas as preferências explicitamente exportáveis.

Capas manuais portáveis podem acompanhar o arquivo ZIP quando a referência e o conteúdo estiverem disponíveis e validados. Vídeos, logs temporários, caches regeneráveis de artwork e thumbnails não são incluídos. Caminhos locais e URIs persistidas continuam sendo referências; o backup não copia o vídeo e não recria grants de SAF ou permissões de MediaStore.

O manifesto mantém versão, schema, contagens, inventário de artwork e checksums SHA-256 existentes. A integridade estrutural e os hashes não equivalem a prova de origem/autenticidade criptográfica.

## Criação e validação de backup

O serviço Kotlin cria um snapshot consistente com `VACUUM INTO`, sanitiza a cópia temporária, valida `PRAGMA integrity_check`, `PRAGMA foreign_key_check`, schema v29 e invariantes de preferências/consumo, então produz o ZIP temporário. O ZIP é inspecionado e seus membros/manifesto/checksums são validados antes da publicação. O seletor de destino usa `ActivityResultContracts.CreateDocument` e a escrita de destino usa `ContentResolver`, portanto o fluxo não pressupõe um caminho físico para um destino SAF.

A interface só apresenta sucesso depois que a criação interna e a gravação do documento selecionado terminam sem exceção. Na exportação Compose, o destino SAF é reaberto para leitura e o conteúdo é comparado por tamanho e SHA-256 com o ZIP temporário validado; se o provedor não permitir leitura de retorno ou o conteúdo divergir, a operação retorna erro em vez de anunciar sucesso. A criação não inclui arquivos de mídia externos e não sobrescreve o backup interno anterior com um nome idêntico: o serviço escolhe um nome interno exclusivo.

## Restauração normal

O fluxo Compose seleciona um documento ZIP por `OpenDocument`. O arquivo é copiado por fluxo para uma área temporária com limite de bytes e sincronização de escrita. `inspectBackup` valida o ZIP, os nomes dos membros, a versão, o schema, os hashes e o banco importado. Antes de iniciar tanto a restauração normal quanto a recuperação, a interface mostra os metadados e contagens do arquivo escolhido e exige confirmação específica desse arquivo; cancelar mantém o catálogo sem alterações.

Quando o banco ativo está saudável e compatível, a restauração lógica permanece transacional: a implementação anexa o snapshot ao SQLite canônico, substitui as tabelas na ordem de integridade referencial dentro de uma única transação, reconecta capas manuais e verifica integridade, foreign keys, preferências e invariantes antes do commit. Um backup inválido é rejeitado antes da transação; uma falha anterior ao commit não é apresentada como sucesso. O caminho normal preserva o snapshot pré-restauração ZIP produzido pelo próprio serviço.

## Recuperação de banco ausente, corrompido ou incompatível

A inicialização nativa inspeciona o arquivo canônico preexistente fora da thread principal. Uma instalação nova sem banco não é classificada automaticamente como falha. Se um banco não vazio não puder ser aberto ou não corresponder ao contrato SQLite atual, o shell navega para a rota Compose de recuperação, sem expor uma restauração Python nesse fluxo Android.

A tela apresenta confirmação explícita antes de selecionar o backup. A recuperação usa `restoreRecoveryBackup`, pois o fluxo normal não consegue criar um backup pré-restauração a partir de uma base que já falha na verificação.

Sequência nativa de recuperação:

1. Inspecionar o ZIP e validar todos os membros obrigatórios antes de tocar no caminho ativo.
2. Criar um diretório `recovery-snapshot-*` em `data/backups`, copiando byte a byte o arquivo SQLite ativo (se existir), `-wal`, `-shm`, `-journal` e remanescentes de promoção interrompida. O manifesto do snapshot registra nomes, tamanhos e SHA-256, e os arquivos copiados são conferidos contra a origem.
3. Extrair `library.sqlite3` e capas manuais válidas em staging, validar o banco temporário e reconectar as referências de artwork no staging.
4. Copiar o candidato validado para um arquivo temporário adjacente ao banco canônico e sincronizar a escrita.
5. Isolar os sidecars ativos para impedir que um WAL/journal anterior seja associado ao novo arquivo; mover o banco anterior para um nome temporário; promover o candidato por rename no mesmo diretório.
6. Validar novamente integridade SQLite, foreign keys, schema v29, invariantes e contrato nativo de esquema. Só então a rota sai de Recovery Mode.
7. Se uma etapa lançar uma exceção, tentar colocar a substituição inválida em quarentena, devolver o banco anterior e recolocar os sidecars. O snapshot de segurança é mantido para diagnóstico mesmo quando a recuperação falha.

O diretório `recovery-snapshot-*` é uma cópia bruta de segurança e **não** é um arquivo de backup ZIP restaurável por `restoreBackup`. Ele serve para preservar os bytes anteriores/sidecars; a recuperação operacional usa um backup v1 validado. Remanescentes ocultos de promoção não são tratados como uma biblioteca vazia e entram no próximo snapshot.

O backup v1 continua excluindo a tabela `account` e não exporta credenciais/tokens. Para manter compatibilidade com o Recovery Mode legado, o caminho Kotlin tenta ler as linhas locais `account(key,value)` do banco atual antes de substituí-lo e reaplica essas linhas ao staging quando a tabela é legível. Se a tabela ou o SQLite estiverem ilegíveis, o processo continua sem inventar uma conta e informa que pode ser necessário autenticar novamente.

## Recuperação de artwork e arquivos externos

A recuperação usa apenas membros de arte manual declarados no manifesto e autorizados pelo validador. Nomes de membros são normalizados e rejeitam caminhos absolutos, `..`, entradas duplicadas, symlinks, tamanhos excessivos e arquivos não permitidos. O hash e o tamanho da arte são verificados antes da publicação; o staging só atualiza referências para os caminhos que o próprio serviço gerou.

O serviço não inventa caminhos físicos para URIs `content://`, não recria permissões do Android e não apaga registros só porque um arquivo externo está indisponível. A reconciliação subsequente preserva referências URI/SAF/MediaStore para os scanners existentes. Para caminhos locais comuns, a verificação pode marcar disponibilidade; isso não implica que o vídeo esteja incluído no ZIP.

## Concorrência e SQLite

As operações de backup, restauração, integridade e reconciliação usam a função de manutenção exclusiva de `ReiAnixSqliteDatabase`, que sincroniza com leitores/escritores Kotlin que compartilham a instância canônica e invalida a validação em cache do contrato após a manutenção. O bloqueio não cria uma nova conexão persistente e não modifica o schema.

A coordenação com writers Python legados não é garantida por esse monitor Kotlin. Durante a recuperação Android, o caminho Flet não executa a operação de restauração; a lógica Python `RecoveryService`/ `RecoveryView` é preservada apenas como fallback desktop legado. A aplicação permanece híbrida e a migração não remove globalmente Python/Flet/Flutter nesta etapa.

## Testes e validação

Foi adicionado `tests/test_kotlin_backup_recovery_contract.py` para cobrir a ordem das validações e promoção, preservação do snapshot, lock canônico e integração da rota nativa. O teste de contrato entra na suíte Python automatizada existente. Testes Kotlin/JVM continuam sob execução manual e não são acionados manualmente nesta etapa; nenhum teste de emulador/instrumentado ou novo workflow instrumentado foi adicionado.

A compilação Android e os testes automatizados ainda precisam ser certificados pelo workflow obrigatório associado ao SHA final da branch/PR. Esta documentação, sozinha, não declara o build aprovado, execução runtime em dispositivo ou publicação dos artefatos.

## Limitações conhecidas

- Só são aceitos o formato ZIP v1 e o schema SQLite v29 que o serviço valida atualmente; uma versão desconhecida é rejeitada em vez de interpretada como compatível.
- Permissões SAF/MediaStore e mídia externa não podem ser reconstituídas só pelo backup; pode ser necessário conceder acesso novamente.
- O snapshot bruto de recuperação preserva arquivos e checksums individualmente, não representa uma transação atômica entre o banco ativo e todos os sidecars caso outro processo externo continue escrevendo simultaneamente. O caminho Android de Recovery Mode evita chamar o writer Python de recuperação, mas a aplicação geral ainda tem consumidores Python legados para outros subsistemas.
- A promoção de vários arquivos não é uma transação filesystem global. A implementação faz rollback explícito para falhas detectadas e mantém os snapshots; encerramento abrupto do processo entre renames é detectado pela presença de remanescentes no próximo startup, não declarado como rollback instantâneo.
- A verificação de build não prova que a aplicação inteira já é independente de Flet/Python/Flutter nem substitui testes de restauração em dispositivo com dados reais.
