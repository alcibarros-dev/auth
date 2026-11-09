package br.com.authcore.server.ratelimit;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.util.Map;

/**
 * Limites por regra ({@code app.rate-limit.<regra>}, ADR-0001, D8), cada um uma janela fixa. O nome da regra é a
 * chave do mapa: as de login, cadastro, troca de senha e protocolo OAuth.
 */
@Validated
@ConfigurationProperties("app")
public record RateLimitProperties(@NotEmpty Map<String, @Valid Limit> rateLimit) {

    public record Limit(@Min(1) long requests, @NotNull @DurationMin(seconds = 1) Duration window) {
    }
}
