package org.tanzu.goosechat;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * Provides a {@link RestClient.Builder} bean.
 * <p>
 * Spring Boot 4 modularized the HTTP-client auto-configuration; the
 * {@code RestClient.Builder} bean that Boot 3's web starter contributed now lives
 * in the separate {@code spring-boot-restclient} auto-configuration module, which
 * {@code spring-boot-starter-webmvc} does not pull in. {@code DiagnosticsController}
 * depends on it, so we contribute a plain builder here. {@link ConditionalOnMissingBean}
 * keeps this inert if that auto-configuration is ever added back to the classpath.
 */
@Configuration
class RestClientConfig {

    @Bean
    @ConditionalOnMissingBean
    RestClient.Builder restClientBuilder() {
        return RestClient.builder();
    }
}
