package br.com.authcore.exemplo.notas;

import java.time.Instant;

/** Uma nota de um usuário. O dono não aparece: o usuário só vê as próprias notas. */
public record Nota(long id, String titulo, String texto, Instant criadaEm) {
}
