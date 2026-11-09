# auth

Tudo o que é segurança, para reaproveitar entre projetos: o servidor de autorização OAuth 2.1 e OpenID Connect, com o
Spring Authorization Server, e as bibliotecas com que as APIs dos projetos validam os tokens dele. O servidor cuida
das credenciais e das páginas de login, cadastro e troca de senha, e emite os tokens; cada projeto registra os
próprios clientes num arquivo de configuração, e a imagem é a mesma para todos. As APIs só cuidam de regra de
negócio: recebem o usuário autenticado pronto.

| Módulo | Artefato | O que é | Como chega aos projetos |
|---|---|---|---|
| `server/` | `auth-server` | O servidor de autorização | Imagem `ghcr.io/alcibarros-dev/auth` |
| `security-api/` | `auth-security-api` | Contratos para os módulos de negócio: `@CurrentUser`, `UserIdResolver`, `PublicRoutes`, `OpenApiSecurity` | Jar, pelo GitHub Packages |
| `security/` | `auth-security` | Validação dos tokens na API (resource server): JWKS com cache e tolerância à queda do servidor, cadeia de filtros e respostas 401, 403 e 503 em `problem+json` | Jar, pelo GitHub Packages |
| `support/` | `auth-support` | Respostas de erro RFC 9457, usadas pelo servidor e pelo `auth-security` | Jar, junto com o `auth-security` |
| `coverage/` | `auth-coverage` | Sem código: o gate de cobertura de todos os módulos | — |

Nasceu no projeto encurtador-url e saiu de lá pelo ADR-0007 daquele repositório. As decisões estão em
[`docs/adr`](docs/adr/README.md): o servidor (ADR-0001), a validação dos tokens nas APIs (ADR-0002) e o repositório e
a distribuição (ADR-0003). Os comentários do código citam esses ADRs.

**O que ele faz**

- Login e logout do navegador, cadastro com login automático e troca de senha, que encerra as outras sessões.
- Fluxo de código com PKCE (aplicações web e BFFs), refresh token e fluxo de dispositivo (RFC 8628, para CLIs).
- Access tokens JWT RS256 de 15 minutos com `sub` estável, `email` e a audiência configurada; JWKS com a chave atual e
  a anterior durante a rotação.
- Rate limit no Redis por IP, e-mail, usuário e `client_id`, com 429 e `Retry-After`; sem o Redis, libera.
- Senhas com bcrypt, de 8 a 64 caracteres (até 72 bytes).

## Usando num projeto

A imagem é `ghcr.io/alcibarros-dev/auth`, para `linux/amd64` e `linux/arm64`. Fixe uma versão (`:0.2.0`): `:main`
muda a cada commit.

1. Escreva um `application.yml` com os clientes, a audiência e o nome das páginas:

   ```yaml
   spring:
     security:
       oauth2:
         authorizationserver:
           client:
             meu-app-web:
               registration:
                 client-id: meu-app-web
                 client-secret: "{noop}${MEU_APP_WEB_SECRET}"
                 client-authentication-methods: client_secret_basic
                 authorization-grant-types: authorization_code,refresh_token
                 redirect-uris: https://app.exemplo.com/login/oauth2/code/meu-app
                 post-logout-redirect-uris: https://app.exemplo.com/
                 scopes: openid,email
               require-proof-key: true
   app:
     auth:
       display-name: Meu App
       audience: meu-app-api
       panel-origins: https://app.exemplo.com
       panel-default-url: https://app.exemplo.com/
   ```

2. Monte o arquivo em `/config/application.yml` e passe o banco, o Redis e o emissor:

   ```yaml
   services:
     auth:
       image: ghcr.io/alcibarros-dev/auth:0.2.0
       environment:
         SPRING_DATASOURCE_URL: jdbc:postgresql://postgres:5432/meuapp
         SPRING_DATASOURCE_USERNAME: meuapp
         SPRING_DATASOURCE_PASSWORD: meuapp
         SPRING_DATA_REDIS_HOST: redis
         AUTH_ISSUER: http://localhost:8090
         MEU_APP_WEB_SECRET: troque-me
       volumes:
         - ./auth/application.yml:/config/application.yml:ro
       ports:
         - "127.0.0.1:8090:8090"
   ```

