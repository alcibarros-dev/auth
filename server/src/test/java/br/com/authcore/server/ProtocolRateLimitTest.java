package br.com.authcore.server;

import br.com.authcore.server.support.AuthServerTest;
import br.com.authcore.server.support.OAuthFlows;
import br.com.authcore.server.ratelimit.RateLimitProperties;
import br.com.authcore.server.ratelimit.RateLimiter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Limites dos endpoints de protocolo (ADR-0001, D8): o cliente confidencial é limitado pelo
 * client_id, e o público, pelo IP.
 */
@AuthServerTest
class ProtocolRateLimitTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    StringRedisTemplate redis;

    @Autowired
    RateLimitProperties limits;

    private final List<String> exhausted = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        redis.delete(exhausted);
    }

    @Test
    void confidentialClientIsLimitedByItsClientId() throws Exception {
        exhaust("token-client", OAuthFlows.WEB_CLIENT);

        mvc.perform(post("/oauth2/token").with(httpBasic(OAuthFlows.WEB_CLIENT, OAuthFlows.WEB_SECRET))
                        .param("grant_type", "refresh_token").param("refresh_token", "qualquer"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"));
    }

    @Test
    void publicDeviceRequestsAreLimitedByIp() throws Exception {
        exhaust("device-authorization", "127.0.0.1");

        mvc.perform(post("/oauth2/device_authorization").param("client_id", "app-cli"))
                .andExpect(status().isTooManyRequests());
    }

    private void exhaust(String rule, String subject) {
        RateLimitProperties.Limit limit = limits.rateLimit().get(rule);
        String key = RateLimiter.key(rule, subject, limit.window(), Instant.now());
        redis.opsForValue().set(key, Long.toString(limit.requests()), Duration.ofMinutes(5));
        exhausted.add(key);
    }
}
