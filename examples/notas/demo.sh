#!/usr/bin/env bash
# Demonstração de ponta a ponta, com a stack no ar (docker compose --profile app up -d --build):
#
#   1. a CLI pede um código ao servidor de autorização (fluxo de dispositivo, cliente notas-cli);
#   2. o "usuário" se cadastra e confirma o código nas páginas do servidor (aqui, com curl no lugar do navegador);
#   3. a CLI troca o código por um access token;
#   4. a CLI usa a API com o token: conta, notas, erros.
#
# Endereços: AUTH_URL (padrão http://localhost:8190) e API_URL (padrão http://localhost:8180). Cada execução cria uma
# conta nova, com e-mail e senha aleatórios.
set -euo pipefail

AUTH_URL=${AUTH_URL:-http://localhost:8190}
API_URL=${API_URL:-http://localhost:8180}
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT
COOKIES="$WORK/cookies"

step() { printf '\n==> %s\n' "$*"; }
fail() { printf 'FALHOU: %s\n' "$*" >&2; exit 1; }
field() { python3 -c 'import json, sys; print(json.load(sys.stdin)[sys.argv[1]])' "$1"; }
pretty() { python3 -c 'import json, sys; print(json.dumps(json.load(sys.stdin), indent=4, ensure_ascii=False))'; }
# Campos de um formulário HTML (inputs com nome e valor), como corpo x-www-form-urlencoded.
form_fields() {
    python3 -c '
import html, re, sys, urllib.parse
fields = []
for tag in re.findall(r"<input\b[^>]*>", sys.stdin.read()):
    name = re.search(r"\bname=\"([^\"]*)\"", tag)
    value = re.search(r"\bvalue=\"([^\"]*)\"", tag)
    if name and value:
        fields.append((name.group(1), html.unescape(value.group(1))))
print(urllib.parse.urlencode(fields))'
}
csrf() { form_fields | tr '&' '\n' | sed -n 's/^_csrf=//p' | head -1; }
browser() { curl -sS -b "$COOKIES" -c "$COOKIES" "$@"; }

wait_ready() {
    for _ in $(seq 1 90); do
        curl -fsS -o /dev/null "$1/readyz" 2>/dev/null && return 0
        sleep 2
    done
    fail "$1 não ficou pronto"
}

step "Esperando o servidor de autorização e a API"
wait_ready "$AUTH_URL"
wait_ready "$API_URL"

step "Rota pública, declarada pelo módulo notas (PublicRoutes): sem token"
curl -fsS "$API_URL/api/v1/sobre" | pretty

step "Rota protegida sem token: 401 do auth-security, em problem+json"
status=$(curl -sS -o "$WORK/401" -w '%{http_code}' "$API_URL/api/v1/notas")
[[ $status == 401 ]] || fail "sem token respondeu $status"
pretty <"$WORK/401"

step "CLI pede um código ao servidor de autorização (fluxo de dispositivo)"
device=$(curl -fsS -X POST "$AUTH_URL/oauth2/device_authorization" -d client_id=notas-cli -d scope=email)
device_code=$(field device_code <<<"$device")
user_code=$(field user_code <<<"$device")
echo "Abra $(field verification_uri <<<"$device") e digite: $user_code"

step "Usuário se cadastra na página do servidor (fica logado ao fim)"
email="demo-$(date +%s)-$RANDOM@exemplo.com"
password=$(openssl rand -hex 12)
token=$(browser "$AUTH_URL/register" | csrf)
status=$(browser -o /dev/null -w '%{http_code}' -X POST "$AUTH_URL/register" \
    --data-urlencode "email=$email" --data-urlencode "password=$password" --data-urlencode "_csrf=$token")
[[ $status == 302 ]] || fail "cadastro respondeu $status"
echo "conta: $email"

step "Usuário digita o código e autoriza a CLI"
token=$(browser "$AUTH_URL/activate?user_code=$user_code" | csrf)
consent=$(browser -X POST "$AUTH_URL/oauth2/device_verification" \
    --data-urlencode "user_code=$user_code" --data-urlencode "_csrf=$token")
grep -q 'name="state"' <<<"$consent" || fail "página de consentimento sem state"
status=$(browser -o /dev/null -w '%{http_code}' -X POST "$AUTH_URL/oauth2/device_verification" \
    --data "$(form_fields <<<"$consent")")
[[ $status == 302 ]] || fail "consentimento respondeu $status"

step "CLI troca o código pelo access token"
tokens=$(curl -fsS -X POST "$AUTH_URL/oauth2/token" -d client_id=notas-cli \
    -d grant_type=urn:ietf:params:oauth:grant-type:device_code -d "device_code=$device_code")
access_token=$(field access_token <<<"$tokens")
echo "claims do access token:"
python3 -c 'import base64, json, sys
payload = sys.argv[1].split(".")[1]
print(json.dumps(json.loads(base64.urlsafe_b64decode(payload + "=" * (-len(payload) % 4))), indent=4))' "$access_token"
auth=(-H "Authorization: Bearer $access_token")

step "Primeiro acesso: o módulo contas (UserIdResolver) cria a conta local para o sub do token"
curl -fsS "${auth[@]}" "$API_URL/api/v1/eu" | pretty

step "Módulo notas: cria, lista e busca, com o usuário vindo de @CurrentUser"
nota=$(curl -fsS "${auth[@]}" -X POST "$API_URL/api/v1/notas" -H 'Content-Type: application/json' \
    -d '{"titulo":"Primeira nota","texto":"Criada pelo demo.sh"}')
pretty <<<"$nota"
id=$(field id <<<"$nota")
curl -fsS "${auth[@]}" "$API_URL/api/v1/notas" | pretty

step "Erro de negócio (ApiException do auth-support): mesmo formato dos erros de segurança"
status=$(curl -sS -o "$WORK/404" -w '%{http_code}' "${auth[@]}" "$API_URL/api/v1/notas/999999999")
[[ $status == 404 ]] || fail "nota inexistente respondeu $status"
pretty <"$WORK/404"

step "Validação: VALIDATION_ERROR com os campos recusados"
status=$(curl -sS -o "$WORK/400" -w '%{http_code}' "${auth[@]}" -X POST "$API_URL/api/v1/notas" \
    -H 'Content-Type: application/json' -d '{"titulo":""}')
[[ $status == 400 ]] || fail "título vazio respondeu $status"
pretty <"$WORK/400"

step "Apaga a nota"
status=$(curl -sS -o /dev/null -w '%{http_code}' "${auth[@]}" -X DELETE "$API_URL/api/v1/notas/$id")
[[ $status == 204 ]] || fail "remoção respondeu $status"
echo 204

step "Token adulterado: 401 INVALID_TOKEN (a assinatura não confere com o JWKS)"
# Troca o primeiro caractere da assinatura (o último pode só mudar bits de preenchimento, que o base64 ignora).
signature=${access_token##*.}
[[ ${signature:0:1} == A ]] && other=B || other=A
tampered="${access_token%.*}.$other${signature:1}"
status=$(curl -sS -o "$WORK/invalid" -w '%{http_code}' -H "Authorization: Bearer $tampered" \
    "$API_URL/api/v1/notas")
[[ $status == 401 ]] || fail "token adulterado respondeu $status"
pretty <"$WORK/invalid"

printf '\nOK: cadastro e login pela CLI → token → conta → notas → erros\n'
