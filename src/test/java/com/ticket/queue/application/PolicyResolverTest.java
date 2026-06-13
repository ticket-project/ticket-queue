package com.ticket.queue.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.ticket.queue.config.QueueProperties;
import com.ticket.queue.domain.Policy;
import com.ticket.queue.domain.PolicyStore;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class PolicyResolverTest {

    @Test
    void resolve는_Redis에_저장된_회차별_정책을_우선한다() {
        QueueProperties queueProperties = new QueueProperties();
        PolicyStore policyStore = org.mockito.Mockito.mock(PolicyStore.class);
        PolicyResolver resolver = new PolicyResolver(queueProperties, policyStore);
        Policy policy = new Policy(10, 200, Duration.ofMinutes(3), Duration.ofMinutes(30));
        when(policyStore.findByPerformanceId(1L)).thenReturn(Optional.of(policy));

        Policy actual = resolver.resolve(1L);

        assertThat(actual).isEqualTo(policy);
    }

    @Test
    void resolve는_회차별_정책이_없으면_전역_기본값을_fallback으로_사용한다() {
        QueueProperties queueProperties = new QueueProperties();
        queueProperties.setDefaultMaxAdmitPerSecond(20);
        queueProperties.setDefaultMaxActiveSessions(500);
        queueProperties.setShoppingSessionTtl(Duration.ofMinutes(4));
        queueProperties.setDefaultQueueTtl(Duration.ofHours(3));
        PolicyStore policyStore = org.mockito.Mockito.mock(PolicyStore.class);
        PolicyResolver resolver = new PolicyResolver(queueProperties, policyStore);
        when(policyStore.findByPerformanceId(1L)).thenReturn(Optional.empty());

        Policy actual = resolver.resolve(1L);

        assertThat(actual.admitLimitPerTick()).isEqualTo(20);
        assertThat(actual.maxActiveUsers()).isEqualTo(500);
        assertThat(actual.activeTtl()).isEqualTo(Duration.ofMinutes(4));
        assertThat(actual.sessionTtl()).isEqualTo(Duration.ofHours(3));
    }
}
