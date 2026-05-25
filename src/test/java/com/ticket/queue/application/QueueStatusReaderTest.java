package com.ticket.queue.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.ticket.queue.domain.QueueEntryStatus;
import com.ticket.queue.domain.QueueTicket;
import com.ticket.queue.domain.QueueTicketStore;
import java.time.Duration;
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
        when(queueTicketStore.findTicket(1L, "session-1"))
                .thenReturn(Optional.of(new QueueTicket(1L, "session-1", QueueEntryStatus.WAITING, 1L)));
        when(queueTicketStore.findWaitingPosition(1L, "session-1")).thenReturn(Optional.of(3L));

        GetQueueStatusUseCase.Output output = queueStatusReader.read(1L, "session-1");

        assertThat(output.status()).isEqualTo(QueueEntryStatus.WAITING);
        assertThat(output.position()).isEqualTo(3L);
        assertThat(output.activeTtl()).isNull();
    }

    @Test
    void active_member는_남은_active_ttl을_반환한다() {
        when(queueTicketStore.findTicket(1L, "session-1"))
                .thenReturn(Optional.of(new QueueTicket(
                        1L,
                        "session-1",
                        QueueEntryStatus.ADMITTED,
                        null,
                        Duration.ofMinutes(3)
                )));

        GetQueueStatusUseCase.Output output = queueStatusReader.read(1L, "session-1");

        assertThat(output.status()).isEqualTo(QueueEntryStatus.ADMITTED);
        assertThat(output.position()).isNull();
        assertThat(output.activeTtl()).isEqualTo(Duration.ofMinutes(3));
    }

    @Test
    void ticket이_없으면_expired를_반환한다() {
        when(queueTicketStore.findTicket(1L, "session-1")).thenReturn(Optional.empty());

        GetQueueStatusUseCase.Output output = queueStatusReader.read(1L, "session-1");

        assertThat(output.status()).isEqualTo(QueueEntryStatus.EXPIRED);
        assertThat(output.position()).isNull();
        assertThat(output.activeTtl()).isNull();
    }
}
