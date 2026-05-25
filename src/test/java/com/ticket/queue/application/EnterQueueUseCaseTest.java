package com.ticket.queue.application;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.ticket.queue.domain.QueueTicketStore;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class EnterQueueUseCaseTest {

    @Mock
    private QueueTicketStore queueTicketStore;

    private final Duration sessionTtl = Duration.ofHours(1);

    @Test
    void registers_member_id_to_waiting_queue_without_reading_position() {
        EnterQueueUseCase useCase = new EnterQueueUseCase(queueTicketStore);

        useCase.execute(new EnterQueueUseCase.Input(1L, "session-1", sessionTtl));

        verify(queueTicketStore).registerWaiting(1L, "session-1", sessionTtl);
        verify(queueTicketStore, never()).findWaitingPosition(1L, "session-1");
    }

    @Test
    void enter_does_not_read_current_ticket_status() {
        EnterQueueUseCase useCase = new EnterQueueUseCase(queueTicketStore);

        useCase.execute(new EnterQueueUseCase.Input(1L, "session-1", sessionTtl));

        verify(queueTicketStore, never()).findTicket(1L, "session-1");
    }

    @Test
    void queue_server_always_registers_waiting_and_does_not_decide_queue_enabled() {
        EnterQueueUseCase useCase = new EnterQueueUseCase(queueTicketStore);

        useCase.execute(new EnterQueueUseCase.Input(1L, "session-1", sessionTtl));

        verify(queueTicketStore, never()).findWaitingPosition(1L, "session-1");
    }

}
