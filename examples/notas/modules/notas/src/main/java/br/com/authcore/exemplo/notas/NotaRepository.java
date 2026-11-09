package br.com.authcore.exemplo.notas;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Toda consulta filtra pelo dono: a posse é regra de negócio, e não do {@code auth-security}. */
@Repository
class NotaRepository {

    private static final RowMapper<Nota> NOTA = (rs, row) -> new Nota(rs.getLong("id"), rs.getString("titulo"),
            rs.getString("texto"), rs.getTimestamp("criada_em").toInstant());

    private final JdbcClient jdbc;

    NotaRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    Nota criar(long contaId, String titulo, String texto, Instant agora) {
        long id = jdbc.sql("""
                        INSERT INTO notas (conta_id, titulo, texto, criada_em)
                        VALUES (:conta, :titulo, :texto, :agora) RETURNING id""")
                .param("conta", contaId)
                .param("titulo", titulo)
                .param("texto", texto)
                .param("agora", Timestamp.from(agora))
                .query(Long.class)
                .single();
        return new Nota(id, titulo, texto, agora);
    }

    List<Nota> listar(long contaId) {
        return jdbc.sql("SELECT id, titulo, texto, criada_em FROM notas WHERE conta_id = :conta ORDER BY id")
                .param("conta", contaId)
                .query(NOTA)
                .list();
    }

    Optional<Nota> buscar(long contaId, long id) {
        return jdbc.sql("SELECT id, titulo, texto, criada_em FROM notas WHERE conta_id = :conta AND id = :id")
                .param("conta", contaId)
                .param("id", id)
                .query(NOTA)
                .optional();
    }

    boolean apagar(long contaId, long id) {
        return jdbc.sql("DELETE FROM notas WHERE conta_id = :conta AND id = :id")
                .param("conta", contaId)
                .param("id", id)
                .update() == 1;
    }

    long contar() {
        return jdbc.sql("SELECT count(*) FROM notas").query(Long.class).single();
    }
}
