package com.ticket.queue.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

class PolicyTest {

    @Test
    void requiresQueueAt은_FORCE_ON이면_항상_true를_반환한다() {
        Policy policy = new Policy(
                10,
                200,
                Duration.ofMinutes(3),
                Duration.ofMinutes(30),
                PolicyMode.FORCE_ON,
                null,
                null
        );

        assertThat(policy.requiresQueueAt(LocalDateTime.of(2026, 5, 24, 19, 0))).isTrue();
    }

    @Test
    void requiresQueueAt은_FORCE_OFF이면_false를_반환한다() {
        Policy policy = new Policy(
                10,
                200,
                Duration.ofMinutes(3),
                Duration.ofMinutes(30),
                PolicyMode.FORCE_OFF,
                null,
                null
        );

        assertThat(policy.requiresQueueAt(LocalDateTime.of(2026, 5, 24, 19, 0))).isFalse();
    }

    @Test
    void requiresQueueAt은_AUTO이면_preopen부터_예매_마감까지_true를_반환한다() {
        Policy policy = new Policy(
                10,
                200,
                Duration.ofMinutes(3),
                Duration.ofMinutes(30),
                PolicyMode.AUTO,
                LocalDateTime.of(2026, 5, 24, 19, 50),
                LocalDateTime.of(2026, 5, 24, 21, 0)
        );

        assertThat(policy.requiresQueueAt(LocalDateTime.of(2026, 5, 24, 19, 49))).isFalse();
        assertThat(policy.requiresQueueAt(LocalDateTime.of(2026, 5, 24, 19, 50))).isTrue();
        assertThat(policy.requiresQueueAt(LocalDateTime.of(2026, 5, 24, 20, 30))).isTrue();
        assertThat(policy.requiresQueueAt(LocalDateTime.of(2026, 5, 24, 21, 1))).isFalse();
    }
}
