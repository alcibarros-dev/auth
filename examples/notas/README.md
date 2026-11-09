# Exemplo: API de notas com o auth

Um projeto completo que usa o auth do jeito que qualquer outro projeto usaria: o servidor de autorização como imagem
Docker, configurado por um arquivo deste projeto, e as bibliotecas `auth-security-api`, `auth-security` e
`auth-support` como dependências Maven. Não herda nada do repositório do auth; dá para copiar a pasta e começar dela.

O domínio é pequeno de propósito: cada usuário cria, lista e apaga as próprias notas. O que interessa é onde cada
peça do auth entra.

**Comece pela aplicação web:** suba a stack, abra http://localhost:8170, crie uma conta e entre. A página `/painel`
explica o auth com os seus dados reais: o que há dentro do access token e do id_token (cada claim), o refresh token,
onde cada coisa fica guardada, o que a API recebe e extrai, o `/userinfo`, os papéis e as formas de login. Os botões
renovam, inspecionam e revogam os tokens de verdade.

## Rodando

Pré-requisitos: Docker, Java 25 e as variáveis `GITHUB_ACTOR` e `GITHUB_TOKEN` (um token classic com
`read:packages`), porque o GitHub Packages exige token até para pacotes públicos (ver `.mvn/github-settings.xml`).

```bash
./mvnw package
docker compose --profile app up -d --build
./demo.sh
```

O `demo.sh` faz o caminho de um usuário de verdade, com `curl` no lugar do navegador: a CLI pede um código ao
servidor (fluxo de dispositivo), o usuário se cadastra e confirma o código nas páginas do servidor, a CLI recebe o
access token e usa a API. Ele imprime cada resposta, inclusive os claims do token e os erros.

| Endereço | O que é |
|---|---|
| http://localhost:8170 | Aplicação web: login e a página `/painel`, que explica o que o auth fez |
| http://localhost:8190 | Servidor de autorização: `/login`, `/register`, `/activate`, `/oauth2/*`, `/.well-known/openid-configuration` |
| http://localhost:8180/swagger-ui.html | API de notas. O botão **Authorize** faz login pelo servidor (cliente `notas-swagger`, código com PKCE) |
| http://localhost:8180/.well-known/oauth-protected-resource | Metadata do recurso protegido (RFC 9728), publicada pelo `auth-security` |

Para rodar a API no IDE, suba só a infraestrutura e o servidor (`docker compose up -d`) e rode `NotasApplication`, ou
`./mvnw -pl apps/api -am spring-boot:run`: os padrões do `application.yml` já apontam para o compose.

## Como as peças se encaixam

```text
                   ┌──────────── auth (imagem ghcr.io/alcibarros-dev/auth) ────────────┐
CLI / Swagger UI ─►│ login, cadastro, consentimento → access token JWT (iss, aud, sub, email) │
                   └──────────────────────────────┬──────────────────────────────────┘
                                                  │ JWKS (chaves públicas)
                  Authorization: Bearer <token>   ▼
CLI / Swagger UI ─────────────────────────► apps/api ── auth-security: valida o token, 401/403/503
                                               │
                         ┌─────────────────────┴──────────────────────┐
                 modules/contas                                 modules/notas
           UserIdResolver: sub → id local            @CurrentUser long contaId, PublicRoutes,
                                                       ApiException → problem+json
                         └──────────── auth-security-api ─────────────┘
```

| Pasta | Depende de | Papel |
|---|---|---|
| `modules/contas/` | `auth-security-api` | Implementa o `UserIdResolver`: recebe o `sub` (e os claims) de um token já validado e devolve o id da conta local, criando-a no primeiro acesso. É o único lugar que conhece o formato do `sub`. Expõe `GET /api/v1/eu` |
| `modules/notas/` | `auth-security-api`, `auth-support` | A regra de negócio. Os controllers recebem `@CurrentUser long contaId` e nunca veem o token. Declara a rota pública `GET /api/v1/sobre` num bean `PublicRoutes` e lança `ApiException` (404 `NOTA_NAO_ENCONTRADA`) |
| `apps/api/` | os dois módulos e `auth-security` | Junta tudo. Só ela depende do `auth-security`, que se auto-configura: cadeia de filtros, validação pelo JWKS e respostas de erro. Tem a configuração `app.jwt.*`, as migrações e o handler que dá o mesmo formato aos erros do Spring MVC |
| `apps/web/` | só o OAuth2 Client do Spring | A aplicação web, cliente confidencial no padrão BFF (`notas-web`): login com código e PKCE, tokens na sessão do servidor, página `/painel`. Não usa as bibliotecas do auth: é cliente, não recurso |
| `auth/application.yml` | — | A configuração do servidor de autorização para este projeto: os clientes OAuth (`notas-web`, `notas-swagger`, `notas-cli`), a audiência (`notas-api`) e o nome das páginas. O compose monta em `/config` |

### `auth-security-api`: os contratos

Os módulos de negócio dependem só dele, sem Spring Security nem JWT:

