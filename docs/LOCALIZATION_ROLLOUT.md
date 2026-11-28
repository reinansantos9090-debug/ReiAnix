# Localização do ReiAnix — trabalho em andamento

Idiomas: pt-BR (fallback), en, es, ru, ja. Usar recursos Android e stringResource
apenas na apresentação; não traduzir IDs, chaves de persistência, contratos,
nomes de arquivos ou valores de navegação.

Esta etapa cobre a navegação inferior, boas-vindas e categorias/estados de
carregamento da tela de ajustes. O seletor utiliza LocaleManager do Android 13+;
no Android 7–12, os recursos seguem o idioma do sistema. Ainda não há seletor
manual nessas versões. Não há dependência de tradução em rede.

Antes de liberar os idiomas, migrar e revisar todas as demais telas, player,
notificações, diálogos, erros dinâmicos, pluralização, acessibilidade e busca
de ajustes; implementar seleção manual compatível com API 24–32; verificar
tamanhos de texto, persistência e preservação dos fluxos existentes.
Sinopses de fontes externas são tratadas separadamente.
PR em rascunho; não considerar a tradução completa.
