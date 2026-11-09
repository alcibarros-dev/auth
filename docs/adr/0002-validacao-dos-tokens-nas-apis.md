# ADR-0002 — Validação dos tokens nas APIs

| Campo | Valor |
|---|---|
| Status | **Aceito** · implementado (`modules/security-api/`, `modules/security/`, `modules/support/`) |
| Data | 2026-11-09 |
| Escopo | As bibliotecas com que a API de cada projeto valida os access tokens do servidor (ADR-0001) e entrega o usuário autenticado aos controllers |
| Origem | Decisões tomadas no projeto encurtador-url (ADR-0001 D9, ADR-0002 D3, D4, D6 e D11, ADR-0003 D6 e D12 de lá), reescritas aqui sem o que era só do encurtador |

**Direcionadores.** O código de negócio não conhece JWT nem o provedor de identidade; trocar o provedor muda
configuração, não código; a API continua aceitando tokens quando o servidor de autorização está fora.

---

## 1. Contexto

Uma API que recebe os tokens do servidor precisa validar assinatura, emissor, audiência e validade, traduzir o `sub`
no usuário dela e responder 401, 403 e 503 num formato estável. Escrito em cada projeto, isso se repete e diverge.
As duas bibliotecas deste repositório fazem isso uma vez, e a API só declara o que é dela.

## 2. Decisões

### D1 — Contratos para o código de negócio (`auth-security-api`)

Sem dependência de framework além das anotações. Os módulos de negócio dependem só dele:

| Tipo | Papel | Quem implementa ou usa |
|---|---|---|
| `@CurrentUser` | Parâmetro de controller: `@CurrentUser long userId` | Controllers; o `auth-security` resolve |
| `UserIdResolver` | SPI: `sub` e claims de um token válido → id do usuário na aplicação | A aplicação (no encurtador, o módulo de contas, que cria a conta no primeiro acesso) |
| `PublicRoutes` | SPI: rotas que um módulo libera sem token | Cada módulo, num bean |
| `OpenApiSecurity.BEARER` | Nome do esquema de segurança nos `@SecurityRequirement` | Controllers protegidos |
| `UnknownSubjectException` | O resolvedor não reconhece o `sub` | O `auth-security` a transforma em 401 |

Como os controllers não veem o `Jwt`, um `sub` em outro formato (UUID do Keycloak, por exemplo) só muda o
`UserIdResolver`.

### D2 — Fronteira do `auth-security`

| Entra no `auth-security` | Fica na aplicação |
|---|---|
| Validação do token por JWKS (D3, D4) | Emissão de tokens, credenciais, login: são do servidor (ADR-0001) |
| Cadeia de filtros stateless: as rotas de `PublicRoutes` e as de infraestrutura (probes, `/error`, Swagger UI quando `springdoc.api-docs.enabled`) liberadas; `/api/v1/**` exige token; o resto é negado | Posse dos recursos e regras de domínio motivadas por segurança |
| Respostas 401, 403 e 503 em `problem+json` (D5) | O formato dos outros erros da aplicação |
| Resolução do `@CurrentUser` | Rate limit da aplicação |
| Esquema bearer do OpenAPI | O restante da documentação OpenAPI |

O actuator completo fica na porta de gestão, que não deve ser publicada fora da rede interna; por isso a cadeia o
libera.

### D3 — Validação local, por JWKS

```yaml
app:
  jwt:
    issuer: https://auth.exemplo.com       # exigido no "iss": a URL pública do servidor
    audience: meu-app-api                  # exigido no "aud": o app.auth.audience do servidor
    jwk-set-uri: http://auth:8090/oauth2/jwks   # pode ser um endereço interno, diferente do emissor
    clock-skew: 5s
```

A cada requisição, sem chamar o servidor: extrai o bearer token, acha a chave pública pelo `kid` no cache, verifica
a assinatura RS256, confere `iss`, `aud`, `exp` e `nbf` (com o `Clock` da aplicação) e o `typ`, se houver (`JWT` ou
`at+jwt`). Depois, o `@CurrentUser` chama o `UserIdResolver`.

O `issuer` e o `jwk-set-uri` são separados de propósito: o navegador acessa o servidor pelo endereço público, que vira
o `iss`, e o container da API pelo nome interno.

### D4 — Cache das chaves e queda do servidor

