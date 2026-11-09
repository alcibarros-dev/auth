package br.com.authcore.exemplo.contas;

import br.com.authcore.security.CurrentUser;
import br.com.authcore.security.OpenApiSecurity;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Quem está logado, na visão da aplicação: o id local e o e-mail guardado no primeiro acesso. */
@RestController
@RequestMapping("/api/v1/eu")
@Tag(name = "Conta")
@SecurityRequirement(name = OpenApiSecurity.BEARER)
class ContaController {

    private final JdbcClient jdbc;

    ContaController(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    record Conta(long id, String email) {
    }

    @GetMapping
    @Operation(summary = "A conta do usuário do token")
    Conta eu(@CurrentUser long contaId) {
        return jdbc.sql("SELECT id, email FROM contas WHERE id = :id")
                .param("id", contaId)
                .query(Conta.class)
                .single();
    }
}
