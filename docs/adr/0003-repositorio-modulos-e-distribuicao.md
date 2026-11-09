# ADR-0003 — Repositório, módulos e distribuição

| Campo | Valor |
|---|---|
| Status | **Aceito** · implementado (0.1.0 e 0.2.0) |
| Data | 2026-11-09 |
| Escopo | Como o repositório se organiza e como o servidor e as bibliotecas chegam aos projetos |
| Origem | ADR-0007 do projeto encurtador-url, que tirou o servidor e as bibliotecas de lá |

**Direcionadores.** Reaproveitar a segurança em mais de um projeto; cada projeto configura o servidor sem mudar a
imagem; nenhum nome pessoal nos artefatos; uma versão só para o servidor e as bibliotecas.

---

## 1. Contexto

O servidor (ADR-0001) e as bibliotecas (ADR-0002) nasceram no encurtador-url, presos ao `pom` pai, aos pacotes
`br.com.encurtador`, aos clientes OAuth escritos no `application.yml` e à plataforma (`shared` e `ratelimit`) de lá.
Em 2026-11-08 eles saíram para este repositório, que começou num commit só, sem reescrever o histórico de lá.

## 2. Decisões

### D1 — Módulos

`br.com.authcore:auth-parent`, filho do `spring-boot-starter-parent`, com as pastas no mesmo arranjo do
encurtador-url: aplicações em `apps/`, bibliotecas em `modules/` e ferramentas de build em `tools/`.

| Módulo | Artefato | Pacote | O que é | Distribuição |
|---|---|---|---|---|
| `modules/support/` | `auth-support` | `br.com.authcore.support` | Respostas de erro RFC 9457 e o `Clock` padrão | Jar |
| `modules/security-api/` | `auth-security-api` | `br.com.authcore.security` | Contratos para o código de negócio (ADR-0002, D1) | Jar |
| `modules/security/` | `auth-security` | `br.com.authcore.security` | Validação dos tokens na API (ADR-0002) | Jar |
| `apps/server/` | `auth-server` | `br.com.authcore.server` | O servidor de autorização (ADR-0001) | Imagem |
| `tools/coverage/` | `auth-coverage` | — | Gate de cobertura: 80% das linhas, somados todos os módulos | Nenhuma |

O servidor usa pouco do que era a plataforma do encurtador: o rate limit, o `RedisGuard`, a validação de tamanho em
UTF-8, o `Clock` e o agendamento. Essas classes foram copiadas para dentro do `server` e evoluem com ele. Só as
respostas de erro, que o `auth-security` também usa, ficaram num módulo próprio, o `support`. O servidor não depende
do `security` nem do `security-api`, e vice-versa.

### D2 — Configuração do servidor por arquivo de cada projeto

O `application.yml` da imagem não tem clientes. Cada projeto monta um arquivo em `/config/application.yml`
(`SPRING_CONFIG_ADDITIONAL_LOCATION=optional:file:/config/`, na imagem) com os clientes, `app.auth.audience`,
`app.auth.display-name`, `app.auth.panel-origins`, `app.auth.panel-default-url` e `app.cors.allowed-origins`. O
arquivo pode ler variáveis de ambiente onde fizer sentido (segredos, endereços). Um mapa de clientes só em variáveis
(`SPRING_SECURITY_OAUTH2_AUTHORIZATIONSERVER_CLIENT_…`) ficaria ilegível.

### D3 — Imagem no GitHub Container Registry

`ghcr.io/alcibarros-dev/auth`, pública, para `linux/amd64` e `linux/arm64`, construída pelo CI. Pública, ela é baixada
sem login, e o `ghcr.io` não cobra imagens públicas. Os projetos fixam a versão (`:0.2.0`); `:main` muda a cada
commit.

### D4 — Bibliotecas no GitHub Packages

Bibliotecas não viram imagem: a API as inclui no build. O GitHub Packages fica ao lado da imagem, sem conta nem
assinatura GPG (Maven Central) e sem serviço de terceiros (JitPack). O CI publica o `auth-parent`, o `auth-support`, o
`auth-security-api` e o `auth-security`; o `auth-server` e o `auth-coverage` têm `maven.deploy.skip`.

O GitHub Packages exige token até para pacotes públicos (`read:packages`). No GitHub Actions de outro repositório, o
`GITHUB_TOKEN` com `packages: read` serve, desde que o pacote dê acesso de leitura a esse repositório (Package
settings → Manage Actions access).

### D5 — Versões e lançamento

Uma versão só para tudo: a tag `vX.Y.Z` publica a imagem (`:X.Y.Z`, `:X.Y`, `:latest`) e as bibliotecas `X.Y.Z`
(`versions:set` com a versão da tag, e `deploy`). Cada push na `main` publica `:main` e `:sha-<commit>`, mas nenhuma
biblioteca. No `pom.xml`, a versão é a próxima, com `-SNAPSHOT`; `./mvnw install` a instala no repositório Maven
local, para testar uma mudança num projeto antes de publicá-la.

Versionamento semântico: mudança que exige ação dos projetos (renomear pacote, propriedade obrigatória nova) sobe o
número do meio enquanto a versão for `0.x`.

### D6 — Namespace sem nome pessoal

Desde a 0.2.0, o `groupId` e os pacotes Java são `br.com.authcore`. O dono do GitHub continua só nos endereços que o
GitHub exige (`github.com/<dono>/auth`, `ghcr.io/<dono>/auth`, `maven.pkg.github.com/<dono>/auth`); tirá-lo daí
exigiria uma organização. Os pacotes e as imagens do namespace anterior foram apagados.

### D7 — Exemplo de uso no repositório

`examples/notas` é um projeto completo que usa o auth como qualquer outro: herda do `spring-boot-starter-parent`, e não
do `auth-parent`; depende das bibliotecas pela versão publicada (`auth.version`) e sobe o servidor pela imagem, com o
próprio arquivo de clientes. Fica fora do reactor, para não virar dependência do build. O CI o roda com as
bibliotecas e a imagem do commit (`-Dauth.version` e `AUTH_IMAGE_TAG`), incluindo a demonstração de ponta a ponta:
uma mudança que quebre o uso das bibliotecas aparece aqui antes de chegar a um projeto.

## 3. Alternativas consideradas

| Alternativa | Por que não |
|---|---|
| Submódulo do Git em cada projeto | O build do projeto voltaria a compilar o servidor, e cada clone precisaria de `--recurse-submodules` |
| Publicar a plataforma do encurtador como biblioteca | Registry, versões e publicação a cada mudança, por cerca de quinze classes |
| Imagem privada | `docker login` e token em cada máquina e em cada CI |

## 4. Consequências

- Uma mudança que precisa dos dois lados vira duas: lançar a versão aqui e subir o número no projeto.
- As referências dos testes e dos exemplos aos clientes são neutras (`app-*`, `demo-*`); o encurtador-url é o exemplo
  completo de uso.
