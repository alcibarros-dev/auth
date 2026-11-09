package br.com.authcore.exemplo.web;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

/**
 * Chamadas que este servidor web faz direto ao servidor de autorização, com as credenciais do cliente (client_id e
 * segredo, em Basic). Ficam explícitas aqui para a página mostrar o pedido e a resposta de cada uma; numa aplicação
 * real, o Spring Security faz a renovação sozinho.
 */
@Component
class AuthEndpoints {

    private final RestClient http;
    private final String internalUrl;

    AuthEndpoints(RestClient.Builder builder, @Value("${app.auth.internal-url}") String internalUrl) {
        this.http = builder.build();
        this.internalUrl = internalUrl;
    }

    /** Resposta crua: status e corpo, mesmo nos erros, para a página mostrar. */
    record Response(int status, String body) {
    }

    /** RFC 6749, §6: refresh token por um access token novo. */
    Response refresh(ClientRegistration client, String refreshToken) {
        return post(client, "/oauth2/token", form("grant_type", "refresh_token", "refresh_token", refreshToken));
    }

    /** RFC 7662: o servidor diz se o token está ativo e o que ele significa. Só clientes autenticados perguntam. */
    Response introspect(ClientRegistration client, String token) {
        return post(client, "/oauth2/introspect", form("token", token));
    }

    /** RFC 7009: o token deixa de valer no servidor. */
    Response revoke(ClientRegistration client, String token) {
        return post(client, "/oauth2/revoke", form("token", token));
    }

    /** OpenID Connect: os dados do usuário, com o access token (exige o escopo openid). */
    Response userInfo(String accessToken) {
        return http.get().uri(internalUrl + "/userinfo")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .exchange((request, response) -> new Response(response.getStatusCode().value(),
                        response.bodyTo(String.class)));
    }

    /** As chaves públicas: o que a API usa para conferir a assinatura dos access tokens. */
    Response jwks() {
        return http.get().uri(internalUrl + "/oauth2/jwks")
                .exchange((request, response) -> new Response(response.getStatusCode().value(),
                        response.bodyTo(String.class)));
    }

    private Response post(ClientRegistration client, String path, MultiValueMap<String, String> body) {
        return http.post().uri(internalUrl + path)
                .headers(headers -> headers.setBasicAuth(client.getClientId(), client.getClientSecret()))
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(body)
                .exchange((request, response) -> new Response(response.getStatusCode().value(),
                        response.bodyTo(String.class)));
    }

    private static MultiValueMap<String, String> form(String... pairs) {
        var form = new LinkedMultiValueMap<String, String>();
        for (int i = 0; i < pairs.length; i += 2) {
            form.add(pairs[i], pairs[i + 1]);
        }
        return form;
    }
}
