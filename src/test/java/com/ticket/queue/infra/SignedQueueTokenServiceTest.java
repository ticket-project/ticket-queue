package com.ticket.queue.infra;

import com.ticket.queue.application.QueueTokenClaims;
import com.ticket.queue.application.QueueTokenException;
import com.ticket.queue.config.QueueProperties;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

class SignedQueueTokenServiceTest {

    private static final String SECRET = "0123456789abcdef0123456789abcdef";

    @Test
    void issue_and_verify_preserves_member_binding() {
        SignedQueueTokenService service = new SignedQueueTokenService(queueProperties());

        String token = service.issue(
                new QueueTokenClaims(1L, "queue-1", 17, 42L, 24_691L, 10L),
                Duration.ofHours(1)
        );

        assertThat(token).startsWith("q2.");
        assertThat(token).doesNotStartWith("eyJ");
        assertThat(token.split("\\.", -1)).hasSize(9);

        QueueTokenClaims claims = service.verify(token);
        assertThat(claims.performanceId()).isEqualTo(1L);
        assertThat(claims.queueId()).isEqualTo("queue-1");
        assertThat(claims.shardId()).isEqualTo(17);
        assertThat(claims.localSeq()).isEqualTo(42L);
        assertThat(claims.slotId()).isEqualTo(24_691L);
        assertThat(claims.memberId()).isEqualTo(10L);
    }

    @Test
    void verify_rejects_tampered_token() {
        SignedQueueTokenService service = new SignedQueueTokenService(queueProperties());
        String token = service.issue(
                new QueueTokenClaims(1L, "queue-1", 17, 42L, 24_691L, 10L),
                Duration.ofHours(1)
        );

        String tampered = token.replace(".42.", ".43.");

        assertThatExceptionOfType(QueueTokenException.class)
                .isThrownBy(() -> service.verify(tampered))
                .withMessageContaining("invalid");
    }

    @Test
    void verify_rejects_expired_token() {
        Clock issuedClock = Clock.fixed(Instant.parse("2026-06-30T00:00:00Z"), ZoneOffset.UTC);
        Clock expiredClock = Clock.fixed(Instant.parse("2026-06-30T00:00:02Z"), ZoneOffset.UTC);
        String token = new SignedQueueTokenService(queueProperties(), issuedClock).issue(
                new QueueTokenClaims(1L, "queue-1", 17, 42L, 24_691L, 10L),
                Duration.ofSeconds(1)
        );

        SignedQueueTokenService service = new SignedQueueTokenService(queueProperties(), expiredClock);

        assertThatExceptionOfType(QueueTokenException.class)
                .isThrownBy(() -> service.verify(token))
                .withMessageContaining("expired");
    }

    @Test
    void verify_accepts_legacy_jwt_queue_token_for_ttl_window() {
        Clock clock = Clock.fixed(Instant.parse("2026-06-30T00:00:00Z"), ZoneOffset.UTC);
        String token = legacyQueueToken(clock, Duration.ofHours(1));
        SignedQueueTokenService service = new SignedQueueTokenService(queueProperties(), clock);

        QueueTokenClaims claims = service.verify(token);

        assertThat(claims.legacy()).isTrue();
        assertThat(claims.performanceId()).isEqualTo(1L);
        assertThat(claims.queueId()).isEqualTo("queue-1");
        assertThat(claims.localSeq()).isEqualTo(42L);
        assertThat(claims.memberId()).isEqualTo(10L);
    }

    private QueueProperties queueProperties() {
        QueueProperties properties = new QueueProperties();
        properties.setQueueTokenSecret(SECRET);
        return properties;
    }

    private String legacyQueueToken(final Clock clock, final Duration ttl) {
        Instant issuedAt = clock.instant();
        return Jwts.builder()
                .issuer("ticket-queue")
                .subject("queue-1")
                .claim("performanceId", 1L)
                .claim("seq", 42L)
                .claim("memberId", 10L)
                .claim("scope", "queue-entry")
                .issuedAt(Date.from(issuedAt))
                .expiration(Date.from(issuedAt.plus(ttl)))
                .signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8)))
                .compact();
    }
}
