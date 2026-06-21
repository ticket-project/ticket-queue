package com.ticket.queue.infra;

import com.ticket.queue.application.QueueTokenClaims;
import com.ticket.queue.application.QueueTokenException;
import com.ticket.queue.application.QueueTokenService;
import com.ticket.queue.config.QueueProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Objects;
import javax.crypto.SecretKey;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class SignedQueueTokenService implements QueueTokenService {

    private static final String PERFORMANCE_ID_CLAIM = "performanceId";
    private static final String SEQ_CLAIM = "seq";
    private static final String MEMBER_ID_CLAIM = "memberId";
    private static final String SCOPE_CLAIM = "scope";
    private static final String SCOPE = "queue-entry";
    private static final String ISSUER = "ticket-queue";

    private final Clock clock;
    private final SecretKey secretKey;

    @Autowired
    public SignedQueueTokenService(final QueueProperties properties) {
        this(properties, Clock.systemUTC());
    }

    SignedQueueTokenService(final QueueProperties properties, final Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        String secret = validateSecret(properties);
        this.secretKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public String issue(final QueueTokenClaims claims, final Duration ttl) {
        validateClaims(claims);
        validateTtl(ttl);

        Instant issuedAt = clock.instant();
        return buildToken(claims, issuedAt, issuedAt.plus(ttl));
    }

    @Override
    public QueueTokenClaims verify(final String token) {
        Claims claims = parse(token);
        validateScope(claims);
        return toQueueTokenClaims(claims);
    }

    private String buildToken(
            final QueueTokenClaims claims,
            final Instant issuedAt,
            final Instant expiresAt
    ) {
        return Jwts.builder()
                .issuer(ISSUER)
                .subject(claims.queueId())
                .claim(PERFORMANCE_ID_CLAIM, claims.performanceId())
                .claim(SEQ_CLAIM, claims.seq())
                .claim(MEMBER_ID_CLAIM, claims.memberId())
                .claim(SCOPE_CLAIM, SCOPE)
                .issuedAt(Date.from(issuedAt))
                .expiration(Date.from(expiresAt))
                .signWith(secretKey)
                .compact();
    }

    private QueueTokenClaims toQueueTokenClaims(final Claims claims) {
        return new QueueTokenClaims(
                readLongClaim(claims, PERFORMANCE_ID_CLAIM),
                claims.getSubject(),
                readLongClaim(claims, SEQ_CLAIM),
                readLongClaim(claims, MEMBER_ID_CLAIM)
        );
    }

    private Claims parse(final String token) {
        if (token == null || token.isBlank()) {
            throw new QueueTokenException("queue token invalid");
        }

        try {
            return Jwts.parser()
                    .requireIssuer(ISSUER)
                    .clock(() -> Date.from(clock.instant()))
                    .verifyWith(secretKey)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
        } catch (ExpiredJwtException exception) {
            throw new QueueTokenException("queue token expired", exception);
        } catch (JwtException | IllegalArgumentException exception) {
            throw new QueueTokenException("queue token invalid", exception);
        }
    }

    private void validateScope(final Claims claims) {
        if (!SCOPE.equals(claims.get(SCOPE_CLAIM, String.class))) {
            throw new QueueTokenException("queue token invalid scope");
        }
    }

    private Long readLongClaim(final Claims claims, final String claimName) {
        Object value = claims.get(claimName);
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String stringValue) {
            try {
                return Long.parseLong(stringValue);
            } catch (NumberFormatException exception) {
                throw new QueueTokenException("queue token invalid " + claimName, exception);
            }
        }
        throw new QueueTokenException("queue token invalid " + claimName);
    }

    private String validateSecret(final QueueProperties properties) {
        Objects.requireNonNull(properties, "properties must not be null");
        String secret = properties.getQueueTokenSecret();
        if (secret == null || secret.isBlank()) {
            throw new IllegalArgumentException("app.queue.queue-token-secret must not be blank");
        }
        if (secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalArgumentException("app.queue.queue-token-secret must be at least 32 bytes for HS256");
        }
        return secret;
    }

    private void validateClaims(final QueueTokenClaims claims) {
        if (claims == null) {
            throw new IllegalArgumentException("claims must not be null");
        }
        validatePositive(claims.performanceId(), "performanceId");
        validateNotBlank(claims.queueId(), "queueId");
        validatePositive(claims.seq(), "seq");
        validatePositive(claims.memberId(), "memberId");
    }

    private void validateTtl(final Duration ttl) {
        if (ttl == null || ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException("ttl must be positive");
        }
    }

    private void validatePositive(final Long value, final String name) {
        if (value == null || value <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }

    private void validateNotBlank(final String value, final String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
