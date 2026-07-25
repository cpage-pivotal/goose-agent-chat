package org.tanzu.goosechat;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Determines which authentication mode the app boots in.
 *
 * <p>SSO presence is detected via {@code Optional<ClientRegistrationRepository>}. That bean
 * only exists when an OAuth2 client registration is configured, which happens when the
 * {@code oauth} profile loads {@code application-oauth.properties}.
 *
 * <p>Mode matrix:
 * <pre>
 * | OIDC registered? | BROKER_BASE_URL set? | Mode             |
 * |------------------|----------------------|------------------|
 * | yes              | (any)                | OAUTH2           |
 * | no               | no                   | PASSWORD         |
 * | no               | yes                  | fail-fast        |
 * </pre>
 */
@Component
public class AuthModeProvider {

    public enum AuthMode { OAUTH2, PASSWORD }

    private final AuthMode mode;

    public AuthModeProvider(
            Optional<ClientRegistrationRepository> clientRegistrationRepository,
            @Value("${broker.base-url:}") String brokerBaseUrl) {

        boolean oidcConfigured = clientRegistrationRepository.isPresent();
        boolean brokerSet = brokerBaseUrl != null && !brokerBaseUrl.isBlank();

        if (!oidcConfigured && brokerSet) {
            throw new IllegalStateException(
                    "Agent Credential Broker is configured (BROKER_BASE_URL is set) but no OAuth2 " +
                    "client registration was found. Activate the \"oauth\" profile with " +
                    "OIDC_ISSUER_URI/OIDC_CLIENT_ID/OIDC_CLIENT_SECRET set, or unset BROKER_BASE_URL.");
        }

        this.mode = oidcConfigured ? AuthMode.OAUTH2 : AuthMode.PASSWORD;
    }

    public AuthMode getMode() {
        return mode;
    }

    public String getLoginUrl() {
        return mode == AuthMode.OAUTH2 ? "/oauth2/authorization/sso" : "/login";
    }
}
