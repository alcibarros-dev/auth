package br.com.authcore.exemplo.api;

import br.com.authcore.support.error.ApiException;
import br.com.authcore.support.error.ProblemDetails;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.util.Map;

/**
 * Erros da aplicação no mesmo formato dos erros de segurança: {@code problem+json} com {@code code} estável e tipo
 * {@code urn:auth:problem:<código>}, montados pelo {@link ProblemDetails} do {@code auth-support}. Os 401 e 403 não
 * passam por aqui: o {@code auth-security} os escreve antes.
 */
@RestControllerAdvice
class ErrosHandler extends ResponseEntityExceptionHandler {

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ProblemDetail> negocio(ApiException ex) {
        return ResponseEntity.status(ex.status())
                .headers(ex.headers())
                .body(ProblemDetails.of(ex.status(), ex.code(), ex.getMessage()));
    }

    /**
     * Os erros que o Spring MVC trata (validação, JSON inválido, 404 de rota) ganham o mesmo formato. Na validação, o
     * código é {@code VALIDATION_ERROR}, e {@code errors} lista cada campo recusado.
     */
    @Override
    protected @Nullable ResponseEntity<Object> handleExceptionInternal(Exception ex, @Nullable Object body,
                                                                       HttpHeaders headers, HttpStatusCode status,
                                                                       WebRequest request) {
        ResponseEntity<Object> response = super.handleExceptionInternal(ex, body, headers, status, request);
        if (response != null && response.getBody() instanceof ProblemDetail problem) {
            if (ex instanceof MethodArgumentNotValidException invalid) {
                problem.setDetail("Há campos inválidos.");
                problem.setProperty(ProblemDetails.ERRORS, invalid.getFieldErrors().stream()
                        .map(error -> Map.of("field", error.getField(), "message",
                                String.valueOf(error.getDefaultMessage())))
                        .toList());
                ProblemDetails.complete(problem, "VALIDATION_ERROR");
            } else {
                ProblemDetails.complete(problem, ProblemDetails.defaultCode(status));
            }
        }
        return response;
    }
}
