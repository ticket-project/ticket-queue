package com.ticket.queue.domain.query.status;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.ticket.queue.domain.model.QueueEntryStatus;
import com.ticket.queue.domain.model.QueueTicket;
import com.ticket.queue.domain.port.QueueTicketStore;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class QueueStatusReaderTest {

    @Mock
    private QueueTicketStore queueTicketStore;

    @InjectMocks
    private QueueStatusReader queueStatusReader;

    @Test
    void waiting_member는_zrank_기준_순번을_반환한다() {
        when(queueTicketStore.findTicket(1L, 10L))
                .thenReturn(Optional.of(new QueueTicket(1L, 10L, QueueEntryStatus.WAITING, 1L)));
        when(queueTicketStore.findWaitingPosition(1L, 10L)).thenReturn(Optional.of(3L));

        GetQueueStatusUseCase.Output output = queueStatusReader.read(1L, 10L);

        assertThat(output.status()).isEqualTo(QueueEntryStatus.WAITING);
        assertThat(output.position()).isEqualTo(3L);
    }

    @Test
    void ticket이_없으면_expired를_반환한다() {
        when(queueTicketStore.findTicket(1L, 10L)).thenReturn(Optional.empty());

        GetQueueStatusUseCase.Output output = queueStatusReader.read(1L, 10L);

        assertThat(output.status()).isEqualTo(QueueEntryStatus.EXPIRED);
        assertThat(output.position()).isNull();
    }
}