package com.ticket.queue.application;

import com.ticket.queue.config.QueueProperties;
import com.ticket.queue.domain.QueueShardSlot;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class QueueShardSlotCalculator {

    private final QueueProperties queueProperties;
    private final Clock clock;

    public QueueShardSlot calculate(final Long performanceId, final Long memberId) {
        validatePositive(performanceId, "performanceId");
        validatePositive(memberId, "memberId");
        int shardCount = queueProperties.getShardCount();
        long slotSizeMillis = queueProperties.getSlotSizeMillis();
        if (shardCount <= 0) {
            throw new IllegalStateException("app.queue.shard-count must be positive");
        }
        if (slotSizeMillis <= 0) {
            throw new IllegalStateException("app.queue.slot-size-millis must be positive");
        }

        long nowMillis = clock.millis();
        long slotId = Math.floorDiv(nowMillis, slotSizeMillis);
        long slotStartMillis = slotId * slotSizeMillis;
        int shardId = Math.floorMod(stableHash(performanceId, memberId), shardCount);
        return new QueueShardSlot(shardId, slotId, slotStartMillis);
    }

    private int stableHash(final Long performanceId, final Long memberId) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String input = performanceId + ":" + memberId;
            byte[] hashed = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return ByteBuffer.wrap(hashed, 0, Integer.BYTES).getInt();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }

    private void validatePositive(final Long value, final String name) {
        if (value == null || value <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }
}
