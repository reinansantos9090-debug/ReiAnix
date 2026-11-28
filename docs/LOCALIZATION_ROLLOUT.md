# Localização do ReiAnix — em andamento

Idiomas: pt-BR (fallback e qualificador explícito), en, es, ru, ja.
Recursos nativos Android; preservar IDs, chaves persistidas, contratos, nomes de arquivos e rotas.

## Escopo migrado nesta PR

Navegação, início, biblioteca, busca, detalhes locais e AniList, Minha Lista,
configurações, diálogos e mensagens do player Compose e nativo, legendas,
faixas de áudio, temporizador, transmissão para TV, cenas favoritas,
notificações de lançamentos, conta, perfil e personalização,
coleções, filtros, gêneros, jornada, armazenamento SAF, backup e recuperação.

Android 13+ utiliza LocaleManager. API 24–32 usa override persistido e
contexto de configuração nas Activities. A seleção manual recria a Activity.
Os recursos de idiomas são mantidos no AAB para seleção offline.
A mudança de idioma não deve alterar dados da biblioteca nem preferências
globais do sistema. Esses comportamentos ainda requerem validação em dispositivo.

## Verificação estática

Em cada arquivo de idioma: 1.009 chaves traduzidas, sem duplicatas,
sem chaves faltantes e sem divergências nos placeholders posicionais.
O recurso padrão conserva também sete constantes e valores de fallback
intencionais (nomes dos idiomas, nome do aplicativo e configuração Google).
As referências R.string introduzidas no diff foram conferidas contra o recurso padrão.
Há também recursos plurais para contagens e mensagens dinâmicas.

## Bloqueios e pendências

O build Android do commit d488d3dd02daa714b717409116d4c2b512b3ce59
(https://github.com/reinansantos9090-debug/ReiAnix/actions/runs/46138766355)
falhou após um segundo, sem etapas registradas; o endpoint de logs
retornou HTTP 404 BlobNotFound. O workflow de compilação Kotlin também falhou.
A causa não pôde ser confirmada. Não houve execução local da compilação
nem testes em dispositivo. Não desabilitar os controles de CI.

Ainda existem textos fixos fora dos recursos, incluindo mensagens de serviços,
rótulos de acessibilidade, alguns valores formatados e títulos de domínio.
A quantidade de chaves não equivale a 100% da interface.
Sinopses externas e documentos legais exigem tratamento separado.
A PR permanece em rascunho e não deve ser mesclada antes de concluir a cobertura
e obter validação oficial bem-sucedida.
