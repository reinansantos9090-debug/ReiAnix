# Etapa 4 — Android App Bundle para Google Play

## Configuração preservada
- ID com.reianix.app; namespace com.reianix.reianix_local.
- compileSdk/targetSdk 36 (Android 16), minSdk 24, AGP 8.9.1, JDK 17.
- ABI arm64-v8a de release; R8 e redução de recursos ativos na produção.
- A chave estável e o SHA-256 fixado em RELEASE_SIGNING_SETUP.md não mudam.
- O versionCode usa github.run_number do mesmo workflow de APK, evitando dois contadores independentes. Não reutilizar o mesmo código em outro upload: iniciar nova execução, não reexecutar um run já enviado. O versionName permanece 0.2.1 até decisão de versão.

## Gerar o arquivo de upload
Depois de mesclar a PR, tornar o repositório principal privado conforme planejado (artefatos de Actions seguem as permissões do repositório). Em Actions > Build ReiAnix Android APK > Run workflow, selecione main, marque **Gerar AAB para Google Play**, execute. O modo AAB não publica o APK no release contínuo. Na execução concluída com sucesso, baixe o artefato ReiAnix-Google-Play-AAB-N e extraia ReiAnix.aab; não envie o ZIP, APK ou checksum para a Play Console. Guarde também o artefato separado de mapping R8 e relatório com o commit, versionCode e impressão digital. Não compartilhe mapping ou credenciais publicamente. A retenção é de 7 dias; arquive os resultados com segurança.

O pacote não é instalado diretamente pelo usuário. Não há upload automático nem alteração na Play Console.

## Assinatura Google Play
O AAB usa a chave existente, verificada por certificado fixado. Play App Signing é configurado na Play Console pelo titular da conta. Para manter a atualização direta das instalações com o mesmo applicationId e certificado atual, avaliar importar a chave de assinatura existente pelo fluxo oficial de exportação criptografada; manter backups seguros e preferir chave de upload separada após configurar a identidade. Se escolher a chave de assinatura gerada pelo Google, a assinatura das instalações Play será diferente da dos APKs distribuídos diretamente; fazer backup antes de migrar e não prometer atualização direta. Não enviar keystore ou senhas por chat, commit ou issue. Não alterar configuração de chaves antes da escolha explícita do titular.

## Validação 16 KB
O script verifica alinhamento ZIP do APK com zipalign -c -P 16 4, e todos os segmentos LOAD ELF das bibliotecas empacotadas no APK/AAB com readelf. O bundletool 1.18.3 oficial tem SHA-256 fixado e valida a estrutura e a configuração PAGE_ALIGNMENT_16K quando há bibliotecas nativas. Se não houver bibliotecas nativas, essa última verificação é não aplicável. Nenhuma biblioteca é removida ou artificialmente realinhada para passar no teste. O verificador Java lê cada entrada assinada do AAB e confere o certificado fixado. Testar em aparelho físico compatível com páginas de 16 KB e conferir App Bundle Explorer na Play Console; a inspeção estática não comprova comportamento em runtime.

## Antes de enviar
Conferir o Manifest mesclado, permissões, política pública, classificação e segurança de dados, licenças e testes. Testar instalação, atualização compatível, biblioteca, player, reprodução externa, Cast/DLNA, AniList e backups. A certificação de release é separada dos testes focados de PR. Não liberar com falhas pendentes.

Referências oficiais:
- https://developer.android.com/google/play/requirements/target-sdk
- https://developer.android.com/guide/practices/page-sizes
- https://developer.android.com/studio/command-line/bundletool
- https://support.google.com/googleplay/android-developer/answer/9842756
