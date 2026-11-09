package br.com.authcore.security.web;

import br.com.authcore.security.UserIdResolver;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Metadata do recurso protegido (RFC 9728): o nome legível vem da API que usa a biblioteca, nunca fixo nela. */
class ProtectedResourceMetadataTest {

    private static final String METADATA = "/.well-known/oauth-protected-resource";

    @Nested
    @SpringBootTest(properties = {
            "app.jwt.issuer=http://auth.test",
            "app.jwt.audience=app-api",
            "app.jwt.jwk-set-uri=http://auth.test/oauth2/jwks",
            "app.jwt.clock-skew=5s",
            "app.jwt.resource-name=Minha API"})
    @AutoConfigureMockMvc
    class WithName {

        @Autowired
        MockMvc mvc;

        @MockitoBean
        UserIdResolver users;

        @Test
        void publishesTheConfiguredName() throws Exception {
            mvc.perform(get(METADATA))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.resource_name").value("Minha API"))
                    .andExpect(jsonPath("$.tls_client_certificate_bound_access_tokens").value(false));
        }
    }

    @Nested
    @SpringBootTest(properties = {
            "app.jwt.issuer=http://auth.test",
            "app.jwt.audience=app-api",
            "app.jwt.jwk-set-uri=http://auth.test/oauth2/jwks",
            "app.jwt.clock-skew=5s"})
    @AutoConfigureMockMvc
    class WithoutName {

        @Autowired
        MockMvc mvc;

        @MockitoBean
        UserIdResolver users;

        @Test
        void omitsTheName() throws Exception {
            mvc.perform(get(METADATA))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.resource_name").doesNotExist());
        }
    }
}