O `JwksKeySource` monta o `JWKSource` com o `JWKSourceBuilder` do Nimbus: cache de 15 minutos com renovação
antecipada, nova busca limitada (até duas por janela de 30 s) quando aparece um `kid` desconhecido, timeouts curtos e
**tolerância a queda**: com o JWKS fora, as últimas chaves obtidas valem por até um dia
(`app.jwt.jwks.outage-tolerance`). O padrão do Spring Security (`withJwkSetUri`) guarda as chaves por 5 minutos, sem
limite de buscas e sem tolerância: cinco minutos depois, com o servidor fora, a API recusaria todos os tokens.

Sem nenhuma chave utilizável (cache vazio e JWKS fora), a requisição recebe **503 `AUTH_UNAVAILABLE`** com
`Retry-After`, e não o 500 padrão (a falha vira `AuthenticationServiceException`, que o handler padrão relança). O 503
é ligado por um `ObjectPostProcessor<BearerTokenAuthenticationFilter>` com um `AuthenticationFailureHandler` próprio,
porque a DSL não oferece esse gancho.

Assim, a API sobe sem o servidor (o JWKS é buscado sob demanda) e continua aceitando os tokens já emitidos. Só novos
logins param.

### D5 — Erros 401 e 403 em `problem+json`

O `BearerTokenAuthenticationEntryPoint` do Spring Security só escreve o status e o `WWW-Authenticate`, sem corpo. O
`ProblemDetailAuthenticationEntryPoint` delega a ele o cabeçalho e escreve o corpo RFC 9457; o
`ProblemDetailAccessDeniedHandler` faz o mesmo no 403. Os códigos são `UNAUTHENTICATED`, `INVALID_TOKEN`,
`ACCESS_DENIED` e `AUTH_UNAVAILABLE`, com o tipo `urn:auth:problem:<código>`, escritos pelo `ProblemResponseWriter` do
`auth-support`.

Exceções de segurança lançadas dentro de um controller (pelo `@CurrentUser`, por exemplo) voltam ao Spring Security
pelo `SecurityExceptionAdvice`, que vem antes do handler global da aplicação: o 401 e o 403 saem sempre do mesmo
lugar.

### D6 — Auto-configuração

As bibliotecas seguem o padrão dos starters do Spring Boot: os beans vêm de classes `@AutoConfiguration` listadas em
`META-INF/spring/…AutoConfiguration.imports`, e a aplicação não precisa escanear `br.com.authcore`. A do
`auth-security` vem antes das do Spring Boot para a cadeia de filtros e o `JwtDecoder`, que só existem na falta das
nossas. A do `auth-support` registra o `ProblemResponseWriter` e um `Clock` UTC, cada um só se a aplicação não tiver o
seu (`@ConditionalOnMissingBean`), com nomes próprios para não colidir.

### D7 — Metadata do recurso protegido (RFC 9728)

O Spring Security 7 publica `/.well-known/oauth-protected-resource` e o anuncia no `WWW-Authenticate`. O padrão
declara tokens presos a certificado (mTLS), o que não vale aqui: o `auth-security` o desliga. O nome legível
(`resource_name`) é de cada API, em `app.jwt.resource-name`; sem ele, o campo não aparece. Até a 0.2.0, o nome do
encurtador estava fixo na biblioteca.

### D8 — Testes

Os testes ficam no módulo do código que testam, com uma `@SpringBootApplication` de teste, porque a classe da
aplicação não existe aqui. Um servidor HTTP local (`JwksStub`) publica um JWKS com uma chave RSA de teste e conta as
buscas: os testes passam pelo mesmo caminho de produção (busca, cache, `kid` desconhecido, indisponibilidade), sem o
servidor de autorização. Cobrem emissor, audiência, assinatura, `typ`, o cache, o 503 sem chave e a metadata.

## 3. Alternativas consideradas

| Alternativa | Por que não |
|---|---|
| Só o `spring-boot-starter-oauth2-resource-server`, configurado em cada API | Sem tolerância à queda do servidor (D4), 500 sem chave e 401 sem corpo; cada projeto repetiria as correções |
| Introspecção do token a cada requisição | A API pararia junto com o servidor |
| Entregar o `Jwt` aos controllers | Prende o código de negócio ao formato do token e ao provedor |

## 4. Limitações conhecidas

- As rotas protegidas são as de `/api/v1/**`, fixas na biblioteca; o resto é negado se não for declarado público.
