package com.ticket.queue.infra;

import com.ticket.queue.application.QueueTokenClaims;
import com.ticket.queue.application.QueueTokenException;
import com.ticket.queue.config.QueueProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

class SignedQueueTokenServiceTest {

    private static final String SECRET = "0123456789abcdef0123456789abcdef";

    @Test
    void issue_and_verify_preserves_member_binding() {
        SignedQueueTokenService service = new SignedQueueTokenService(queueProperties());

        String token = service.issue(
                new QueueTokenClaims(1L, "queue-1", 42L, 10L),
                Duration.ofHours(1)
        );

        QueueTokenClaims claims = service.verify(token);
        assertThat(claims.performanceId()).isEqualTo(1L);
        assertThat(claims.queueId()).isEqualTo("queue-1");
        assertThat(claims.seq()).isEqualTo(42L);
        assertThat(claims.memberId()).isEqualTo(10L);
    }

    @Test
    void issue_uses_compact_hmac_token_instead_of_jwt() {
        SignedQueueTokenService service = new SignedQueueTokenService(queueProperties());

        String token = service.issue(
                new QueueTokenClaims(1L, "queue-1", 42L, 10L),
                Duration.ofHours(1)
        );

        assertThat(token).startsWith("q1.");
        assertThat(token.split("\\.", -1)).hasSize(7);
    }

    @Test
    void verify_rejects_tampered_token() {
        SignedQueueTokenService service = new SignedQueueTokenService(queueProperties());
        String token = service.issue(
                new QueueTokenClaims(1L, "queue-1", 42L, 10L),
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
                new QueueTokenClaims(1L, "queue-1", 42L, 10L),
                Duration.ofSeconds(1)
        );

        SignedQueueTokenService service = new SignedQueueTokenService(queueProperties(), expiredClock);

        assertThatExceptionOfType(QueueTokenException.class)
                .isThrownBy(() -> service.verify(token))
                .withMessageContaining("expired");
    }

    private QueueProperties queueProperties() {
        QueueProperties properties = new QueueProperties();
        properties.setQueueTokenSecret(SECRET);
        return properties;
    }
}
