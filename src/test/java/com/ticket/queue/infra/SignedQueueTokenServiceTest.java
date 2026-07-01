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

    private QueueProperties queueProperties() {
        QueueProperties properties = new QueueProperties();
        properties.setQueueTokenSecret(SECRET);
        return properties;
    }
}
