package com.ticket.queue.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Objects;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.server.ResponseStatusException;

@Component
public class AccessTokenAuthenticationFilter extends OncePerRequestFilter {

    static final String MEMBER_ATTRIBUTE = AuthenticatedMember.class.getName();

    private final QueueJwtTokenVerifier accessTokenVerifier;

    public AccessTokenAuthenticationFilter(final QueueJwtTokenVerifier accessTokenVerifier) {
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
            request.setAttribute(MEMBER_ATTRIBUTE, accessTokenVerifier.verify(authorizationHeader));
        } catch (ResponseStatusException exception) {
            response.sendError(exception.getStatusCode().value(), exception.getReason());
            return;
        }
        filterChain.doFilter(request, response);
    }
}
