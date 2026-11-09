package br.com.authcore.security.autoconfigure;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurationExcludeFilter;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.security.autoconfigure.web.servlet.ServletWebSecurityAutoConfiguration;
import org.springframework.boot.security.oauth2.server.resource.autoconfigure.OAuth2ResourceServerAutoConfiguration;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;

/**
 * Segurança da API (ADR-0002, D2 e D6): registra os beans de {@code br.com.authcore.security} numa aplicação que
 * não escaneia esse pacote.
 * <p>
 * Se a aplicação também escanear esse pacote, a varredura daqui reencontra as mesmas classes e não as registra de
 * novo. O {@code @SpringBootApplication} exclui do scan as classes listadas como auto-configuração, como
 * esta.
 * <p>
 * A cadeia de filtros e o {@code JwtDecoder} vêm antes dos padrões do Spring Boot, que só existem na falta deles.
 */
@AutoConfiguration(before = {ServletWebSecurityAutoConfiguration.class, OAuth2ResourceServerAutoConfiguration.class,
        UserDetailsServiceAutoConfiguration.class})
@ComponentScan(basePackages = "br.com.authcore.security",
        excludeFilters = @ComponentScan.Filter(type = FilterType.CUSTOM,
                classes = AutoConfigurationExcludeFilter.class))
@ConfigurationPropertiesScan("br.com.authcore.security")
public class AppSecurityAutoConfiguration {
}
