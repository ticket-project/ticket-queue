package com.ticket.queue.domain.command;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ticket.queue.domain.model.QueueEntryStatus;
import com.ticket.queue.domain.model.QueueLevel;
import com.ticket.queue.domain.model.QueuePolicy;
import com.ticket.queue.domain.model.QueueTicket;
import com.ticket.queue.domain.port.QueueTicketStore;
import com.ticket.queue.domain.service.QueuePolicyResolver;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class QueueAdmissionAdvancerTest {

    @Test
    void active_capacity_is_filled_from_waiting_members() {
        QueuePolicyResolver policyResolver = org.mockito.Mockito.mock(QueuePolicyResolver.class);
        QueueTicketStore queueTicketStore = org.mockito.Mockito.mock(QueueTicketStore.class);
        Clock clock = Clock.fixed(Instant.parse("2026-05-24T00:00:00Z"), ZoneId.of("Asia/Seoul"));
        QueueAdmissionAdvancer advancer = new QueueAdmissionAdvancer(policyResolver, queueTicketStore, clock);
        QueuePolicy policy = new QueuePolicy(QueueLevel.LEVEL_1, 2, Duration.ofMinutes(5), Duration.ofHours(1));

        when(policyResolver.resolve(1L)).thenReturn(policy);
        when(queueTicketStore.countActive(1L)).thenReturn(0L, 1L, 2L);
        when(queueTicketStore.admitNextWaiting(eq(1L), eq(policy.entryTokenTtl()), eq(policy.entryRetention()), any(LocalDateTime.class)))
                .thenReturn(Optional.of(new QueueTicket(1L, 10L, QueueEntryStatus.ADMITTED, null)))
                .thenReturn(Optional.of(new QueueTicket(1L, 11L, QueueEntryStatus.ADMITTED, null)));

        advancer.advance(1L);

        verify(queueTicketStore, times(2))
                .admitNextWaiting(eq(1L), eq(policy.entryTokenTtl()), eq(policy.entryRetention()), any(LocalDateTime.class));
    }
}