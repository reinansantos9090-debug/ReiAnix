# Preparação de segurança e permissões — Google Play

## Declaração sugerida para READ_MEDIA_VIDEO (verificar no APK/AAB final)
O ReiAnix é uma biblioteca e reprodutor de vídeos locais. O usuário pode conceder acesso frequente à coleção de vídeos para indexar episódios, gerar miniaturas, atualizar a biblioteca quando arquivos mudam e reproduzir os itens. A seleção pontual de um arquivo não permite a mesma atualização contínua da coleção MediaStore. O acesso por pasta SAF e acesso parcial continuam disponíveis; negar acesso não impede o uso do escopo concedido. Não solicitar MANAGE_EXTERNAL_STORAGE nem READ_MEDIA_IMAGES. Demonstrar o fluxo real de onboarding em vídeo na declaração; a aprovação não é garantida.

## Segurança de dados — inventário a confirmar na Play Console
- Local: catálogo, progresso, listas, perfil, preferências, miniaturas, arquivos e backups ficam no dispositivo; exportações voluntárias podem ir para o provedor escolhido pelo usuário.
- AniList: termos de busca, IDs, metadados; quando conectado, ID/nome/avatar, credencial e progresso/estado sincronizados. Finalidade: funcionalidade e gestão de conta externa.
- Kitsu/AniMap: títulos e IDs de catálogo; MyMemory: sinopses e códigos de idioma; AniSkip: IDs, número/duração. Todos usam HTTPS.
- Trace.moe: miniaturas JPEG e IP, após consentimento específico, desativado por padrão; pode ser revogado. A autorização fica fora dos backups lógicos. Não enviar vídeos completos a essa API.
- Cast: consultar a ficha de dados do SDK na versão efetiva; o fornecedor informa coleta de atividade do app e diagnósticos. A transmissão local usa HTTP privado, não declarar que todas as transferências são criptografadas sem avaliar o escopo da pergunta. Web Video Caster recebe URI/título por ação do usuário.
- Suporte: reianix.oficial@gmail.com. Conta externa não é criada pelo ReiAnix; desconectar e limpar dados locais não apaga a conta AniList. Documentar retenção e exclusão.
- Revisar classificação indicativa real, conteúdo gerado por usuários e público-alvo: filtro de novas respostas adultas não certifica capas históricas, arquivos locais ou classificação.
- Conferir permissões do Manifest mesclado, componentes exportados, SDKs e dados efetivamente transmitidos; preencher formulário com base no AAB final e nas definições de coleta/compartilhamento e exceções, sem presumir respostas.

## Autenticação
AniList documenta implicit grant e authorization code com client secret; não introduzir segredo no APK. Mantém AES-GCM/Android Keystore, nonce descartável, validação de callback e HTTPS sem redirect. Migrar para fluxo moderno somente com suporte oficial a cliente público/PKCE ou backend apropriado.

## Verificação obrigatória
Testar SAF e MediaStore com acesso total/parcial/negado; abrir vídeos externos e bloquear arquivos privados; Cast/DLNA, Roku pelo Web Video Caster, desconexão; opt-in/revogação Trace.moe; adult=true em respostas de catálogo; backup ZIP existente e envelope .rxb com senha válida, inválida, corrupção e truncamento. Conferir políticas públicas no ReiAnix-Legal antes de publicar. O HTTP global no Manifest permanece para compatibilidade de receptores LAN; restringir conexões externas nos clientes. O filtro de catálogo não elimina dados já persistidos. A análise estática não substitui testes em aparelho.

## Backup protegido
Disponível em Dados e Backup, Android 8+. Cria cópia .rxb de ZIP válido com AES-256-GCM em registros autenticados, sal aleatório e PBKDF2-HMAC-SHA256 (600.000 iterações). Recupera ZIP autenticado para restauração tradicional. O ZIP original continua sem criptografia. Nunca persistir senha; limpar temporários privados e validar arquivo salvo. Não substituir o mecanismo de recuperação de emergência existente. O novo formato requer revisão criptográfica e testes antes de release.

Fontes oficiais:
- https://support.google.com/googleplay/android-developer/answer/16558241
- https://support.google.com/googleplay/android-developer/answer/10144311
- https://developers.google.com/cast/docs/android_sender/data_disclosure
- https://docs.anilist.co/guide/auth/
