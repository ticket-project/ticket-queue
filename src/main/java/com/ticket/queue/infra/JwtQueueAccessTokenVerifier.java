package com.ticket.queue.infra;

import com.ticket.queue.application.AuthenticatedMember;
import com.ticket.queue.application.QueueAccessTokenVerifier;
import com.ticket.queue.config.AccessTokenProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Date;
import java.util.Objects;
import javax.crypto.SecretKey;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
public class JwtQueueAccessTokenVerifier implements QueueAccessTokenVerifier {

    private static final String BEARER_PREFIX = "Bearer ";
    private static final String ROLE_CLAIM = "role";

    private final AccessTokenProperties properties;
    private final Clock clock;
    private final SecretKey secretKey;

    public JwtQueueAccessTokenVerifier(final AccessTokenProperties properties) {
        this(properties, Clock.systemUTC());
    }

    JwtQueueAccessTokenVerifier(final AccessTokenProperties properties, final Clock clock) {
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.properties.validate();
        this.secretKey = Keys.hmacShaKeyFor(properties.getSecretKey().getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public AuthenticatedMember verify(final String authorizationHeader) {
        String token = extractBearerToken(authorizationHeader);
        try {
            Claims claims = Jwts.parser()
                    .requireIssuer(properties.getIssuer())
                    .clock(() -> Date.from(clock.instant()))
                    .verifyWith(secretKey)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();

            return new AuthenticatedMember(parseMemberId(claims.getSubject()), claims.get(ROLE_CLAIM, String.class));
        } catch (JwtException | IllegalArgumentException exception) {
            throw unauthorized();
        }
    }

    private String extractBearerToken(final String authorizationHeader) {
        if (authorizationHeader == null || !authorizationHeader.startsWith(BEARER_PREFIX)) {
            throw unauthorized();
        }
        String token = authorizationHeader.substring(BEARER_PREFIX.length()).trim();
        if (token.isBlank()) {
            throw unauthorized();
        }
        return token;
    }

    private Long parseMemberId(final String subject) {
        if (subject == null || subject.isBlank()) {
            throw new IllegalArgumentException("subject must not be blank");
        }
        Long memberId = Long.parseLong(subject);
        if (memberId <= 0) {
            throw new IllegalArgumentException("memberId must be positive");
        }
        return memberId;
    }

    private ResponseStatusException unauthorized() {
        return new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid access token");
    }
}
