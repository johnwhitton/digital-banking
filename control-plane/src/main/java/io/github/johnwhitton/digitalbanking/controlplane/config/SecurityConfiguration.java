package io.github.johnwhitton.digitalbanking.controlplane.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.web.SecurityFilterChain;

@Configuration(proxyBeanMethods = false)
public class SecurityConfiguration {

    private static final String UNAUTHENTICATED = """
            {"type":"urn:digital-banking:problem:unauthenticated","title":"Authentication required","status":401,"detail":"An authenticated participant identity is required."}
            """;
    private static final String UNAUTHORIZED = """
            {"type":"urn:digital-banking:problem:unauthorized","title":"Access denied","status":403,"detail":"The authenticated participant is not authorized for this operation."}
            """;

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(
                                "/actuator/health", "/actuator/health/**",
                                "/openapi/token-operations-v1.yaml")
                        .permitAll()
                        .requestMatchers(HttpMethod.POST, "/v1/token-operations/mints")
                        .hasAuthority("token:mint")
                        .requestMatchers(HttpMethod.POST, "/v1/token-operations/burns")
                        .hasAuthority("token:burn")
                        .requestMatchers(HttpMethod.GET, "/v1/token-operations/*")
                        .hasAuthority("token:read")
                        .requestMatchers(HttpMethod.POST, "/v1/transfers")
                        .hasAuthority("transfer:create")
                        .requestMatchers(HttpMethod.GET, "/v1/transfers/*")
                        .hasAuthority("transfer:read")
                        .requestMatchers(HttpMethod.POST,
                                "/local/v1/mock-banks/*/accounts/*/withdrawals")
                        .hasAuthority("local-bank:debit")
                        .requestMatchers(HttpMethod.POST,
                                "/local/v1/mock-banks/*/accounts/*/deposits")
                        .hasAuthority("local-bank:credit")
                        .requestMatchers(HttpMethod.GET,
                                "/local/v1/mock-banks/*/accounts/*",
                                "/local/v1/mock-banks/operations/*",
                                "/local/v1/mock-banks/openapi.yaml")
                        .hasAuthority("local-bank:read")
                        .anyRequest().denyAll())
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint((request, response, failure) ->
                                writeProblem(response, 401, UNAUTHENTICATED))
                        .accessDeniedHandler((request, response, failure) ->
                                writeProblem(response, 403, UNAUTHORIZED)))
                .anonymous(Customizer.withDefaults());
        return http.build();
    }

    /** Prevents development credentials while leaving identity integration deliberately absent. */
    @Bean
    UserDetailsService noLocalUsers() {
        return username -> {
            throw new UsernameNotFoundException("identity adapter is not configured");
        };
    }

    private static void writeProblem(
            HttpServletResponse response, int status, String body) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.getOutputStream().write(body.strip().getBytes(StandardCharsets.UTF_8));
    }
}
