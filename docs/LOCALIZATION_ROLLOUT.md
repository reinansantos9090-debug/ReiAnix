# Localização do ReiAnix — em andamento

Idiomas: pt-BR (fallback e qualificador explícito), en, es, ru, ja.
Recursos nativos Android; não traduzir IDs, chaves de persistência,
contratos, nomes de arquivos ou valores de navegação.

## Escopo já migrado parcialmente

- Navegação, boas-vindas, Home, biblioteca, busca, detalhes, Minha Lista.
- Configurações, títulos das seções, opções de seleção, confirmação de ações.
- Player Compose, diálogos nativos do player, notificações de lançamentos.
- Perfil, editor de avatar/papel de parede, coleções, linha do tempo,
  organização, jornada, armazenamento, gerenciador SAF e recuperação.
- Partes da autorização Trace.moe, conta AniList, backup protegido e licenças.

Android 13+ utiliza LocaleManager. API 24–32 usa override persistido e
contexto de configuração nas Activities. A seleção manual recria a Activity.
Os recursos de idiomas são mantidos no AAB para seleção offline.
Não altera a configuração global nem os dados da biblioteca.

## Validação e pendências

Verificação estática das chaves dos recursos e dos placeholders posicionais:
545 chaves de tradução, sem faltantes ou duplicatas nos arquivos
de recursos inspecionados. Isso não comprova a cobertura de toda a interface.

Ainda há textos fixos em Kotlin, incluindo mensagens dinâmicas, estados,
rótulos de acessibilidade, valores formatados, gêneros, títulos internos,
erros e textos de serviços. É necessário revisar toda a superfície de UI,
pluralização, fallback, layout, busca das configurações e lifecycle.
Sinopses externas e documentos legais são tratados separadamente.
As execuções oficiais anteriores falharam e os logs não ficaram acessíveis
(HTTP 404 BlobNotFound). Não houve compilação ou validação física confirmada.
PR em rascunho: não considerar a tradução concluída e não mesclar ainda.
