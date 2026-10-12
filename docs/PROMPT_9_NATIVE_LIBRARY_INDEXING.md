# Prompt 9 — indexação nativa e reconciliação segura

`NativeLibraryScanSnapshot` é o contrato explícito entre descoberta e persistência: contém operação, fonte, raiz do escopo, localizações esperadas e processadas, falhas, cancelamento, autorização e estado final. A reconciliação usa uma allow-list de estados completos e exige que todas as localizações esperadas tenham sido processadas sem falhas ou cancelamento.

A verificação de escopo é segment-aware, portanto `primary:AnimeBackup` não pertence a `primary:Anime`. Para SAF, `scopeLocation` contém o Document ID e `location` mantém a referência real do arquivo. Quando o scanner/provider já validou o documento contra a árvore SAF concedida, `scopeVerified` transporta essa prova; não se presume que todo provedor codifique ancestralidade em prefixos de Document ID. Títulos normalizados só ajudam a procurar candidatos; não são identidade universal.

A inserção de episódios requer um único anime com título normalizado exato, número de episódio/temporada ou número absoluto e confiança explícita alta. Se já existir uma linha para o mesmo episódio lógico, o arquivo novo é reportado como ambíguo em vez de substituir arbitrariamente o arquivo existente. Atualizações de localização alteram apenas campos da descoberta e disponibilidade; progresso, watched, last played, notas, favoritos e IDs são preservados.

Uma varredura incompleta ainda pode atualizar itens individualmente verificados, mas nunca marca itens ausentes. A reconciliação só atua em `episodes.source_folder` dentro da raiz verificada, usando limite por segmento. Tudo é executado na transação do repositório SQLite canônico.

## Limite operacional

A API de indexação Kotlin está disponível para o coordenador nativo. A ponte Python/NativeMailbox ainda participa de partes do fluxo legado; a migração não deve ser declarada integralmente nativa até que o fluxo de execução passe por esta API sem escritor Python concorrente.