3. Na API do projeto, valide os tokens com o emissor (`iss` igual a `AUTH_ISSUER`), a audiência e o JWKS em
   `/oauth2/jwks`.

O projeto encurtador-url é um exemplo completo:
`infra/auth-server/application.yml` e o serviço `auth-server` do `compose.yaml` dele.

### Configuração

| Propriedade ou variável | Obrigatória | O que é |
|---|---|---|
| `spring.security.oauth2.authorizationserver.client.*` | sim | Os clientes OAuth. Sem nenhum, o servidor não sobe |
| `app.auth.audience` (`AUTH_AUDIENCE`) | sim | O `aud` dos access tokens: a API que os valida |
| `app.auth.panel-origins` (`AUTH_PANEL_ORIGINS`) | sim | Origens para onde o cadastro e a troca de senha podem voltar (`?continue=`) |
| `app.auth.panel-default-url` (`AUTH_PANEL_DEFAULT_URL`) | sim | Para onde voltar sem um `continue` válido |
| `app.auth.display-name` (`AUTH_DISPLAY_NAME`) | não | Nome no topo e no título das páginas; padrão `Conta` |
| `AUTH_ISSUER` | não | URL pública, que vai no `iss`; padrão `http://localhost:8090` |
| `AUTH_SIGNING_KEY_ID`, `AUTH_SIGNING_KEY_FILE` | não | `kid` e PEM PKCS#8 da chave RSA (`file:/run/secrets/chave.pem`). Sem o arquivo, a chave é gerada na subida e os tokens deixam de valer quando o container reinicia |
| `APP_AUTH_PREVIOUS_SIGNING_KEY_ID`, `APP_AUTH_PREVIOUS_SIGNING_KEY_LOCATION` | não | Chave anterior, publicada no JWKS durante a rotação |
| `app.cors.allowed-origins` | não | Origens que chamam `/oauth2/token` do navegador (um Swagger UI, por exemplo) |
| `SPRING_DATASOURCE_*` | sim | PostgreSQL. Tudo fica no schema `auth`, criado pelo Flyway |
| `SPRING_DATA_REDIS_HOST`, `SPRING_DATA_REDIS_PORT` | sim | Redis dos contadores de rate limit |
| `app.rate-limit.<regra>` | não | Limites: `login`, `login-email`, `register`, `password-change`, `token`, `token-client`, `device-authorization`, `device-verification` |

Gere uma chave de assinatura com:

```bash
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out auth-signing-key.pem
```

**Rotação:** suba com a chave nova em `AUTH_SIGNING_KEY_*` e a anterior em `APP_AUTH_PREVIOUS_SIGNING_KEY_*`. O JWKS
publica as duas e só a nova assina. Depois de 15 minutos, o tempo de vida de um access token, retire a anterior.

### Endereços

| Porta | Conteúdo |
|---|---|
| 8090 | Páginas (`/login`, `/register`, `/account/password`, `/activate`), protocolo OAuth e OIDC (`/oauth2/*`, `/connect/logout`, `/userinfo`, `/.well-known/openid-configuration`), `/livez` e `/readyz` |
| 8091 | Actuator completo (`health`, `info`, `metrics`, `prometheus`); só para a rede interna |

## Validando os tokens numa API

O `auth-security` é auto-configurado: com ele no classpath, a API exige um access token válido em toda rota que não
for declarada pública, e o controller recebe o usuário por `@CurrentUser`. A API declara:

- `app.jwt.issuer` (o `AUTH_ISSUER` do servidor), `app.jwt.audience` (o `app.auth.audience` dele) e
  `app.jwt.jwk-set-uri` (o `/oauth2/jwks` dele, que pode ser um endereço interno);
- opcionalmente, `app.jwt.resource-name`, o nome legível que a API publica em `/.well-known/oauth-protected-resource`
  (RFC 9728);
- um bean `UserIdResolver`, que traduz o `sub` do token no id do usuário da aplicação;
- beans `PublicRoutes` com as rotas que não exigem token.

