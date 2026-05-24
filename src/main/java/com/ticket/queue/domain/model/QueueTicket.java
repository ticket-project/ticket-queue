package com.ticket.queue.domain.model;

import com.ticket.queue.domain.support.exception.CoreException;
import com.ticket.queue.domain.support.exception.ErrorType;

public record QueueTicket(
        Long performanceId,
        Long memberId,
        QueueEntryStatus status,
        Long sequence
) {

    public QueueTicket {
        validatePositive(performanceId, "performanceId");
        validatePositive(memberId, "memberId");
        if (status == null) {
            throw new IllegalArgumentException("status must not be null");
        }
    }

    public boolean isWaiting() {
        return status == QueueEntryStatus.WAITING;
    }

    public boolean isAdmitted() {
        return status == QueueEntryStatus.ADMITTED;
    }

    public void assertOwnedBy(final Long expectedPerformanceId, final Long expectedMemberId) {
        if (!performanceId.equals(expectedPerformanceId) || !memberId.equals(expectedMemberId)) {
            throw new CoreException(ErrorType.AUTHORIZATION_ERROR, "queue ticket owner mismatch");
        }
    }

    private static void validatePositive(final Long value, final String name) {
        if (value == null || value <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }
}