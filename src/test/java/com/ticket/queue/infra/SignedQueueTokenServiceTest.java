package com.ticket.queue.infra;

import com.ticket.queue.application.QueueTokenClaims;
import com.ticket.queue.config.QueueProperties;
import java.time.Duration;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

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

    private QueueProperties queueProperties() {
        QueueProperties properties = new QueueProperties();
        properties.setQueueTokenSecret(SECRET);
        return properties;
    }
}
