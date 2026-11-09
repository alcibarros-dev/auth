package br.com.authcore.exemplo.notas;

import br.com.authcore.security.CurrentUser;
import br.com.authcore.security.OpenApiSecurity;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.util.List;

/**
 * As notas do usuário logado. O controller não vê o token: recebe o id local pronto em {@code @CurrentUser}. Sem
 * token, a requisição nem chega aqui: o {@code auth-security} responde 401 antes, porque {@code /api/v1/**} exige
 * token e estas rotas não foram declaradas públicas.
 */
@RestController
@RequestMapping("/api/v1/notas")
@Tag(name = "Notas")
@SecurityRequirement(name = OpenApiSecurity.BEARER)
class NotaController {

    private final NotaRepository notas;
    /** O {@code Clock} padrão vem do {@code auth-support}; os testes podem trocar por outro. */
    private final Clock clock;

    NotaController(NotaRepository notas, Clock clock) {
        this.notas = notas;
        this.clock = clock;
    }

    record NovaNota(@NotBlank @Size(max = 120) String titulo, @Size(max = 4000) String texto) {
    }

    @GetMapping
    @Operation(summary = "Lista as notas do usuário")
    List<Nota> listar(@CurrentUser long contaId) {
        return notas.listar(contaId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Cria uma nota")
    Nota criar(@CurrentUser long contaId, @Valid @RequestBody NovaNota nova) {
        return notas.criar(contaId, nova.titulo(), nova.texto() == null ? "" : nova.texto(), clock.instant());
    }

    @GetMapping("/{id}")
    @Operation(summary = "Uma nota do usuário")
    Nota buscar(@CurrentUser long contaId, @PathVariable long id) {
        return notas.buscar(contaId, id).orElseThrow(() -> new NotaNaoEncontradaException(id));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Apaga uma nota do usuário")
    void apagar(@CurrentUser long contaId, @PathVariable long id) {
        if (!notas.apagar(contaId, id)) {
            throw new NotaNaoEncontradaException(id);
        }
    }
}
