package org.tanzu.goosechat;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    /**
     * Static asset URL patterns that must be reachable without authentication so the
     * Angular login page can render. Angular CLI emits all bundles, fonts, and images
     * at the static root (no nested chunk directories), so root-level globs are
     * sufficient — and avoid accidentally permitting nested API paths.
     */
    private static final String[] PUBLIC_STATIC_ASSETS = {
            "/index.html",
            "/*.js", "/*.css", "/*.map",
            "/*.ico", "/*.png", "/*.svg", "/*.jpg", "/*.jpeg", "/*.gif", "/*.webp",
            "/*.woff", "/*.woff2", "/*.ttf", "/*.eot", "/*.otf",
            "/assets/**", "/media/**"
    };

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, AuthModeProvider authMode) throws Exception {
        http
                .cors(Customizer.withDefaults())
                .csrf(csrf -> csrf.disable())
                .exceptionHandling(e -> e
                        .defaultAuthenticationEntryPointFor(
                                new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED),
                                new OrRequestMatcher(
                                        PathPatternRequestMatcher.pathPattern("/api/**"),
                                        PathPatternRequestMatcher.pathPattern("/auth/**")
                                )
                        )
                );

        if (authMode.getMode() == AuthModeProvider.AuthMode.OAUTH2) {
            http
                    .authorizeHttpRequests(auth -> auth
                            .requestMatchers(
                                    "/actuator/health/**",
                                    "/actuator/info",
                                    "/favicon.ico",
                                    "/auth/status"
                            ).permitAll()
                            .anyRequest().authenticated()
                    )
                    .oauth2Login(oauth -> oauth
                            .defaultSuccessUrl("/", true)
                    )
                    .logout(l -> l
                            .logoutUrl("/logout")
                            .logoutSuccessUrl("/")
                    );
        } else {
            http
                    .authorizeHttpRequests(auth -> auth
                            .requestMatchers(
                                    "/actuator/health/**",
                                    "/actuator/info",
                                    "/favicon.ico",
                                    "/auth/status",
                                    "/",
                                    "/login"
                            ).permitAll()
                            .requestMatchers(PUBLIC_STATIC_ASSETS).permitAll()
                            .anyRequest().authenticated()
                    )
                    .formLogin(form -> form
                            .loginPage("/login")
                            .loginProcessingUrl("/login")
                            .defaultSuccessUrl("/", true)
                            .permitAll()
                    )
                    .logout(l -> l
                            .logoutUrl("/logout")
                            .logoutSuccessUrl("/login")
                            .permitAll()
                    );
        }

        return http.build();
    }

    /**
     * Only registered when no {@link ClientRegistrationRepository} is present
     * (i.e. {@code agent-sso} is not bound), which is the same signal
     * {@link AuthModeProvider} uses to select PASSWORD mode.
     */
    @Bean
    @ConditionalOnMissingBean(ClientRegistrationRepository.class)
    PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    @Bean
    @ConditionalOnMissingBean(ClientRegistrationRepository.class)
    UserDetailsService userDetailsService(
            PasswordEncoder passwordEncoder,
            @Value("${app.auth.secret:changeme}") String appAuthSecret) {
        UserDetails user = User.withUsername("user")
                .password(passwordEncoder.encode(appAuthSecret))
                .roles("USER")
                .build();
        return new InMemoryUserDetailsManager(user);
    }
}
