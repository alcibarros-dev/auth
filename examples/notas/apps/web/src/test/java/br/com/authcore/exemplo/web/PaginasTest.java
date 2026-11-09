package br.com.authcore.exemplo.web;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Sem servidor de autorização no ar: só o que acontece antes do login. */
@SpringBootTest
@AutoConfigureMockMvc
class PaginasTest {

    @Autowired
    MockMvc mvc;

    @Test
    void inicioEPublicoELevaAoLogin() throws Exception {
        mvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("/oauth2/authorization/notas")));
    }

    @Test
    void painelSemSessaoVoltaAoInicio() throws Exception {
        mvc.perform(get("/painel"))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("/"));
    }

    @Test
    void loginVaiAoServidorComPkce() throws Exception {
        mvc.perform(get("/oauth2/authorization/notas"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", startsWith("http://localhost:8190/oauth2/authorize?")))
                .andExpect(header().string("Location", containsString("client_id=notas-web")))
                .andExpect(header().string("Location", containsString("code_challenge_method=S256")));
    }
}
