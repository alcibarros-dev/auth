package br.com.authcore.server.config;

import br.com.authcore.server.config.AuthServerProperties.SigningKey;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.URI;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Só voltas para as origens da aplicação; o resto cai no endereço padrão (ADR-0001, D9). */
class PanelRedirectsTest {

    private final PanelRedirects redirects = new PanelRedirects(new AuthServerProperties("app-api",
            new SigningKey("k", null), null,
            List.of(URI.create("http://localhost:8070"), URI.create("https://app.example.com")),
            URI.create("https://app.example.com/")));

    @ParameterizedTest
    @ValueSource(strings = {"http://localhost:8070/conta", "https://app.example.com/links?x=1",
            "https://app.example.com:443/", "HTTP://LOCALHOST:8070/"})
    void acceptsThePanelOrigins(String target) {
        assertThat(redirects.target(target)).isEqualTo(target);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"https://atacante.example/", "http://localhost:8071/", "https://app.example.com.evil/",
            "/conta", "//app.example.com/", "https://user@app.example.com/", "javascript:alert(1)",
            "http://[::1"})
    void anythingElseGoesToTheDefault(String target) {
        assertThat(redirects.target(target)).isEqualTo("https://app.example.com/");
    }
}
