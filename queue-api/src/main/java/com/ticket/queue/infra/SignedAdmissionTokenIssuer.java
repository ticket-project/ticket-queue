package com.ticket.queue.infra;

import com.ticket.queue.application.AdmissionTokenIssuer;
import com.ticket.queue.config.AdmissionTokenProperties;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Objects;
import java.util.UUID;
import javax.crypto.SecretKey;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class SignedAdmissionTokenIssuer implements AdmissionTokenIssuer {

    private static final String PERFORMANCE_ID_CLAIM = "performanceId";
    private static final String QUEUE_ID_CLAIM = "queueId";
    private static final String SCOPE_CLAIM = "scope";
    private static final String SCOPE = "ticket-admission";

    private final AdmissionTokenProperties properties;
    private final Clock clock;
    private final SecretKey secretKey;

    @Autowired
    public SignedAdmissionTokenIssuer(final AdmissionTokenProperties properties) {
        this(properties, Clock.systemUTC());
    }

    SignedAdmissionTokenIssuer(final AdmissionTokenProperties properties, final Clock clock) {
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.properties.validate();
        this.secretKey = Keys.hmacShaKeyFor(properties.getSecretKey().getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public String issue(final Long memberId, final Long performanceId, final String queueId, final Duration ttl) {
        validatePositive(memberId, "memberId");
        validatePositive(performanceId, "performanceId");
        validateNotBlank(queueId, "queueId");
        validateTtl(ttl);

        Instant issuedAt = clock.instant();
        return buildToken(memberId, performanceId, queueId, issuedAt, issuedAt.plus(ttl));
    }

    private String buildToken(
            final Long memberId,
            final Long performanceId,
            final String queueId,
            final Instant issuedAt,
            final Instant expiresAt
    ) {
        return Jwts.builder()
                .issuer(properties.getIssuer())
                .audience()
                .add(properties.getAudience())
                .and()
                .subject(String.valueOf(memberId))
                .claim(PERFORMANCE_ID_CLAIM, performanceId)
                .claim(QUEUE_ID_CLAIM, queueId)
                .claim(SCOPE_CLAIM, SCOPE)
                .issuedAt(Date.from(issuedAt))
                .expiration(Date.from(expiresAt))
                .id(UUID.randomUUID().toString())
                .signWith(secretKey)
                .compact();
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

    private void validateTtl(final Duration ttl) {
        if (ttl == null || ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException("ttl must be positive");
        }
    }
}
