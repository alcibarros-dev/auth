package br.com.authcore.server.redis;

import io.lettuce.core.ClientOptions;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.data.redis.autoconfigure.LettuceClientOptionsBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Orçamento de latência do Redis (ADR-0001, D8): os timeouts curtos ficam no {@code application.yml}; aqui fica o
 * que só o código do Lettuce expressa.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(LettuceClientOptionsBuilderCustomizer.class)
class RedisClientConfig {

    /**
     * Sem conexão, o comando falha na hora. O padrão do Lettuce o guardaria numa fila até a reconexão, e cada
     * requisição esperaria o timeout inteiro.
     */
    @Bean
    LettuceClientOptionsBuilderCustomizer rejectCommandsWhileDisconnected() {
        return options -> options.disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS);
    }
}
