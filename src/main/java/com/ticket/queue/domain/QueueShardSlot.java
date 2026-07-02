package com.ticket.queue.domain;

public record QueueShardSlot(
        int shardId,
        long slotId,
        long slotStartMillis
) {
}
