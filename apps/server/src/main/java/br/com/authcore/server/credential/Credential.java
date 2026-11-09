package br.com.authcore.server.credential;

/**
 * Credencial de um usuário (ADR-0001, D6).
 *
 * @param subject {@code sub} dos tokens: o id do sistema de origem em texto, para os usuários
 *                importados, ou um UUID
 */
public record Credential(long id, String subject, String email, String passwordHash) {
}
