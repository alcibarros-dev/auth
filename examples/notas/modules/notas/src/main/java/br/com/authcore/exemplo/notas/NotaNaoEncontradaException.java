package br.com.authcore.exemplo.notas;

import br.com.authcore.support.error.ApiException;
import org.springframework.http.HttpStatus;

/**
 * Erro de negócio com código estável, do {@code auth-support}: sai em {@code problem+json}, no mesmo formato dos 401 e
 * 403 do {@code auth-security}. Nota de outro usuário também é 404, para não revelar que existe.
 */
class NotaNaoEncontradaException extends ApiException {

    NotaNaoEncontradaException(long id) {
        super(HttpStatus.NOT_FOUND, "NOTA_NAO_ENCONTRADA", "Nota " + id + " não encontrada.");
    }
}
