package com.ticket.queue.domain;

public record JoinResult(
        Long performanceId,
        String queueId,
        Long seq,
        boolean created
) {
}
