package br.com.authcore.server.token;

import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationConsent;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationConsentService;

/**
 * Consentimento que nunca é lembrado: só o cliente do fluxo de dispositivo pede consentimento, e cada {@code user_code}
 * precisa ser confirmado. Lembrado, um segundo link com o código de outra pessoa entregaria a ela um token de quem já
 * confirmou uma vez (ADR-0003, D9).
 */
final class UnrememberedConsentService implements OAuth2AuthorizationConsentService {

    @Override
    public void save(OAuth2AuthorizationConsent authorizationConsent) {
    }

    @Override
    public void remove(OAuth2AuthorizationConsent authorizationConsent) {
    }

    @Override
    public OAuth2AuthorizationConsent findById(String registeredClientId, String principalName) {
        return null;
    }
}
