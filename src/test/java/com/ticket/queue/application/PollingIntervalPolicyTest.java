package com.ticket.queue.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PollingIntervalPolicyTest {

    private final PollingIntervalPolicy policy = new PollingIntervalPolicy();

    @Test
    void position이_멀수록_polling_간격을_길게_반환한다() {
        assertThat(policy.pollAfterSeconds(10_001)).isEqualTo(30L);
        assertThat(policy.pollAfterSeconds(1_001)).isEqualTo(10L);
        assertThat(policy.pollAfterSeconds(101)).isEqualTo(5L);
        assertThat(policy.pollAfterSeconds(100)).isEqualTo(2L);
    }
}
