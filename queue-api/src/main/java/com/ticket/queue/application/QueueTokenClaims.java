package com.ticket.queue.application;

public record QueueTokenClaims(
        Long performanceId,
        String queueId,
        int shardId,
        Long localSeq,
        Long slotId,
        Long memberId
) {
}
