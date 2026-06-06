package com.ticket.queue.application;

import com.ticket.queue.domain.QueueEntryStatus;
import com.ticket.queue.domain.QueueTicket;
import com.ticket.queue.domain.QueueTicketStore;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class QueueStatusReader {

    private final QueueTicketStore queueTicketStore;

    public Result read(final Long performanceId, final String queueSessionId) {
        return queueTicketStore.findTicket(performanceId, queueSessionId)
                .map(this::read)
                .orElseGet(() -> new Result(QueueEntryStatus.EXPIRED, null, null));
    }

    private Result read(final QueueTicket ticket) {
        if (ticket.isWaiting()) {
            return new Result(QueueEntryStatus.WAITING, ticket.position(), null);
        }
        if (ticket.isAdmitted()) {
            return new Result(QueueEntryStatus.ADMITTED, null, ticket.activeTtl());
        }
        return new Result(QueueEntryStatus.EXPIRED, null, null);
    }

    public record Result(QueueEntryStatus status, Long position, Duration activeTtl) {
    }
}
