package com.ticket.queue.domain.model;

public final class QueueRedisKey {

    private QueueRedisKey() {
    }

    public static String waiting(final Long performanceId) {
        return "queue:waiting:" + performanceId;
    }

    public static String active(final Long performanceId) {
        return "queue:active:" + performanceId;
    }

    public static String sequence(final Long performanceId) {
        return "queue:sequence:" + performanceId;
    }

    public static String memberState(final Long performanceId, final Long memberId) {
        return "queue:member:" + performanceId + ":" + memberId;
    }

    public static String session(final String queueSessionId) {
        return "queue:session:" + queueSessionId;
    }

    public static String waitingPerformances() {
        return "queue:waiting:performances";
    }
}