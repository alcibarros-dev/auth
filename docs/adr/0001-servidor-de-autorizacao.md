# ADR-0001 — Servidor de autorização

| Campo | Valor |
|---|---|
| Status | **Aceito** · implementado (`apps/server/`) |
| Data | 2026-11-09 |
| Escopo | O servidor de autorização OAuth 2.1 e OpenID Connect (`auth-server`): clientes, fluxos, tokens, chaves, dados, páginas e proteções |
| Origem | Decisões tomadas no projeto encurtador-url (ADR-0001 D7, D9 e D11, ADR-0003 e ADR-0004 D4 de lá), reescritas aqui sem o que era só do encurtador |

**Direcionadores.** O provedor de identidade tem de ser trocável (pelo Keycloak, por exemplo) sem mudar o código das
APIs; a senha do usuário não passa pelas APIs; padrões abertos (OAuth 2, OpenID Connect, JWT, JWKS) em vez de um
protocolo próprio; as APIs continuam funcionando quando o servidor está fora (ADR-0002, D4).

---

## 1. Contexto

O servidor nasceu dentro do encurtador-url, para tirar da API o login, o cadastro, a senha e a emissão de tokens, que
antes eram dela (tokens HMAC assinados com um segredo que a API também conhecia). Ele usa o Spring Authorization
Server (SAS), que desde o Spring Security 7 faz parte do Spring Security e vem pelo starter do Spring Boot. Depois
saiu para este repositório, para servir a outros projetos (ADR-0003).

## 2. Decisões

### D1 — Papéis e vocabulário

| Papel (OAuth) | Quem é | O que faz com o token |
|---|---|---|
| Cliente | CLI, frontend (direto ou por um BFF), Postman, Swagger UI | Obtém o access token e o envia; para ele, o token é opaco |
| Servidor de autorização | `auth-server` | Autentica o usuário e assina os tokens |
| Servidor de recurso | A API de cada projeto, com o `auth-security` (ADR-0002) | Valida o access token em toda requisição |

O cliente só valida o `id_token`, uma vez, no login, se precisar saber quem entrou.

### D2 — Aplicação, portas, cadeias e sessão

- **Portas 8090** (páginas e protocolo) e **8091** (actuator completo: `health`, `info`, `metrics`, `prometheus`).
  A 8091 é só para a rede interna; `/livez` e `/readyz` também respondem na 8090.
- Endpoints do SAS: `/oauth2/authorize`, `/oauth2/token`, `/oauth2/device_authorization`,
  `/oauth2/device_verification`, `/oauth2/jwks`, `/oauth2/revoke`, `/oauth2/introspect`, `/userinfo`,
  `/connect/logout` e os dois `/.well-known`. Páginas próprias: `/login`, `/register`, `/account/password`,
  `/activate` (confirmação de dispositivo) e a página inicial da conta.
- **Duas cadeias do Spring Security:** a do protocolo, sem CSRF, e a das páginas, com sessão por cookie e **CSRF
  ligado**.
- Cookie de sessão com nome próprio, `AUTHSESSION` (`HttpOnly`, `SameSite=Lax`): cookies de `localhost` não se separam
  por porta, e o frontend do projeto costuma rodar na mesma máquina.
- **Sessão em memória:** uma instância só. Mais instâncias exigiriam Spring Session com Redis.
- Logs JSON (ECS) com `traceId`; nunca registram senha, token, código de autorização, `device_code` nem `user_code`.

### D3 — Clientes

- Registrados por propriedades do Spring Boot (`spring.security.oauth2.authorizationserver.client.*`), sem tabela de
  clientes: a lista é fixa e versionada **no projeto que usa o servidor**, que monta o arquivo na imagem (ADR-0003,
  D3). A imagem não traz nenhum cliente, e sem nenhum o servidor não sobe.
- **PKCE obrigatório** em todo `authorization_code`, inclusive de cliente confidencial (`require-proof-key: true`).
- **Access token de 15 minutos**, configurado em cada cliente (o padrão do SAS é 5).
- **Refresh token só para cliente confidencial** (um BFF), sem rotação (`reuse-refresh-tokens: true`) e com validade
  absoluta (30 dias no encurtador). A RFC 9700 (§2.2.2) só exige rotação ou vínculo ao remetente para clientes
  públicos, e o token só serve com o segredo, que fica no servidor do cliente. O SAS não detecta reuso de refresh
  token rotacionado e aceita renovações paralelas; por isso os clientes públicos não recebem o grant.
