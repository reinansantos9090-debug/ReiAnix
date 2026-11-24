# Publicação dos documentos legais

1. No repositório público ReiAnix-Legal, mescle a PR com os documentos. Em Settings → Pages, selecione Deploy from a branch, main, /(root), Save. Não é necessário novo workflow no aplicativo.
2. Confirme em janela anônima, sem login, HTTP 200 e conteúdo correto: https://reinansantos9090-debug.github.io/ReiAnix-Legal/privacy.html e https://reinansantos9090-debug.github.io/ReiAnix-Legal/terms.html. Confirme os novos links antes de tornar o repositório principal privado. Atualize também a ficha da Play Console se houver URL antiga cadastrada.
3. Informe a URL pública de privacidade em Play Console → Conteúdo do app → Política de privacidade. Use reianix.oficial@gmail.com como contato oficial de suporte e privacidade. Mantenha a caixa monitorada e não solicite dados pessoais em Issues públicos.
4. As integrações auxiliares de intervalos foram retiradas na PR #456, já mesclada à main. Os documentos foram atualizados para descrever somente AniSkip nesse fluxo. Confira o APK final e preencha a seção Segurança de dados com o comportamento efetivo; créditos não equivalem a autorização dos fornecedores.
5. Revise os documentos com o responsável pelo tratamento de dados e, quando necessário, assessoria jurídica. A eventual exigência de exclusão de conta deve ser avaliada na versão final; perfil local não é uma conta remota criada pelo ReiAnix.

## Manutenção

A publicação oficial fica no repositório ReiAnix-Legal (https://github.com/reinansantos9090-debug/ReiAnix-Legal). As cópias HTML em docs/ do repositório principal e os textos offline em android/app/src/main/assets/legal devem corresponder aos documentos públicos. Atualize as cópias juntas e altere a data de atualização. Não publique código, credenciais, arquivos de mídia ou dados de usuários no repositório legal. As páginas são estáticas, sem scripts, formulários, cookies próprios ou login. GitHub Pages é a hospedagem e pode processar dados técnicos de acesso conforme suas políticas.