Com o servidor fora, as chaves já obtidas continuam valendo por até um dia (`app.jwt.jwks.outage-tolerance`); sem
nenhuma chave utilizável, a requisição recebe 503 `AUTH_UNAVAILABLE` com `Retry-After`.

As bibliotecas são publicadas no GitHub Packages a cada tag `vX.Y.Z`. No projeto, declare o repositório e a
dependência:

```xml
<repositories>
    <repository>
        <id>github</id>
        <url>https://maven.pkg.github.com/alcibarros-dev/auth</url>
    </repository>
</repositories>

<dependency>
    <groupId>br.com.authcore</groupId>
    <artifactId>auth-security</artifactId>
    <version>0.2.0</version>
</dependency>
```

Os módulos de negócio, que só precisam de `@CurrentUser` e dos outros contratos, dependem só do `auth-security-api`.
O GitHub Packages exige token até para pacotes públicos: um servidor `github` no `settings.xml`, com um token
(classic) com `read:packages`. No GitHub Actions de outro repositório, o `GITHUB_TOKEN` com `packages: read` serve,
desde que o pacote dê acesso a esse repositório (Package settings → Manage Actions access). O
encurtador-url mostra um jeito de fazer isso com variáveis de
ambiente (`.mvn/maven.config` e `.mvn/github-settings.xml`), inclusive no build da imagem.

Para testar uma mudança aqui num projeto antes de publicá-la, `./mvnw install` instala as bibliotecas no repositório
Maven local, com a versão do `pom.xml`.

## Desenvolvimento

Pré-requisitos: Docker e Java 25 (`mise install` instala a versão do `.mise.toml`).

```bash
./mvnw -pl server -am spring-boot:run
```

O perfil `local` sobe o `compose.yaml` (PostgreSQL na 5434 e Redis na 6380), assina com uma chave de desenvolvimento
versionada, que nunca deve ser usada fora dele, e registra dois clientes de exemplo: `demo-cli` (fluxo de dispositivo)
e `demo-dev` (código com PKCE, para o Postman). Para experimentar o fluxo de dispositivo:

```bash
curl -s -X POST localhost:8090/oauth2/device_authorization -d client_id=demo-cli -d scope=email
```

Abra o `verification_uri_complete` da resposta, crie uma conta e confirme o código.

## Testes

```bash
./mvnw verify
```

Só exige Docker: o Testcontainers sobe PostgreSQL e Redis reais. No servidor, cobre os fluxos de código com PKCE e de
dispositivo, cadastro, troca de senha, limites, rotação de chave e claims emitidos, com clientes neutros (`app-cli`,
`app-web`, `app-dev`) definidos no `application-test.yml`. No `auth-security`, a validação do token (emissor,
audiência, assinatura, `typ`), o cache do JWKS e o 503 sem nenhuma chave. O gate de cobertura (JaCoCo) exige 80% das
linhas fora de configuração, somadas todas as do repositório; o relatório fica em
`coverage/target/site/jacoco-aggregate/`.

Para testar um módulo sozinho:

```bash
./mvnw -pl security -am test
```

## Imagem e publicação

```bash
docker build -t auth:local .
```

O CI testa cada push e pull request e publica a imagem no `ghcr.io`:

| Evento | Tags |
|---|---|
| Push na `main` | `:main`, `:sha-<commit>` |
| Tag `vX.Y.Z` | `:X.Y.Z`, `:X.Y`, `:latest`, e as bibliotecas `X.Y.Z` no GitHub Packages |

Para lançar uma versão:

```bash
git tag v0.2.0 && git push origin v0.2.0
```

Depois da primeira publicação, confira em **Packages → auth → Package settings** que a visibilidade da imagem é
pública: assim os projetos baixam a imagem sem login. Nos pacotes Maven (`br.com.authcore.auth-security` e os outros),
dê acesso de leitura em **Manage Actions access** aos repositórios cujo CI os baixa.

## Limitações conhecidas

- A sessão do navegador fica em memória: uma instância só.
- Os tokens emitidos ficam guardados em claro no banco até o expurgo diário.
- Sem verificação de e-mail nem exclusão de conta.
