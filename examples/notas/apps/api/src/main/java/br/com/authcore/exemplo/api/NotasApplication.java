package br.com.authcore.exemplo.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Escaneia os módulos do exemplo ({@code contas} e {@code notas}). O {@code auth-security} e o {@code auth-support}
 * não precisam de scan: são auto-configurações.
 */
@SpringBootApplication(scanBasePackages = "br.com.authcore.exemplo")
public class NotasApplication {

    public static void main(String[] args) {
        SpringApplication.run(NotasApplication.class, args);
    }
}