- **Cliente público no fluxo de dispositivo:** o SAS só autentica cliente público quando a requisição traz
  `code_verifier`. Um conversor e um provider próprios (`DeviceClientAuthenticationConverter` e
  `DeviceClientAuthenticationProvider`) aceitam o `client_id` em `/oauth2/device_authorization` e na troca do
  `device_code`.

### D4 — Fluxos

**Código de autorização com PKCE** (aplicações web e BFFs):

```text
cliente  → navegador → /oauth2/authorize?client_id&redirect_uri&scope=openid email&state&code_challenge(S256)
auth-server          → sem sessão: /login; o usuário digita e-mail e senha NA PÁGINA DO auth-server
auth-server          → abre a sessão dele e redireciona para redirect_uri?code&state
cliente  → /oauth2/token (code + code_verifier)
cliente  ← access token + id_token (+ refresh token, se confidencial)
```

A sessão do servidor permite um segundo login sem senha (SSO entre os clientes do mesmo projeto).

**Dispositivo** (RFC 8628, para CLIs): a CLI pede o `user_code`; o usuário abre a `verification_uri`, faz login,
digita o código e **confirma** o acesso numa página de consentimento, só por POST com CSRF (um `GET` com o código na
URL só preenche o campo). O consentimento não é lembrado (`UnrememberedConsentService`): cada código pede confirmação.
Sem isso, um link com o `user_code` de outra pessoa entregaria a ela um token de quem já está com a sessão aberta.

**Logout:** `/connect/logout` encerra a sessão do servidor. Um access token já emitido vale até o `exp`.

### D5 — Tokens e chaves

| Campo do access token | Valor |
|---|---|
| `kid` (cabeçalho) | Identificador da chave de assinatura |
| `iss` | URL pública do servidor (`AUTH_ISSUER`) |
| `sub` | Identificador estável da credencial (D6), opaco para as APIs |
| `aud` | `app.auth.audience`: a API do projeto |
| `email`, `scope` | E-mail atual e escopos concedidos |
| `iat`, `exp` | Validade do cliente (D3) |

- `sub`, `email` e `aud` são definidos pelo `TokenClaimsCustomizer`. Sem ele, o SAS usaria o e-mail (nome do
  principal) como `sub` e o `client_id` como `aud`. O `id_token` também leva o `email`.
- O SAS não grava o cabeçalho `typ`; o `aud` já impede que um `id_token` seja aceito como access token.
- **RS256 com RSA 2048**, lida de um PEM PKCS#8 (`AUTH_SIGNING_KEY_FILE`, `AUTH_SIGNING_KEY_ID`). Sem o arquivo, uma
  chave é gerada na subida e os tokens deixam de valer quando o processo reinicia. O perfil `local` usa uma chave de
  desenvolvimento versionada, que nunca deve ser usada fora dele.
- As APIs só conhecem chaves públicas: invadir uma API não permite emitir tokens.
- **Rotação:** o JWKS publica a chave atual e a anterior (`APP_AUTH_PREVIOUS_SIGNING_KEY_*`); só a atual assina; a
  anterior sai depois do tempo de vida de um access token.

### D6 — Dados

- Tudo no schema `auth` do PostgreSQL, com histórico Flyway próprio e, fora do ambiente local, usuário de banco
  próprio. O banco pode ser o mesmo do projeto.
- `credentials`: id, `subject`, e-mail, hash da senha e datas. O `subject` de uma conta nova é um UUID aleatório; um
  projeto que importa contas antigas pode usar outro formato, porque as APIs o tratam como opaco.
- `oauth2_authorization`: o schema do SAS adaptado ao PostgreSQL (`blob` vira `text`, `timestamp` vira
  `timestamptz`). Guarda códigos, `device_code` e tokens **em claro**; um job diário (`AuthorizationPurgeJob`) apaga as
  autorizações cujos códigos e tokens já expiraram todos.

### D7 — Credenciais e senha

- **bcrypt** pelo `DelegatingPasswordEncoder`: o prefixo no hash permite trocar de algoritmo sem invalidar senhas.
- **Senha** normalizada em NFC, de 8 a 64 caracteres e no máximo 72 bytes em UTF-8 (o limite do bcrypt, que chega
  antes do de caracteres com acentos ou emojis).
- **E-mail** sem espaços nas pontas, em minúsculas e com até 254 caracteres.
- Login com a mesma mensagem para e-mail inexistente e senha errada. No cadastro, e-mail duplicado é informado: é
  uma enumeração aceita conscientemente, contida pelo rate limit (D8).
