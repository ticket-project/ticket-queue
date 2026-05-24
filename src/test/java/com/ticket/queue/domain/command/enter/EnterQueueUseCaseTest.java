package com.ticket.queue.domain.command.enter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.ticket.queue.domain.model.QueueEntryStatus;
import com.ticket.queue.domain.model.QueueLevel;
import com.ticket.queue.domain.model.QueuePolicy;
import com.ticket.queue.domain.model.QueueTicket;
import com.ticket.queue.domain.port.QueueTicketStore;
import com.ticket.queue.domain.service.QueuePolicyResolver;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class EnterQueueUseCaseTest {

    @Mock
    private QueuePolicyResolver queuePolicyResolver;

    @Mock
    private QueueTicketStore queueTicketStore;

    @InjectMocks
    private EnterQueueUseCase useCase;

    @Test
    void registers_member_id_to_waiting_queue_and_returns_position() {
        QueuePolicy policy = policy();
        when(queuePolicyResolver.resolve(1L)).thenReturn(policy);
        when(queueTicketStore.registerWaiting(1L, 10L, policy.entryRetention()))
                .thenReturn(new QueueTicket(1L, 10L, QueueEntryStatus.WAITING, 1L));
        when(queueTicketStore.findWaitingPosition(1L, 10L)).thenReturn(Optional.of(7L));

        EnterQueueUseCase.Output output = useCase.execute(new EnterQueueUseCase.Input(1L, 10L));

        assertThat(output.status()).isEqualTo(QueueEntryStatus.WAITING);
        assertThat(output.position()).isEqualTo(7L);
    }

    @Test
    void returns_admitted_when_member_is_already_active() {
        QueuePolicy policy = policy();
        when(queuePolicyResolver.resolve(1L)).thenReturn(policy);
        when(queueTicketStore.registerWaiting(1L, 10L, policy.entryRetention()))
                .thenReturn(new QueueTicket(1L, 10L, QueueEntryStatus.ADMITTED, null));

        EnterQueueUseCase.Output output = useCase.execute(new EnterQueueUseCase.Input(1L, 10L));

        assertThat(output.status()).isEqualTo(QueueEntryStatus.ADMITTED);
        assertThat(output.position()).isNull();
    }

    @Test
    void queue_server_always_registers_waiting_and_does_not_decide_queue_enabled() {
        QueuePolicy policy = policy();
        when(queuePolicyResolver.resolve(1L)).thenReturn(policy);
        when(queueTicketStore.registerWaiting(1L, 10L, policy.entryRetention()))
                .thenReturn(new QueueTicket(1L, 10L, QueueEntryStatus.WAITING, 1L));
        when(queueTicketStore.findWaitingPosition(1L, 10L)).thenReturn(Optional.of(1L));

        EnterQueueUseCase.Output output = useCase.execute(new EnterQueueUseCase.Input(1L, 10L));

        assertThat(output.status()).isEqualTo(QueueEntryStatus.WAITING);
        assertThat(output.position()).isEqualTo(1L);
    }

    private QueuePolicy policy() {
        return new QueuePolicy(
                QueueLevel.LEVEL_1,
                300,
                Duration.ofMinutes(5),
                Duration.ofHours(1)
        );
    }
}