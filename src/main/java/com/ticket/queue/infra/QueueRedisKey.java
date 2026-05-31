package com.ticket.queue.infra;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class QueueRedisKey {

    public static String waiting(final Long performanceId) {
        return "queue:" + performanceHashTag(performanceId) + ":waiting";
    }

    public static String active(final Long performanceId) {
        return "queue:" + performanceHashTag(performanceId) + ":active";
    }

    public static String sequence(final Long performanceId) {
        return "queue:" + performanceHashTag(performanceId) + ":sequence";
    }

    public static String policy(final Long performanceId) {
        return "queue:" + performanceHashTag(performanceId) + ":policy";
    }

    public static String memberState(final Long performanceId, final String queueSessionId) {
        return memberStatePrefix(performanceId) + queueSessionId;
    }

    public static String memberStatePrefix(final Long performanceId) {
        return "queue:" + performanceHashTag(performanceId) + ":member:";
    }

    public static String memberSession(final Long performanceId, final Long memberId) {
        return "queue:" + performanceHashTag(performanceId) + ":member-session:" + memberId;
    }

    public static String session(final String queueSessionId) {
        return "queue:session:" + queueSessionId;
    }

    public static String waitingPerformances() {
        return "queue:waiting:performances";
    }

    private static String performanceHashTag(final Long performanceId) {
        return "{" + performanceId + "}";
    }
}
