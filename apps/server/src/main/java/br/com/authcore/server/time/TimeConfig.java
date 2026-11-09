package br.com.authcore.server.time;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * Toda leitura de tempo passa por este {@link Clock}, que os testes substituem (ADR-0001, D11).
 */
@Configuration(proxyBeanMethods = false)
class TimeConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
