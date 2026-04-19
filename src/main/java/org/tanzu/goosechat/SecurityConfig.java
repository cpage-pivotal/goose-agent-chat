package org.tanzu.goosechat;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.http.HttpStatus;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .cors(Customizer.withDefaults())
            .csrf(csrf -> csrf.disable())
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(
                    "/actuator/health/**",
                    "/actuator/info",
                    "/favicon.ico"
                ).permitAll()
                .anyRequest().authenticated()
            )
            .exceptionHandling(e -> e
                .defaultAuthenticationEntryPointFor(
                    new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED),
                    new OrRequestMatcher(
                        new AntPathRequestMatcher("/api/**"),
                        new AntPathRequestMatcher("/auth/**")
                    )
                )
            )
            .oauth2Login(oauth -> oauth
                .defaultSuccessUrl("/", true)
            )
            .logout(l -> l
                .logoutUrl("/logout")
                .logoutSuccessUrl("/")
            );

        return http.build();
    }
}
