package com.ticket.queue.config;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Date;
import java.util.Objects;
import javax.crypto.SecretKey;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

public class QueueJwtTokenVerifier implements AccessTokenVerifier {

    private static final String BEARER_PREFIX = "Bearer ";
    private static final String ROLE_CLAIM = "role";

    private final QueueJwtProperties properties;
    private final Clock clock;
    private final SecretKey secretKey;

    public QueueJwtTokenVerifier(final QueueJwtProperties properties) {
        this(properties, Clock.systemUTC());
    }

    QueueJwtTokenVerifier(final QueueJwtProperties properties, final Clock clock) {
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.properties.validate();
        this.secretKey = Keys.hmacShaKeyFor(properties.getSecretKey().getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public AuthenticatedMember verify(final String authorizationHeader) {
        try {
            Claims claims = Jwts.parser()
                    .requireIssuer(properties.getIssuer())
                    .clock(() -> Date.from(clock.instant()))
                    .verifyWith(secretKey)
                    .build()
                    .parseSignedClaims(extractBearerToken(authorizationHeader))
                    .getPayload();
            return new AuthenticatedMember(Long.parseLong(claims.getSubject()), claims.get(ROLE_CLAIM, String.class));
        } catch (ExpiredJwtException exception) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "expired", exception);
        } catch (JwtException | IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid", exception);
        }
    }

    private String extractBearerToken(final String authorizationHeader) {
        if (authorizationHeader == null || !authorizationHeader.startsWith(BEARER_PREFIX)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid");
        }
        String token = authorizationHeader.substring(BEARER_PREFIX.length()).trim();
        if (token.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid");
        }
        return token;
    }
}
