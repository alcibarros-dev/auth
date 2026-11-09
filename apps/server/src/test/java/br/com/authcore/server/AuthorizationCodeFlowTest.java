package br.com.authcore.server;

import br.com.authcore.server.support.AuthServerTest;
import br.com.authcore.server.support.OAuthFlows;
import br.com.authcore.server.support.OAuthFlows.Pkce;
import br.com.authcore.server.support.OAuthFlows.Tokens;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.util.UriComponentsBuilder;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Código de autorização com PKCE, claims dos tokens e refresh token do cliente confidencial (ADR-0001, D3,
 * D4 e D5). */
@AuthServerTest
class AuthorizationCodeFlowTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    OAuthFlows flows;

    @Autowired
    JdbcClient jdbc;

    @Test
    void browserWithoutSessionIsSentToTheLoginPage() throws Exception {
        mvc.perform(get(OAuthFlows.authorizeUri(OAuthFlows.WEB_CLIENT, OAuthFlows.WEB_REDIRECT, "openid email",
                        Pkce.create())).accept(MediaType.TEXT_HTML))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"));
    }

    @Test
    void loginContinuesTheAuthorizationAndIssuesTheTokensWithTheAppClaims() throws Exception {
        String email = flows.newAccount();
        String subject = jdbc.sql("select subject from credentials where email = :email").param("email", email)
                .query(String.class).single();
        MockHttpSession session = flows.login(email, OAuthFlows.PASSWORD).session();

        Tokens tokens = flows.authorizationCode(session);

        Jwt access = flows.decode(tokens.accessToken());
        assertThat(access.getSubject()).isEqualTo(subject);
        assertThat(access.getAudience()).containsExactly("app-api");
        assertThat(access.getClaimAsString("email")).isEqualTo(email);
        assertThat(access.getClaimAsString("iss")).isEqualTo("http://localhost:8090");
        assertThat(Duration.between(access.getIssuedAt(), access.getExpiresAt())).isEqualTo(Duration.ofMinutes(15));
        assertThat(access.getHeaders()).containsEntry("kid", "test-1").containsEntry("alg", "RS256");

        Jwt id = flows.decode(tokens.idToken());
        assertThat(id.getSubject()).isEqualTo(subject);
        assertThat(id.getClaimAsString("email")).isEqualTo(email);
        assertThat(id.getAudience()).containsExactly(OAuthFlows.WEB_CLIENT);
        assertThat(tokens.refreshToken()).isNotBlank();
    }

    @Test
    void refreshTokenIsReusedWithoutRotationUntilRevoked() throws Exception {
        MockHttpSession session = flows.login(flows.newAccount(), OAuthFlows.PASSWORD).session();
        Tokens tokens = flows.authorizationCode(session);

        Tokens first = flows.tokens(flows.refresh(tokens.refreshToken()));
        Tokens second = flows.tokens(flows.refresh(tokens.refreshToken()));

        assertThat(first.accessToken()).isNotBlank().isNotEqualTo(tokens.accessToken());
        assertThat(first.refreshToken()).isEqualTo(tokens.refreshToken());
        assertThat(second.accessToken()).isNotBlank();

        mvc.perform(post("/oauth2/revoke").with(httpBasic(OAuthFlows.WEB_CLIENT, OAuthFlows.WEB_SECRET))
                        .param("token", tokens.refreshToken()).param("token_type_hint", "refresh_token"))
                .andExpect(status().isOk());
        MvcResult revoked = flows.refresh(tokens.refreshToken());
        assertThat(revoked.getResponse().getStatus()).isEqualTo(400);
        assertThat(flows.body(revoked).get("error").asString()).isEqualTo("invalid_grant");
    }

    @Test
    void publicClientUsesPkceAndReceivesNoRefreshToken() throws Exception {
        MockHttpSession session = flows.login(flows.newAccount(), OAuthFlows.PASSWORD).session();
        Pkce pkce = Pkce.create();
        String redirect = "http://localhost:8080/swagger-ui/oauth2-redirect.html";
        String location = flows.authorize(session, "app-dev", redirect, "openid email", pkce);
        String code = location.replaceAll(".*[?&]code=([^&]+).*", "$1");

        mvc.perform(post("/oauth2/token").param("client_id", "app-dev").param("grant_type", "authorization_code")
                        .param("code", code).param("redirect_uri", redirect).param("code_verifier", pkce.verifier()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.access_token").isNotEmpty())
                .andExpect(jsonPath("$.refresh_token").doesNotExist());
    }

    @Test
    void wrongCodeVerifierIsRejected() throws Exception {
        MockHttpSession session = flows.login(flows.newAccount(), OAuthFlows.PASSWORD).session();
        String location = flows.authorize(session, OAuthFlows.WEB_CLIENT, OAuthFlows.WEB_REDIRECT, "openid email",
                Pkce.create());
        String code = location.replaceAll(".*[?&]code=([^&]+).*", "$1");

        mvc.perform(post("/oauth2/token").with(httpBasic(OAuthFlows.WEB_CLIENT, OAuthFlows.WEB_SECRET))
                        .param("grant_type", "authorization_code").param("code", code)
                        .param("redirect_uri", OAuthFlows.WEB_REDIRECT).param("code_verifier", Pkce.create().verifier()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_grant"));
    }

    @Test
    void discoveryAndJwksPublishTheCurrentAndThePreviousKey() throws Exception {
        mvc.perform(get("/.well-known/openid-configuration"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.issuer").value("http://localhost:8090"))
                .andExpect(jsonPath("$.device_authorization_endpoint").value("http://localhost:8090/oauth2/device_authorization"));
        mvc.perform(get("/oauth2/jwks"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.keys[*].kid").value(org.hamcrest.Matchers.containsInAnyOrder("test-1", "test-0")))
                .andExpect(jsonPath("$.keys[0].d").doesNotExist());
    }

    @Test
    void logoutEndsTheSessionAndReturnsToThePanel() throws Exception {
        MockHttpSession session = flows.login(flows.newAccount(), OAuthFlows.PASSWORD).session();
        Tokens tokens = flows.authorizationCode(session);

        // Como o navegador manda: parâmetros na query string.
        mvc.perform(get(UriComponentsBuilder.fromPath("/connect/logout")
                        .queryParam("id_token_hint", tokens.idToken())
                        .queryParam("post_logout_redirect_uri", "http://localhost:8070/").encode().build().toUri())
                        .session(session))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("http://localhost:8070/"));
        assertThat(session.isInvalid()).isTrue();
    }

    /**
     * A troca de senha encerra as autorizações, e o id_token da sessão deixa de ser conhecido: o logout encerra a
     * sessão daqui mesmo assim e volta ao endereço fixo da aplicação, ignorando o pedido (ADR-0001, D9).
     */
    @Test
    void logoutWithAnIdTokenThatIsNoLongerKnownStillEndsTheSession() throws Exception {
        MockHttpSession session = flows.login(flows.newAccount(), OAuthFlows.PASSWORD).session();
        Tokens tokens = flows.authorizationCode(session);
        jdbc.sql("delete from oauth2_authorization").update();

        mvc.perform(get(UriComponentsBuilder.fromPath("/connect/logout")
                        .queryParam("id_token_hint", tokens.idToken())
                        .queryParam("post_logout_redirect_uri", "https://atacante.example/").encode().build().toUri())
                        .session(session))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("http://localhost:8070/"));
        assertThat(session.isInvalid()).isTrue();
    }
}
