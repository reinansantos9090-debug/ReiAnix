# Publicação dos documentos legais

1. Após mesclar esta PR, em Settings → Pages do repositório, selecione Deploy from a branch, main, /docs, Save (não é necessário novo workflow).
2. Confirme em janela anônima, sem login, HTTP 200 e conteúdo correto: https://reinansantos9090-debug.github.io/ReiAnix/privacy.html e https://reinansantos9090-debug.github.io/ReiAnix/terms.html. A URL é prevista, não confirmada enquanto Pages não estiver ativo.
3. Informe a URL pública de privacidade em Play Console → Conteúdo do app → Política de privacidade. Confirme o nome do desenvolvedor e disponibilize um canal privado e monitorado de solicitações de titulares (e-mail de suporte ou formulário privado). O canal Issues é público e não deve receber dados pessoais. Não invente um endereço de contato.
4. Antes da publicação, confirme a integração de APIs na versão final, inclusive a retirada prevista de IntroDB/Ani.zip na PR #456. Atualize as seções correspondentes nas duas cópias, bem como os dados de segurança da Play Console. A política descreve o comportamento atual e a transição; não equivale à autorização dos fornecedores.
5. Revise os documentos com o responsável pelo tratamento de dados e, quando necessário, assessoria jurídica. A eventual exigência de exclusão de conta deve ser avaliada na versão final; perfil local não é uma conta remota criada pelo ReiAnix.

## Manutenção

Os documentos HTML públicos e os textos offline em android/app/src/main/assets/legal devem conter o mesmo conteúdo. Atualize-os juntos e altere a data de atualização. As páginas são estáticas, sem scripts, formulários, cookies próprios ou login. GitHub Pages é a hospedagem e pode processar dados técnicos de acesso conforme suas políticas.