- `@CurrentUser long id` num parâmetro de controller entrega o id local do usuário
  ([NotaController](modules/notas/src/main/java/br/com/authcore/exemplo/notas/NotaController.java));
- `UserIdResolver`, implementado uma vez na aplicação, traduz o `sub` no id
  ([ContaResolver](modules/contas/src/main/java/br/com/authcore/exemplo/contas/ContaResolver.java));
- `PublicRoutes` libera uma rota sem token
  ([SobreController](modules/notas/src/main/java/br/com/authcore/exemplo/notas/SobreController.java));
- `OpenApiSecurity.BEARER` é o nome do esquema no `@SecurityRequirement`.

### `auth-security`: a validação

Entra só na aplicação ([pom](apps/api/pom.xml)) e não precisa de código: com ele no classpath, toda rota de
`/api/v1/**` exige token, menos as declaradas públicas, e o resto é negado. A API só configura
([application.yml](apps/api/src/main/resources/application.yml)):

```yaml
app:
  jwt:
    issuer: http://localhost:8190                # o AUTH_ISSUER do servidor (a URL pública)
    audience: notas-api                          # o app.auth.audience do servidor
    jwk-set-uri: http://localhost:8190/oauth2/jwks   # no container: http://auth:8090/oauth2/jwks
    clock-skew: 5s
    resource-name: API de notas                  # opcional (RFC 9728)
```

Com o servidor fora, as chaves já obtidas continuam valendo, e a API continua aceitando os tokens emitidos. Sem
nenhuma chave, responde 503 `AUTH_UNAVAILABLE`.

### `auth-support`: o formato dos erros

Os erros de segurança (401 `UNAUTHENTICATED` e `INVALID_TOKEN`, 403 `ACCESS_DENIED`) saem em `problem+json` com tipo
`urn:auth:problem:<código>`. O módulo `notas` lança `ApiException` com o próprio código, e o
[ErrosHandler](apps/api/src/main/java/br/com/authcore/exemplo/api/ErrosHandler.java) da aplicação usa o mesmo
`ProblemDetails` para esses erros e para os do Spring MVC (validação vira `VALIDATION_ERROR`, com os campos em
`errors`). O cliente trata todos do mesmo jeito. O `auth-support` também registra um `Clock` UTC, que o `notas` usa
para a data das notas.

### O servidor de autorização

A imagem é a mesma de qualquer projeto. O [compose.yaml](compose.yaml) passa o banco (o mesmo da API, no schema
`auth`), o Redis dos limites e o `AUTH_ISSUER`, e monta o [auth/application.yml](auth/application.yml) com os clientes
e a audiência deste projeto. O `aud` dos tokens é `notas-api`: um token emitido para outro projeto, mesmo pelo mesmo
servidor, não passa aqui.

## Onde fica cada token

| Onde | O que tem |
|---|---|
| Navegador, na aplicação web | Só cookies opacos: `NOTASWEB` (sessão da aplicação web) e `AUTHSESSION` (sessão do servidor de autorização) |
| Aplicação web, na sessão em memória | Access token (JWT), id_token (JWT) e refresh token (opaco) |
| Servidor de autorização, PostgreSQL (`auth.oauth2_authorization`) | Cada autorização com o código, os tokens em texto e as validades, até o expurgo diário. É o que a introspecção, a revogação e a renovação consultam |
| Servidor de autorização, Redis | Só os contadores de rate limit (`rl:*`). Nenhum token |
| API | Nenhum token: valida o access token a cada requisição, pela assinatura, e guarda só a conta local |

O access token é um JWT: quem o tem lê o conteúdo (é assinado, não cifrado). Na aplicação web, quem o tem é o
servidor dela; o navegador só vê o cookie de sessão. A CLI e o Swagger UI recebem o JWT direto. Como a API valida o
JWT sozinha, revogar no servidor não o derruba na hora: ele vale até o `exp` (15 minutos), e o que deixa de
funcionar é a renovação. O painel mostra isso no botão **Revogar refresh token**.

## Testes

```bash
./mvnw verify
```

Os testes da aplicação web cobrem a página pública, o redirecionamento ao login com PKCE e a leitura dos JWTs. Os
testes da API ([NotasApiTest](apps/api/src/test/java/br/com/authcore/exemplo/api/NotasApiTest.java)) sobem a
aplicação com um PostgreSQL real (Testcontainers) e usam o `jwt()` do spring-security-test, que entrega um token já
validado: a assinatura, o emissor e a audiência são testados no repositório do auth, e aqui o que importa é o que
cada módulo faz com o usuário. Cobrem a rota pública, o 401 sem token, o 403 fora de `/api/v1`, a criação da conta no
primeiro acesso, a posse das notas, os erros e a metadata.

No CI do auth, este exemplo roda com as bibliotecas e a imagem do próprio commit (`-Dauth.version` e
`AUTH_IMAGE_TAG`), incluindo o `demo.sh`.

## Versão do auth

`auth.version` no [pom.xml](pom.xml) e o padrão de `AUTH_IMAGE_TAG` no [compose.yaml](compose.yaml) andam juntos.
