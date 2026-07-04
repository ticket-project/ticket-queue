package com.ticket.queue.application;

import com.ticket.queue.config.QueueProperties;
import com.ticket.queue.domain.QueueShardSlot;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class QueueShardSlotCalculator {

    private static final ThreadLocal<MessageDigest> SHA256_DIGEST = ThreadLocal.withInitial(
            QueueShardSlotCalculator::newSha256Digest
    );
    private static final ThreadLocal<ByteBuffer> HASH_INPUT_BUFFER = ThreadLocal.withInitial(
            () -> ByteBuffer.allocate(Long.BYTES * 2)
    );

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
        MessageDigest digest = SHA256_DIGEST.get();
        digest.reset();
        ByteBuffer input = HASH_INPUT_BUFFER.get();
        input.clear();
        input.putLong(performanceId);
        input.putLong(memberId);
        byte[] hashed = digest.digest(input.array());
        return ByteBuffer.wrap(hashed, 0, Integer.BYTES).getInt();
    }

    private static MessageDigest newSha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
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
