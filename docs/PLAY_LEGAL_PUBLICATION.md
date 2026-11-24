# Publicação dos documentos legais

1. Após mesclar esta PR, em Settings → Pages do repositório, selecione Deploy from a branch, main, /docs, Save (não é necessário novo workflow).
2. Confirme em janela anônima, sem login, HTTP 200 e conteúdo correto: https://reinansantos9090-debug.github.io/ReiAnix/privacy.html e https://reinansantos9090-debug.github.io/ReiAnix/terms.html. A URL é prevista, não confirmada enquanto Pages não estiver ativo.
3. Informe a URL pública de privacidade em Play Console → Conteúdo do app → Política de privacidade. Use reianix.oficial@gmail.com como contato oficial de suporte e privacidade. Mantenha a caixa monitorada e não solicite dados pessoais em Issues públicos.
4. As integrações auxiliares de intervalos foram retiradas na PR #456, já mesclada à main. Os documentos foram atualizados para descrever somente AniSkip nesse fluxo. Confira o APK final e preencha a seção Segurança de dados com o comportamento efetivo; créditos não equivalem a autorização dos fornecedores.
5. Revise os documentos com o responsável pelo tratamento de dados e, quando necessário, assessoria jurídica. A eventual exigência de exclusão de conta deve ser avaliada na versão final; perfil local não é uma conta remota criada pelo ReiAnix.

## Manutenção

Os documentos HTML públicos e os textos offline em android/app/src/main/assets/legal devem conter o mesmo conteúdo. Atualize-os juntos e altere a data de atualização. As páginas são estáticas, sem scripts, formulários, cookies próprios ou login. GitHub Pages é a hospedagem e pode processar dados técnicos de acesso conforme suas políticas.
