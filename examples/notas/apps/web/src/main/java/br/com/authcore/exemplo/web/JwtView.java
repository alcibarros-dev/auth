package br.com.authcore.exemplo.web;

import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Um JWT aberto para leitura: as três partes separadas e cada claim com a explicação. Só decodifica o Base64 — quem
 * confere a assinatura é o Spring Security, no login (id_token), e a API, a cada requisição (access token). Qualquer
 * um que tenha o token consegue ler o conteúdo: JWT é assinado, não cifrado.
 */
record JwtView(String raw, String header, String payload, String signature, List<Claim> headerClaims,
               List<Claim> claims) {

    /**
     * @param simple    o que o campo quer dizer, em palavras do dia a dia
     * @param technical o detalhe para quem programa: quem grava, quem confere, de onde vem o valor
     */
    record Claim(String name, String value, String simple, String technical) {
    }

    private record Meaning(String simple, String technical) {
    }

    private static final Map<String, Meaning> MEANINGS = Map.ofEntries(
            Map.entry("alg", new Meaning("Como o token foi lacrado.",
                    "RS256: assinatura RSA com SHA-256. O servidor assina com a chave privada; quem confere usa a chave "
                            + "pública de /oauth2/jwks.")),
            Map.entry("kid", new Meaning("Qual chave lacrou o token.",
                    "A API procura a chave com esse id no JWKS. Permite trocar a chave sem invalidar os tokens: o JWKS "
                            + "publica a atual e a anterior.")),
            Map.entry("typ", new Meaning("O tipo do token.",
                    "O servidor não grava este campo; a API aceita a ausência, JWT ou at+jwt.")),
            Map.entry("iss", new Meaning("Quem emitiu o token: o servidor de autorização.",
                    "É o AUTH_ISSUER do servidor. A API só aceita tokens deste emissor (app.jwt.issuer).")),
            Map.entry("sub", new Meaning("Quem é você, por um número que nunca muda.",
                    "O id da conta no servidor de autorização. Não muda se o e-mail mudar. A API o troca pelo id da "
                            + "conta dela (UserIdResolver do módulo contas).")),
            Map.entry("aud", new Meaning("Para quem o token foi feito.",
                    "No access token, a API do projeto (notas-api): ela recusa qualquer outro valor, então um token de "
                            + "outro projeto não entra aqui. No id_token, o próprio cliente (notas-web).")),
            Map.entry("exp", new Meaning("Até quando o token vale.",
                    "15 minutos depois da emissão (access-token-time-to-live). Depois disso a API responde 401 e o "
                            + "cliente pede outro com o refresh token.")),
            Map.entry("iat", new Meaning("Quando o token foi emitido.", "Issued at.")),
            Map.entry("nbf", new Meaning("A partir de quando o token vale.",
                    "Not before. A API tolera 5 s de diferença entre os relógios (clock-skew).")),
            Map.entry("jti", new Meaning("O número de série deste token.",
                    "JWT id: único por emissão. Cada renovação gera outro.")),
            Map.entry("scope", new Meaning("O que você autorizou esta aplicação a fazer em seu nome.",
                    "openid: fazer login e consultar /userinfo; email: ver o seu e-mail. Não diz o que você pode fazer "
                            + "dentro da aplicação. O Spring os vira authorities SCOPE_openid e SCOPE_email.")),
            Map.entry("email", new Meaning("O seu e-mail.",
                    "Posto pelo servidor (TokenClaimsCustomizer), com o valor do momento da emissão.")),
            Map.entry("azp", new Meaning("Qual aplicação pediu o login.",
                    "Authorized party: o cliente para o qual o id_token foi emitido.")),
            Map.entry("auth_time", new Meaning("Quando você digitou a senha.",
                    "Um novo login com a sessão do servidor aberta (cookie AUTHSESSION) não pede senha, e este horário "
                            + "não muda.")),
            Map.entry("sid", new Meaning("O número da sua sessão no servidor de autorização.",
                    "Usado no logout (id_token_hint), para o servidor saber qual sessão encerrar.")),
            Map.entry("nonce", new Meaning("Um código de uso único contra fraude.",
                    "Esta aplicação o gerou ao pedir o login e conferiu na volta: um id_token antigo, copiado, não "
                            + "seria aceito.")),
            Map.entry("at_hash", new Meaning("Liga este id_token ao access token emitido junto.",
                    "Hash do access token.")));

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss");

    static JwtView of(String token, JsonMapper json, ZoneId zone) {
        String[] parts = token.split("\\.");
        Map<String, Object> header = decode(parts[0], json);
        Map<String, Object> payload = decode(parts[1], json);
        return new JwtView(token, parts[0], parts[1], parts[2], claims(header, zone), claims(payload, zone));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> decode(String part, JsonMapper json) {
        byte[] bytes = Base64.getUrlDecoder().decode(part);
        return json.readValue(new String(bytes, StandardCharsets.UTF_8), LinkedHashMap.class);
    }

    private static List<Claim> claims(Map<String, Object> values, ZoneId zone) {
        List<Claim> claims = new ArrayList<>();
        values.forEach((name, value) -> {
            Meaning meaning = MEANINGS.getOrDefault(name, new Meaning("—", ""));
            claims.add(new Claim(name, format(name, value, zone), meaning.simple(), meaning.technical()));
        });
        return claims;
    }

    private static String format(String name, Object value, ZoneId zone) {
        if (value instanceof Number seconds && List.of("exp", "iat", "nbf", "auth_time").contains(name)) {
            return seconds + "  (" + TIME.format(Instant.ofEpochSecond(seconds.longValue()).atZone(zone)) + ")";
        }
        return String.valueOf(value);
    }
}
