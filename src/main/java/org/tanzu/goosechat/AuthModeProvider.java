package org.tanzu.goosechat;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Determines which authentication mode the app boots in.
 *
 * <p>SSO presence is detected via {@code Optional<ClientRegistrationRepository>} —
 * {@code java-cfenv-boot-pivotal-sso} only registers this bean when the
 * {@code agent-sso} service binding is present.
 *
 * <p>Mode matrix:
 * <pre>
 * | agent-sso bound? | BROKER_BASE_URL set? | Mode             |
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

        boolean ssoBound = clientRegistrationRepository.isPresent();
        boolean brokerSet = brokerBaseUrl != null && !brokerBaseUrl.isBlank();

        if (!ssoBound && brokerSet) {
            throw new IllegalStateException(
                    "Agent Credential Broker is configured (BROKER_BASE_URL is set) but the " +
                    "agent-sso service binding is missing. Bind agent-sso or unset BROKER_BASE_URL.");
        }

        this.mode = ssoBound ? AuthMode.OAUTH2 : AuthMode.PASSWORD;
    }

    public AuthMode getMode() {
        return mode;
    }

    public String getLoginUrl() {
        return mode == AuthMode.OAUTH2 ? "/oauth2/authorization/sso" : "/login";
    }
}
