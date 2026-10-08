# ReiAnix — Backup, Restore e Integridade

## Formato

O ReiAnix usa o formato versionado `rei-flix-backup-v1`. O arquivo é um ZIP validável contendo, no mínimo:

- `manifest.json`
- `library.sqlite3`
- artwork manual portátil somente quando o arquivo original estiver dentro do cache gerenciado pelo aplicativo e puder ser incluído com segurança.

O manifest registra versão do formato, versão do aplicativo, schema SQLite, timestamp, contagens, compatibilidade e hashes SHA-256.

## O que é incluído

O backup protege o estado lógico existente no SQLite: catálogo, episódios, referências persistentes, consumo/progresso/histórico, favoritos, pins, tags, notas, gêneros, relações de gênero, estado de matching AniList, metadata persistida, referências de artwork, pastas configuradas e preferências suportadas pelo `SettingsStore`.

Os vídeos locais não são copiados.

## O que não é incluído

Autenticação da instalação atual não é restaurada. A tabela `account` é sanitizada no snapshot e `folders.account_id` é removido.

Também ficam fora do backup:

- tokens, senhas, cookies e credenciais;
- identificadores específicos do dispositivo;
- IDs transitórios do NativeMailbox;
- logs temporários e registros de execução de scan;
- cache remoto/generated de artwork que pode ser reconstruído;
- arquivos de vídeo originais;
- artefatos de build e arquivos temporários.

O backup funciona offline e não requer Google Login, AniList ou artwork remoto. Artwork manual portátil é tratado como conteúdo opcional: se um membro de capa estiver ausente ou com checksum inválido, a biblioteca lógica ainda pode ser validada/restaurada e a capa pode ser regenerada posteriormente.

## Segurança e atomicidade

O snapshot SQLite é obtido pela API de backup do SQLite, em vez de copiar um arquivo que pode estar no meio de uma escrita.

O ZIP é criado em arquivo temporário, validado e somente depois promovido ao nome final com rename. Um nome já existente não é sobrescrito silenciosamente.

O restore executa pré-validação completa do container e dos checksums. Antes de alterar o catálogo, é criado um backup de segurança `pre-restore-*.zip`. A aplicação depois importa o SQLite dentro de uma transação e executa `quick_check`, `foreign_key_check` e verificação de identidades persistentes antes do commit. No Recovery Mode, se o SQLite atual estiver corrompido e a sessão não puder ser lida, a restauração do catálogo ainda é permitida quando houver snapshot bruto de segurança; nesse caso o login é refeito pelo usuário.

Falhas durante a transação fazem rollback e deixam o estado anterior no SQLite. As operações de backup/restore usam um lock compartilhado para impedir sobreposição perigosa entre operações. A criação verifica espaço livre estimado antes de produzir o ZIP.

## Restore

O modo atual de restore é **REPLACE**: o estado lógico persistido no backup substitui o estado lógico atual. Não existe MERGE neste estágio, portanto não há regras implícitas ou conflitos silenciosos.

Os caminhos físicos dos vídeos não são considerados prova de exclusão. Quando um caminho local não existe no dispositivo no momento do restore, a entidade lógica continua no catálogo e o episódio fica marcado como `missing`. Referências `content://` e outras identidades nativas são preservadas para que MediaStore/SAF/NativeIndex façam a reconciliação existente posteriormente.

O restore não inicia um full rescan automaticamente.

## Artwork

O Artwork Engine continua sendo a única autoridade do cache. Artwork gerado e cache remoto podem ser reconstruídos. Artwork manual que está dentro do cache gerenciado pode ser empacotado e restaurado de forma portátil; referências manuais externas permanecem como referências, sem copiar caminhos arbitrários para dentro do backup.

Após restore, requisições de artwork já enfileiradas recebem uma nova geração para impedir que resultados antigos sobrescrevam o estado restaurado.

## Settings

O backup usa o `SettingsStore` existente e inclui somente as preferências suportadas/exportáveis. O `reianix-compose/settings.json`, quando presente no Android, é apenas uma projeção IPC derivada para a UI Compose; não é fonte de verdade nem armazenamento de preferências.

## Migração e compatibilidade

O formato do container possui versão própria. O repositório atual possui schema SQLite 29 e backup v1; não foram inventadas versões históricas que não existem no projeto. Backups com schema incompatível ou formato futuro são rejeitados explicitamente.

Quando uma nova versão de backup for criada, a implementação deve adicionar uma migração determinística e testável no serviço antes de aceitar o formato.

## Diagnóstico

O relatório técnico local pode ser exportado em JSON. Ele reúne:

- schema e saúde SQLite;
- foreign keys;
- contagens da biblioteca;
- missing files;
- duplicidades de identidade;
- órfãos;
- consumo/progresso inconsistente;
- estado de artwork/cache;
- estado AniList;
- erros recentes do player;
- storage e scan state.

Caminhos são tratados como informação potencialmente privada; o relatório externo não inclui credenciais, autenticação, tokens ou identificadores do dispositivo.

## Operação

A interface fica integrada ao Settings Center:

**Configurações → Backup & Restore**

Operações disponíveis:

- Fazer backup;
- Restaurar backup;
- Verificar integridade;
- Exportar diagnóstico.

Arquivos são escolhidos pelo usuário através do FilePicker/SAF, sem depender de um caminho fixo.

## Limitações atuais

O restore é REPLACE, não MERGE.

A reconciliação de URIs Android depende do fluxo existente de MediaStore/SAF/NativeIndex e não é falsificada pelo serviço Python.

Não existe backup automático remoto ou sincronização em nuvem.

