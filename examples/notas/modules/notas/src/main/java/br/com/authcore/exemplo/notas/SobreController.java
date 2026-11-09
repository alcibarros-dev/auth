package br.com.authcore.exemplo.notas;

import br.com.authcore.security.PublicRoutes;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Uma rota pública, sem token. O {@code auth-security} exige token em todo {@code /api/v1/**}; cada módulo libera as
 * próprias exceções num bean {@link PublicRoutes}, e só elas.
 */
@RestController
@Tag(name = "Sobre")
class SobreController {

    private final NotaRepository notas;

    SobreController(NotaRepository notas) {
        this.notas = notas;
    }

    record Sobre(String nome, long notasCriadas) {
    }

    @GetMapping("/api/v1/sobre")
    @Operation(summary = "Informações públicas do serviço")
    Sobre sobre() {
        return new Sobre("API de notas", notas.contar());
    }

    @Configuration(proxyBeanMethods = false)
    static class Rotas {

        @Bean
        PublicRoutes rotasPublicasDasNotas() {
            return () -> List.of(PublicRoutes.Route.get("/api/v1/sobre"));
        }
    }
}
