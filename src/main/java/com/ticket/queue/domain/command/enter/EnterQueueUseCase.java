package com.ticket.queue.domain.command.enter;

import com.ticket.queue.domain.model.QueueEntryStatus;
import com.ticket.queue.domain.model.QueuePolicy;
import com.ticket.queue.domain.model.QueueTicket;
import com.ticket.queue.domain.port.QueueTicketStore;
import com.ticket.queue.domain.service.QueuePolicyResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class EnterQueueUseCase {

    private final QueuePolicyResolver queuePolicyResolver;
    private final QueueTicketStore queueTicketStore;

    public Output execute(final Input input) {
        validate(input);
        QueuePolicy policy = queuePolicyResolver.resolve(input.performanceId());
        QueueTicket ticket = queueTicketStore.registerWaiting(
                input.performanceId(),
                input.memberId(),
                policy.entryRetention()
        );
        if (ticket.isAdmitted()) {
            return new Output(QueueEntryStatus.ADMITTED, null);
        }

        Long position = queueTicketStore.findWaitingPosition(input.performanceId(), input.memberId())
                .orElse(1L);
        return new Output(QueueEntryStatus.WAITING, position);
    }

    private void validate(final Input input) {
        if (input == null) {
            throw new IllegalArgumentException("input must not be null");
        }
        validatePositive(input.performanceId(), "performanceId");
        validatePositive(input.memberId(), "memberId");
    }

    private void validatePositive(final Long value, final String name) {
        if (value == null || value <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }

    public record Input(Long performanceId, Long memberId) {
    }

    public record Output(QueueEntryStatus status, Long position) {
    }
}