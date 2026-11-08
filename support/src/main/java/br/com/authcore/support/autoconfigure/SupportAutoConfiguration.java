package br.com.authcore.support.autoconfigure;

import br.com.authcore.support.error.ProblemResponseWriter;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.context.annotation.Bean;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;

/**
 * Registra o {@link ProblemResponseWriter} e um {@link Clock} UTC no servidor e nas aplicações que usam o
 * {@code auth-security}. Os nomes dos beans são próprios para não colidir com os da aplicação, e o {@code Clock} só
 * entra se a aplicação não tiver um, como o que os testes substituem.
 */
@AutoConfiguration(after = JacksonAutoConfiguration.class)
public class SupportAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    ProblemResponseWriter authProblemResponseWriter(JsonMapper jsonMapper) {
        return new ProblemResponseWriter(jsonMapper);
    }

    @Bean
    @ConditionalOnMissingBean
    Clock authClock() {
        return Clock.systemUTC();
    }
}
