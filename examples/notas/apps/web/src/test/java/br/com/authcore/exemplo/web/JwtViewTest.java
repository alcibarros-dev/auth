package br.com.authcore.exemplo.web;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

class JwtViewTest {

    private static String part(String json) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void abreAsPartesEExplicaCadaClaim() {
        String token = part("{\"kid\":\"auth-1\",\"alg\":\"RS256\"}") + "."
                + part("{\"sub\":\"abc\",\"aud\":\"notas-api\",\"exp\":0,\"outro\":1}") + ".assinatura";

        JwtView view = JwtView.of(token, JsonMapper.builder().build(), ZoneId.of("UTC"));

        assertThat(view.signature()).isEqualTo("assinatura");
        assertThat(view.headerClaims()).extracting(JwtView.Claim::name).containsExactly("kid", "alg");
        assertThat(view.claims()).extracting(JwtView.Claim::name).containsExactly("sub", "aud", "exp", "outro");
        assertThat(view.claims().get(2).value()).isEqualTo("0  (01/01/1970 00:00:00)");
        assertThat(view.claims().get(0).simple()).contains("nunca muda");
        assertThat(view.claims().get(0).technical()).contains("UserIdResolver");
        assertThat(view.claims().get(3).simple()).isEqualTo("—");
    }
}
