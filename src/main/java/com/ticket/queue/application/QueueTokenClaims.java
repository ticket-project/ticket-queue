package com.ticket.queue.application;

public record QueueTokenClaims(
        Long performanceId,
        String queueId,
        int shardId,
        Long localSeq,
        Long slotId,
        Long memberId,
        boolean legacy
) {

    public QueueTokenClaims(
            final Long performanceId,
            final String queueId,
            final int shardId,
            final Long localSeq,
            final Long slotId,
            final Long memberId
    ) {
        this(performanceId, queueId, shardId, localSeq, slotId, memberId, false);
    }

    public static QueueTokenClaims legacy(
            final Long performanceId,
            final String queueId,
            final Long seq,
            final Long memberId
    ) {
        return new QueueTokenClaims(performanceId, queueId, 0, seq, 0L, memberId, true);
    }
}
