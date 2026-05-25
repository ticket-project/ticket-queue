package com.ticket.queue.application;

import com.ticket.queue.domain.QueueTicketStore;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class EnterQueueUseCase {

    private final QueueTicketStore queueTicketStore;

    public void execute(final Input input) {
        validate(input);
        queueTicketStore.registerWaiting(
                input.performanceId(),
                input.queueSessionId(),
                input.sessionTtl()
        );
    }

    private void validate(final Input input) {
        if (input == null) {
            throw new IllegalArgumentException("input must not be null");
        }
        validatePositive(input.performanceId(), "performanceId");
        validateNotBlank(input.queueSessionId(), "queueSessionId");
        validatePositive(input.sessionTtl(), "sessionTtl");
    }

    private void validatePositive(final Long value, final String name) {
        if (value == null || value <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }

    private void validateNotBlank(final String value, final String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }

    private void validatePositive(final Duration value, final String name) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }

    public record Input(Long performanceId, String queueSessionId, Duration sessionTtl) {
    }
}
