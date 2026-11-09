package br.com.authcore.exemplo.api;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A API inteira, com o banco real. O {@code jwt()} do spring-security-test entrega um token já validado: a
 * assinatura, o emissor e a audiência são testados no repositório do auth, e aqui o que importa é o que cada módulo
 * faz com o usuário autenticado.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class NotasApiTest {

    @Autowired
    MockMvc mvc;

    /** Um usuário novo por teste: o {@code sub} é o que o servidor de autorização poria no token. */
    private static JwtRequestPostProcessor usuario() {
        String sub = UUID.randomUUID().toString();
        return jwt().jwt(token -> token.subject(sub).claim("email", sub + "@exemplo.com"));
    }

    @Test
    void rotaPublicaDeclaradaPeloModuloNaoExigeToken() throws Exception {
        mvc.perform(get("/api/v1/sobre"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nome").value("API de notas"));
    }

    @Test
    void semTokenOAuthSecurityResponde401EmProblemJson() throws Exception {
        mvc.perform(get("/api/v1/notas"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentType("application/problem+json"))
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    void rotaForaDeApiV1ENegada() throws Exception {
        mvc.perform(get("/qualquer").with(usuario()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }

    @Test
    void primeiroAcessoCriaAContaEOsSeguintesReusam() throws Exception {
        JwtRequestPostProcessor usuario = usuario();
        String primeira = mvc.perform(get("/api/v1/eu").with(usuario))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").exists())
                .andReturn().getResponse().getContentAsString();
        long id = ((Number) JsonPath.read(primeira, "$.id")).longValue();

        mvc.perform(get("/api/v1/eu").with(usuario)).andExpect(jsonPath("$.id").value(id));
    }

    @Test
    void criaListaBuscaEApagaAsProprias() throws Exception {
        JwtRequestPostProcessor usuario = usuario();
        String criada = mvc.perform(post("/api/v1/notas").with(usuario)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"titulo\":\"Mercado\",\"texto\":\"Pão e café\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.titulo").value("Mercado"))
                .andReturn().getResponse().getContentAsString();
        long id = ((Number) JsonPath.read(criada, "$.id")).longValue();

        mvc.perform(get("/api/v1/notas").with(usuario))
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id").value(id));
        mvc.perform(get("/api/v1/notas/" + id).with(usuario))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.texto").value("Pão e café"));
        mvc.perform(delete("/api/v1/notas/" + id).with(usuario)).andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/notas").with(usuario)).andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    void notaDeOutroUsuarioE404ComCodigoDoNegocio() throws Exception {
        String criada = mvc.perform(post("/api/v1/notas").with(usuario())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"titulo\":\"Segredo\"}"))
                .andReturn().getResponse().getContentAsString();
        long id = ((Number) JsonPath.read(criada, "$.id")).longValue();

        mvc.perform(get("/api/v1/notas/" + id).with(usuario()))
                .andExpect(status().isNotFound())
                .andExpect(content().contentType("application/problem+json"))
                .andExpect(jsonPath("$.code").value("NOTA_NAO_ENCONTRADA"))
                .andExpect(jsonPath("$.type").value("urn:auth:problem:nota-nao-encontrada"));
    }

    @Test
    void validacaoSaiNoMesmoFormato() throws Exception {
        mvc.perform(post("/api/v1/notas").with(usuario())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"titulo\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType("application/problem+json"))
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.errors[0].field").value("titulo"));
    }

    @Test
    void metadataDoRecursoProtegidoTemONomeDaApi() throws Exception {
        mvc.perform(get("/.well-known/oauth-protected-resource"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resource_name").value("API de notas"));
    }
}
