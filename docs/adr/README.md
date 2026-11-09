# Decisões de arquitetura

| ADR | Título | Status |
|---|---|---|
| [0001](0001-servidor-de-autorizacao.md) | Servidor de autorização | Aceito |
| [0002](0002-validacao-dos-tokens-nas-apis.md) | Validação dos tokens nas APIs | Aceito |
| [0003](0003-repositorio-modulos-e-distribuicao.md) | Repositório, módulos e distribuição | Aceito |

As decisões nasceram no projeto encurtador-url, de onde este repositório saiu; cada ADR diz de quais de lá veio. Os
comentários do código citam estes ADRs ("ADR-0001, D8"). A exceção é a migração `V1__auth.sql`, que cita os do
encurtador: o Flyway confere o checksum das migrações já aplicadas, e mudar até um comentário quebraria os bancos
existentes.
