package com.ticket.queue.application;

public record QueueTokenClaims(
        Long performanceId,
        String queueId,
        Long seq,
        Long memberId
) {
}
