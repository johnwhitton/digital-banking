package io.github.johnwhitton.digitalbanking.controlplane.config;

import java.io.IOException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/** Installs only an exact local-demo bearer identity; invalid values remain anonymous. */
final class LocalDemoBearerTokenAuthenticationFilter extends OncePerRequestFilter {

    private final LocalDemoBearerAuthenticator authenticator;

    LocalDemoBearerTokenAuthenticationFilter(LocalDemoBearerAuthenticator authenticator) {
        this.authenticator = java.util.Objects.requireNonNull(authenticator, "authenticator");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        if (SecurityContextHolder.getContext().getAuthentication() == null) {
            authenticator.authenticate(request.getHeader("Authorization"))
                    .ifPresent(authentication -> {
                        var context = SecurityContextHolder.createEmptyContext();
                        context.setAuthentication(authentication);
                        SecurityContextHolder.setContext(context);
                    });
        }
        chain.doFilter(request, response);
    }
}