- A **troca de senha** exige a senha atual e encerra todas as autorizações do usuário: os refresh tokens deixam de
  valer, e os clientes pedem login de novo.

### D8 — Rate limit e Redis

| Regra (`app.rate-limit.<regra>`) | Alvo | Chave | Padrão |
|---|---|---|---|
| `login` / `login-email` | `POST /login` | IP / e-mail | 10 por minuto / 5 em 15 minutos |
| `register` | `POST /register` | IP | 10 por hora |
| `password-change` | `POST /account/password` | usuário | 5 em 15 minutos |
| `token` | `POST /oauth2/token`, cliente público | IP | 30 por minuto |
| `token-client` | `POST /oauth2/token`, cliente confidencial | `client_id` | 600 por minuto |
| `device-authorization` | `POST /oauth2/device_authorization` | IP | 10 por minuto |
| `device-verification` | `POST /oauth2/device_verification` | IP | 10 por minuto |

- Janela fixa no Redis, com `INCR` e `PEXPIRE` atômicos num script Lua. Permite rajadas de até 2× na virada da
  janela (aceito). IPv6 é agregado por /64.
- Os endpoints de protocolo são limitados por um filtro antes da cadeia do Spring Security, com 429 e `Retry-After`
  em `problem+json`. O cliente confidencial é limitado por `client_id`, porque todas as trocas de um BFF saem do mesmo
  IP.
- O login por formulário não passa por um serviço próprio: os limites de IP e e-mail ficam num `AuthenticationProvider`
  (`RateLimitedAuthenticationProvider`) consultado antes do bcrypt, que é lento de propósito. Nas páginas, o limite
  aparece como erro do formulário, e não como JSON.
- **Fail-open:** o Redis é acessório. Timeouts curtos (100 ms de comando, 200 ms de conexão), comandos recusados na
  hora com a conexão caída (`REJECT_COMMANDS`) e um disjuntor (`RedisGuard`: 5 falhas seguidas abrem por 5 s). Sem o
  Redis, os limites liberam.

### D9 — Volta para a aplicação do projeto

- **Cadastro com login automático:** ao fim do cadastro, o usuário fica logado e volta para o `continue` pedido, que
  normalmente é o início do login do cliente; esse login é silencioso.
- Só valem voltas para as origens de `app.auth.panel-origins`; sem uma válida, a volta é `app.auth.panel-default-url`.
  A troca de senha sempre volta para esse endereço fixo.
- O nome nas páginas vem de `app.auth.display-name`. As cores e a tipografia das páginas ficam em `painel.css`; no
  encurtador, são os mesmos valores do frontend dele, copiados.

### D10 — Erros

- Os endpoints de protocolo respondem no formato do OAuth (RFC 6749: `error`, `error_description`), que é o que os
  clientes OAuth esperam. As páginas mostram os erros no próprio formulário.
- O que o servidor responde em JSON fora do protocolo (o 429 do filtro, por exemplo) é `problem+json` (RFC 9457), com
  tipo `urn:auth:problem:<código>`, pelo `auth-support`.
- CORS em `/oauth2/token` só para as origens de `app.cors.allowed-origins` (um Swagger UI que faz login, por exemplo).

### D11 — Tempo e testes

- Toda leitura de tempo passa por um `Clock`, que os testes substituem.
- Testes de integração com PostgreSQL e Redis reais (Testcontainers) e MockMvc: fluxos de código com PKCE e de
  dispositivo, consentimento, cadastro, troca de senha, política de senha, limites, JWKS, rotação de chave, claims e
  expurgo. Os clientes de teste são neutros (`app-cli`, `app-web`, `app-dev`, audiência `app-api`).

## 3. Alternativas consideradas

| Alternativa | Por que não |
|---|---|
| Keycloak | Mais um produto para operar, com o próprio banco e as próprias páginas; o SAS já vem com o Spring. A troca continua possível: as APIs só conhecem emissor, audiência e JWKS |
| Fluxo com senha direto (*resource owner password*) | Desaconselhado pela RFC 9700 (§2.4) e retirado do OAuth 2.1 |
| Tokens opacos com introspecção | Uma chamada ao servidor a cada requisição da API, que pararia junto com ele |

## 4. Limitações conhecidas

- Sessão em memória (D2): uma instância só.
- Tokens guardados em claro em `oauth2_authorization` até o expurgo (D6).
- Sem verificação de e-mail nem exclusão de conta.
