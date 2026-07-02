package com.ticket.queue.infra;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class RedisKey {

    public static String waitingPerformances() {
        return "queue:waiting:performances";
    }

    public static String shardSequence(final Long performanceId, final int shardId) {
        return "q:" + shardHashTag(performanceId, shardId) + ":seq";
    }

    public static String publicState(final Long performanceId) {
        return "q:" + performanceHashTag(performanceId) + ":state";
    }

    public static String shardState(final Long performanceId, final int shardId) {
        return "q:" + shardHashTag(performanceId, shardId) + ":state";
    }

    public static String shardUser(final Long performanceId, final int shardId, final String userIdHash) {
        return "q:" + shardHashTag(performanceId, shardId) + ":user:" + userIdHash;
    }

    public static String shardQueue(final Long performanceId, final int shardId, final String queueId) {
        return "q:" + shardHashTag(performanceId, shardId) + ":queue:" + queueId;
    }

    public static String shardEntered(final Long performanceId, final int shardId, final String queueId) {
        return "q:" + shardHashTag(performanceId, shardId) + ":entered:" + queueId;
    }

    public static String shardSessions(final Long performanceId, final int shardId) {
        return "q:" + shardHashTag(performanceId, shardId) + ":sessions";
    }

    public static String shardSlotTail(final Long performanceId, final int shardId) {
        return "q:" + shardHashTag(performanceId, shardId) + ":slot-tail";
    }

    public static String shardPendingSlots(final Long performanceId, final int shardId) {
        return "q:" + shardHashTag(performanceId, shardId) + ":pending-slots";
    }

    public static String shardWaitingMarker(final Long performanceId, final int shardId) {
        return "q:" + shardHashTag(performanceId, shardId) + ":waiting-marker";
    }

    public static String advanceLock(final Long performanceId) {
        return "lock:queue:advance:" + performanceId;
    }

    private static String performanceHashTag(final Long performanceId) {
        return "{" + performanceId + "}";
    }

    private static String shardHashTag(final Long performanceId, final int shardId) {
        return "{" + performanceId + ":" + shardId + "}";
    }
}
