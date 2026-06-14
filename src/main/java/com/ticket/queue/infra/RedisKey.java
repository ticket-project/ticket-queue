package com.ticket.queue.infra;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class RedisKey {

    public static String waitingPerformances() {
        return "queue:waiting:performances";
    }

    public static String publicSequence(final Long performanceId) {
        return "q:" + performanceHashTag(performanceId) + ":seq";
    }

    public static String publicState(final Long performanceId) {
        return "q:" + performanceHashTag(performanceId) + ":state";
    }

    public static String publicUser(final Long performanceId, final String userIdHash) {
        return "q:" + performanceHashTag(performanceId) + ":user:" + userIdHash;
    }

    public static String publicQueue(final Long performanceId, final String queueId) {
        return "q:" + performanceHashTag(performanceId) + ":queue:" + queueId;
    }

    public static String publicEntered(final Long performanceId, final String queueId) {
        return "q:" + performanceHashTag(performanceId) + ":entered:" + queueId;
    }

    public static String publicSessions(final Long performanceId) {
        return "q:" + performanceHashTag(performanceId) + ":sessions";
    }

    public static String publicJoinStream(final Long performanceId) {
        return "q:" + performanceHashTag(performanceId) + ":join-stream";
    }

    public static String advanceLock(final Long performanceId) {
        return "lock:queue:advance:" + performanceId;
    }

    private static String performanceHashTag(final Long performanceId) {
        return "{" + performanceId + "}";
    }
}
