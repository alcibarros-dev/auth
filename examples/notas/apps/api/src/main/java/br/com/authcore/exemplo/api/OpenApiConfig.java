package br.com.authcore.exemplo.api;

import br.com.authcore.security.OpenApiSecurity;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.OAuthFlow;
import io.swagger.v3.oas.models.security.OAuthFlows;
import io.swagger.v3.oas.models.security.Scopes;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Documentação em {@code /swagger-ui.html}. O esquema bearer (colar um token) vem do {@code auth-security}; aqui entra
 * o login pelo servidor de autorização no botão "Authorize", pelo fluxo de código com PKCE (cliente
 * {@code notas-swagger}).
 */
@Configuration(proxyBeanMethods = false)
class OpenApiConfig {

    private static final String OAUTH2 = "oauth2";

    @Bean
    OpenAPI openApi() {
        return new OpenAPI().info(new Info()
                .title("API de notas")
                .version("1.0")
                .description("Exemplo de uso do auth. As rotas de /api/v1 exigem um access token do servidor de "
                        + "autorização, menos /api/v1/sobre. Erros em application/problem+json com um code estável."));
    }

    /** Toda operação que aceita o bearer passa a aceitar também o token do login pelo Swagger UI. */
    @Bean
    OpenApiCustomizer loginPeloServidor(@Value("${app.openapi.authorization-url}") String authorizationUrl,
                                        @Value("${app.openapi.token-url}") String tokenUrl) {
        return openApi -> {
            openApi.getComponents().addSecuritySchemes(OAUTH2, new SecurityScheme()
                    .type(SecurityScheme.Type.OAUTH2)
                    .flows(new OAuthFlows().authorizationCode(new OAuthFlow()
                            .authorizationUrl(authorizationUrl)
                            .tokenUrl(tokenUrl)
                            .scopes(new Scopes().addString("openid", "OpenID Connect").addString("email", "E-mail")))));
            openApi.getPaths().values().forEach(path -> path.readOperations().forEach(operation -> {
                if (operation.getSecurity() != null && operation.getSecurity().stream()
                        .anyMatch(requirement -> requirement.containsKey(OpenApiSecurity.BEARER))) {
                    operation.addSecurityItem(new SecurityRequirement().addList(OAUTH2));
                }
            }));
        };
    }
}
