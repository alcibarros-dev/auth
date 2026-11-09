package br.com.authcore.exemplo.web;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Map;

/** Chamadas à API de notas com o access token da sessão: o que chega do outro lado e o que ela devolve. */
@Component
class ApiCalls {

    private final RestClient http;
    private final String apiUrl;

    ApiCalls(RestClient.Builder builder, @Value("${app.api.url}") String apiUrl) {
        this.http = builder.build();
        this.apiUrl = apiUrl;
    }

    AuthEndpoints.Response get(String path, String accessToken) {
        return http.get().uri(apiUrl + path)
                .headers(headers -> {
                    if (accessToken != null) {
                        headers.setBearerAuth(accessToken);
                    }
                })
                .exchange((request, response) -> new AuthEndpoints.Response(response.getStatusCode().value(),
                        response.bodyTo(String.class)));
    }

    AuthEndpoints.Response createNote(String accessToken, String titulo) {
        return http.post().uri(apiUrl + "/api/v1/notas")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("titulo", titulo, "texto", "Criada pelo painel do app web"))
                .exchange((request, response) -> new AuthEndpoints.Response(response.getStatusCode().value(),
                        response.bodyTo(String.class)));
    }
}
