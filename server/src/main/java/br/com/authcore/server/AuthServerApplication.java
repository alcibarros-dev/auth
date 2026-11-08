package br.com.authcore.server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Servidor de autorização OAuth 2 e OpenID Connect reaproveitável: credenciais, login, cadastro, troca de senha e
 * emissão de tokens. Os clientes, a audiência dos tokens e o nome exibido vêm da configuração de cada projeto.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class AuthServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(AuthServerApplication.class, args);
    }
}
