package com.ticket.queue.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Objects;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.server.ResponseStatusException;

public class AccessTokenAuthenticationFilter extends OncePerRequestFilter {

    private final AccessTokenVerifier accessTokenVerifier;

    public AccessTokenAuthenticationFilter(final AccessTokenVerifier accessTokenVerifier) {
        this.accessTokenVerifier = Objects.requireNonNull(accessTokenVerifier, "accessTokenVerifier must not be null");
    }

    @Override
    protected void doFilterInternal(
            final HttpServletRequest request,
            final HttpServletResponse response,
            final FilterChain filterChain
    ) throws ServletException, IOException {
        String authorizationHeader = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (authorizationHeader == null || authorizationHeader.isBlank()) {
            filterChain.doFilter(request, response);
            return;
        }

        try {
            AuthenticatedMember member = accessTokenVerifier.verify(authorizationHeader);
            UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                    member,
                    null,
                    List.of(new SimpleGrantedAuthority("ROLE_" + member.role()))
            );
            SecurityContextHolder.getContext().setAuthentication(authentication);
            filterChain.doFilter(request, response);
        } catch (ResponseStatusException exception) {
            response.sendError(exception.getStatusCode().value(), exception.getReason());
        } finally {
            SecurityContextHolder.clearContext();
        }
    }
}
