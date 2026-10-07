package com.ticket.queue.domain;

public record JoinResult(
        String queueId,
        int shardId,
        Long localSeq,
        Long slotId,
        Long slotStartMillis,
        boolean created
) {
}
