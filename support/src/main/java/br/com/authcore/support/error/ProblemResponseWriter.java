package br.com.authcore.support.error;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.json.ProblemDetailJacksonMixin;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.URI;

/**
 * Escreve um {@link ProblemDetail} direto na resposta, para erros gerados em filtros, onde o
 * {@code @RestControllerAdvice} não atua (ADR-0001, D10; ADR-0002, D5).
 * <p>
 * O formato é o mesmo do MVC: o mixin do Spring achata as propriedades extras ({@code code}, {@code errors}) no
 * nível raiz do JSON.
 */
public class ProblemResponseWriter {

    private final JsonMapper mapper;

    public ProblemResponseWriter(JsonMapper jsonMapper) {
        this.mapper = jsonMapper.rebuild().addMixIn(ProblemDetail.class, ProblemDetailJacksonMixin.class).build();
    }

    public void write(HttpServletRequest request, HttpServletResponse response, ProblemDetail problem)
            throws IOException {
        if (problem.getInstance() == null) {
            problem.setInstance(URI.create(request.getRequestURI()));
        }
        response.setStatus(problem.getStatus());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        mapper.writeValue(response.getOutputStream(), problem);
    }
}
