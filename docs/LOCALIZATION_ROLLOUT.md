# Localização do ReiAnix — trabalho em andamento

Idiomas: pt-BR (fallback), en, es, ru, ja. Usar recursos Android e stringResource
apenas na apresentação; não traduzir IDs, chaves de persistência, contratos,
nomes de arquivos ou valores de navegação.

Recursos migrados: navegação inferior, boas-vindas, categorias/estados de
ajustes, principais rótulos de Home, biblioteca, busca, detalhes, Minha Lista,
controles e menu do player e notificações de lançamento.

Android 13+ utiliza LocaleManager; API 24–32 usa um override persistido
de idioma e contexto de configuração nas Activities, inclusive no player.
A notificação de lançamento usa o contexto localizado. A seleção manual
recria a Activity. Não altera a configuração global nem os dados da biblioteca.
A compatibilidade ainda precisa ser validada em dispositivos e por build.

Pendente: auditoria e migração de textos restantes (inclusive opções de
ajustes, perfil, listas, armazenamento, diálogos, erros dinâmicos,
acessibilidade, busca de ajustes, notificações adicionais e pluralização),
revisão de traduções, layout e lifecycle. Sinopses externas são separadas.
PR em rascunho: não considerar a tradução completa.
