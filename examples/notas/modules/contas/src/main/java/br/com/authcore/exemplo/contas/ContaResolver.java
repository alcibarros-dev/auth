package br.com.authcore.exemplo.contas;

import br.com.authcore.security.UserIdResolver;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A ponte entre o servidor de autorização e a aplicação: o {@code auth-security} valida o token e pede a este bean o id
 * local do usuário dono do {@code sub}. O primeiro acesso de um usuário cria a conta dele aqui, com o e-mail do token.
 * <p>
 * Só este módulo conhece o formato do {@code sub}: trocar de provedor (Keycloak, por exemplo) muda só esta classe, e
 * nenhum controller.
 */
@Component
class ContaResolver implements UserIdResolver {

    private final JdbcClient jdbc;
    /** O {@code sub} de uma conta não muda: o cache evita uma ida ao banco por requisição. */
    private final Map<String, Long> ids = new ConcurrentHashMap<>();

    ContaResolver(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public long resolve(String subject, Map<String, Object> claims) {
        return ids.computeIfAbsent(subject, sub -> criarOuBuscar(sub, (String) claims.get("email")));
    }

    /** Acessos simultâneos do mesmo usuário novo criam uma conta só, pela unicidade do {@code subject}. */
    private long criarOuBuscar(String subject, String email) {
        return jdbc.sql("""
                        INSERT INTO contas (subject, email) VALUES (:subject, :email)
                        ON CONFLICT (subject) DO UPDATE SET email = EXCLUDED.email
                        RETURNING id""")
                .param("subject", subject)
                .param("email", email)
                .query(Long.class)
                .single();
    }
}
