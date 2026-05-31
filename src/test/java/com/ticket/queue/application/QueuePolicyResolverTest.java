package com.ticket.queue.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.ticket.queue.config.QueueAdmissionProperties;
import com.ticket.queue.config.QueueProperties;
import com.ticket.queue.domain.QueuePolicy;
import com.ticket.queue.domain.QueuePolicyStore;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class QueuePolicyResolverTest {

    @Test
    void resolve는_Redis에_저장된_회차별_정책을_우선한다() {
        QueueProperties queueProperties = new QueueProperties();
        QueueAdmissionProperties admissionProperties = new QueueAdmissionProperties();
        QueuePolicyStore queuePolicyStore = org.mockito.Mockito.mock(QueuePolicyStore.class);
        QueuePolicyResolver resolver = new QueuePolicyResolver(queueProperties, admissionProperties, queuePolicyStore);
        QueuePolicy policy = new QueuePolicy(10, 200, Duration.ofMinutes(3), Duration.ofMinutes(30));
        when(queuePolicyStore.findByPerformanceId(1L)).thenReturn(Optional.of(policy));

        QueuePolicy actual = resolver.resolve(1L);

        assertThat(actual).isEqualTo(policy);
    }

    @Test
    void resolve는_회차별_정책이_없으면_전역_기본값을_fallback으로_사용한다() {
        QueueProperties queueProperties = new QueueProperties();
        queueProperties.setAdmitLimitPerTick(20);
        queueProperties.setMaxActiveUsers(500);
        queueProperties.setActiveTtl(Duration.ofMinutes(4));
        QueueAdmissionProperties admissionProperties = new QueueAdmissionProperties();
        admissionProperties.setSessionTtl(Duration.ofHours(3));
        QueuePolicyStore queuePolicyStore = org.mockito.Mockito.mock(QueuePolicyStore.class);
        QueuePolicyResolver resolver = new QueuePolicyResolver(queueProperties, admissionProperties, queuePolicyStore);
        when(queuePolicyStore.findByPerformanceId(1L)).thenReturn(Optional.empty());

        QueuePolicy actual = resolver.resolve(1L);

        assertThat(actual.admitLimitPerTick()).isEqualTo(20);
        assertThat(actual.maxActiveUsers()).isEqualTo(500);
        assertThat(actual.activeTtl()).isEqualTo(Duration.ofMinutes(4));
        assertThat(actual.sessionTtl()).isEqualTo(Duration.ofHours(3));
    }
}
