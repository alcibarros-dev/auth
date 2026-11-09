package br.com.authcore.exemplo.web;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.HttpSessionOAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestCustomizers;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Login pelo fluxo de código de autorização. O que muda em relação ao padrão do Spring Security:
 * <ul>
 *     <li>PKCE ligado também para este cliente confidencial: o Spring só o liga sozinho para cliente público, e o
 *     servidor de autorização o exige ({@code require-proof-key});</li>
 *     <li>a página inicial é a de entrada, e o login volta sempre para {@code /painel}.</li>
 * </ul>
 * O CSRF continua ligado: os botões do painel são formulários POST.
 */
@Configuration(proxyBeanMethods = false)
class SecurityConfig {

    @Bean
    SecurityFilterChain web(HttpSecurity http, ClientRegistrationRepository registrations) throws Exception {
        var resolver = new DefaultOAuth2AuthorizationRequestResolver(registrations, "/oauth2/authorization");
        resolver.setAuthorizationRequestCustomizer(OAuth2AuthorizationRequestCustomizers.withPkce());

        http.authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/", "/css/**", "/favicon.ico", "/error").permitAll()
                        .anyRequest().authenticated())
                .oauth2Login(login -> login
                        .loginPage("/")
                        .authorizationEndpoint(endpoint -> endpoint.authorizationRequestResolver(resolver))
                        .defaultSuccessUrl("/painel", true))
                // O logout é do PainelController: revoga o refresh token e passa pelo logout do servidor.
                .logout(logout -> logout.disable());
        return http.build();
    }

    /**
     * Os tokens de cada usuário ficam na sessão HTTP dele, na memória deste servidor. O navegador só recebe o cookie
     * da sessão (NOTASWEB), que não diz nada sozinho.
     */
    @Bean
    OAuth2AuthorizedClientRepository authorizedClientRepository() {
        return new HttpSessionOAuth2AuthorizedClientRepository();
    }
}
