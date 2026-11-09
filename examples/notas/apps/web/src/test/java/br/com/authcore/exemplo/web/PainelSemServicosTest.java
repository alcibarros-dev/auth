package br.com.authcore.exemplo.web;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2AuthorizationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.oauth2.core.user.OAuth2UserAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oauth2Client;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Usuário logado, com a API e o servidor de autorização fora do ar (endereços numa porta fechada): a página continua
 * abrindo e diz que não houve resposta, em vez de quebrar. A sessão e os tokens são os do spring-security-test.
 */
@SpringBootTest(properties = {"app.api.url=http://127.0.0.1:1", "app.auth.internal-url=http://127.0.0.1:1"})
@AutoConfigureMockMvc
class PainelSemServicosTest {

    private static final String ACCESS_TOKEN = jwt("{\"sub\":\"abc\",\"aud\":\"notas-api\",\"jti\":\"serie-1\"}");
    private static final String ID_TOKEN = jwt("{\"sub\":\"abc\",\"aud\":\"notas-web\",\"email\":\"ana@exemplo.com\"}");

    @Autowired
    MockMvc mvc;

    @Autowired
    PainelController painel;

    @Test
    void painelAbreEMostraQueNaoHouveResposta() throws Exception {
        mvc.perform(get("/painel").with(usuario()).with(tokens()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("ana@exemplo.com")))
                .andExpect(content().string(containsString("sem resposta")))
                .andExpect(content().string(containsString("Não houve resposta")));
    }

    @Test
    void botoesAvisamEmVezDeQuebrar() throws Exception {
        for (String botao : List.of("/painel/renovar", "/painel/revogar")) {
            mvc.perform(post(botao).with(usuario()).with(tokens()).with(csrf()))
                    .andExpect(status().isFound())
                    .andExpect(redirectedUrl("/painel#acoes"))
                    .andExpect(flash().attributeExists("result"));
        }
        mvc.perform(post("/painel/nota").param("titulo", "Uma nota").with(usuario()).with(tokens()).with(csrf()))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("/painel#api"));
    }

    @Test
    void sairFuncionaSemConseguirRevogar() throws Exception {
        mvc.perform(post("/sair").with(usuario()).with(tokens()).with(csrf()))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("http://localhost:8190/connect/logout?id_token_hint=" + ID_TOKEN
                        + "&post_logout_redirect_uri=http://localhost:8170/"));
    }

    /**
     * Quando o access token vence, o Spring Security renova sozinho ao montar a página. Refresh token recusado:
     * login de novo. Servidor sem resposta: 503, e a sessão fica.
     */
    @Test
    void renovacaoAutomaticaQueFalhaNaoViraErro500() {
        assertThat(painel.renovacaoFalhou(new OAuth2AuthorizationException(new OAuth2Error("invalid_grant"))))
                .isEqualTo("redirect:/oauth2/authorization/notas");

        Object semResposta = painel.renovacaoFalhou(
                new OAuth2AuthorizationException(new OAuth2Error("invalid_token_response")));
        assertThat(semResposta).isInstanceOfSatisfying(ResponseEntity.class, response -> {
            assertThat(response.getStatusCode().value()).isEqualTo(503);
            assertThat((String) response.getBody()).contains("A sua sessão continua");
        });
    }

    private static RequestPostProcessor usuario() {
        var idToken = new OidcIdToken(ID_TOKEN, Instant.now(), Instant.now().plusSeconds(900),
                Map.of("sub", "abc", "email", "ana@exemplo.com"));
        OidcUser user = new DefaultOidcUser(Set.of(new OAuth2UserAuthority(Map.of("sub", "abc"))), idToken);
        return oidcLogin().oidcUser(user);
    }

    /** Access token ainda válido e sem refresh token: nada aqui tenta renovar antes de chamar os serviços. */
    private static RequestPostProcessor tokens() {
        return oauth2Client("notas").accessToken(new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER,
                ACCESS_TOKEN, Instant.now(), Instant.now().plusSeconds(900), Set.of("openid", "email")));
    }

    private static String jwt(String payload) {
        return part("{\"kid\":\"auth-1\",\"alg\":\"RS256\"}") + "." + part(payload) + ".assinatura";
    }

    private static String part(String json) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }
}
