package com.ticket.queue.api;

public record JoinResponse(
        Long performanceId,
        String queueId,
        Long seq,
        String status,
        String queueToken
) {
}
