package com.ticket.queue.infra;

import com.ticket.queue.domain.JoinResult;
import com.ticket.queue.domain.EnterResult;
import com.ticket.queue.domain.PublicState;
import com.ticket.queue.domain.AdmissionStateStore;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.redisson.api.RLock;
import org.redisson.api.RMap;
import org.redisson.api.RScript;
import org.redisson.api.RSet;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Repository;
import org.springframework.util.StreamUtils;

@Repository
@RequiredArgsConstructor
public class RedisAdmissionStateStore implements AdmissionStateStore {

    private static final long ADVANCE_LOCK_LEASE_MILLIS = 5_000L;
    private static final String JOIN_QUEUE_SCRIPT = readScript("redis/join_queue.lua");
    private static final String ENTER_QUEUE_SCRIPT = readScript("redis/enter_queue.lua");
    private static final String ADVANCE_QUEUE_STATE_SCRIPT = readScript("redis/advance_queue_state.lua");

    private final RedissonClient redissonClient;

    @Override
    public Set<Long> findWaitingPerformanceIds() {
        return waitingPerformanceSet().readAll()
                .stream()
                .map(Long::valueOf)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    @Override
    public JoinResult joinQueue(
            final Long performanceId,
            final String userIdHash,
            final String candidateQueueId,
            final Duration queueTtl,
            final long refreshAfterMs
    ) {
        validatePositive(performanceId, "performanceId");
        validateNotBlank(userIdHash, "userIdHash");
        validateNotBlank(candidateQueueId, "candidateQueueId");
        if (refreshAfterMs <= 0) {
            throw new IllegalArgumentException("refreshAfterMs must be positive");
        }

        List<Object> result = redissonClient.getScript(StringCodec.INSTANCE).eval(
                RScript.Mode.READ_WRITE,
                JOIN_QUEUE_SCRIPT,
                RScript.ReturnType.LIST,
                List.of(
                        RedisKey.publicSequence(performanceId),
                        RedisKey.publicState(performanceId),
                        RedisKey.publicUser(performanceId, userIdHash),
                        RedisKey.publicQueue(performanceId, candidateQueueId),
                        RedisKey.publicJoinStream(performanceId)
                ),
                candidateQueueId,
                userIdHash,
                ttlDuration(queueTtl).toMillis(),
                refreshAfterMs
        );
        waitingPerformanceSet().add(performanceKey(performanceId));

        return new JoinResult(
                performanceId,
                asString(result.get(0)),
                asLong(result.get(1)),
                asLong(result.get(2)) == 1L
        );
    }

    @Override
    public PublicState readPublicState(final Long performanceId, final long refreshAfterMs) {
        validatePositive(performanceId, "performanceId");
        if (refreshAfterMs <= 0) {
            throw new IllegalArgumentException("refreshAfterMs must be positive");
        }

        Map<String, String> values = publicStateMap(performanceId).readAllMap();
        long admittedUntilSeq = parseLong(values.get("admittedUntilSeq"), 0L);
        long tailSeq = parseLong(values.get("tailSeq"), 0L);
        long normalizedRefreshAfterMs = parseLong(values.get("refreshAfterMs"), refreshAfterMs);
        String status = values.getOrDefault("status", tailSeq > 0 ? "OPEN" : "EMPTY");

        return new PublicState(
                performanceId,
                status,
                admittedUntilSeq,
                tailSeq,
                normalizedRefreshAfterMs,
                System.currentTimeMillis()
        );
    }

    @Override
    public EnterResult enterQueue(
            final Long performanceId,
            final String queueId,
            final Long seq,
            final String admissionToken,
            final Duration shoppingSessionTtl,
            final int maxActiveSessions
    ) {
        validatePositive(performanceId, "performanceId");
        validateNotBlank(queueId, "queueId");
        validatePositive(seq, "seq");
        validateNotBlank(admissionToken, "admissionToken");
        if (maxActiveSessions <= 0) {
            throw new IllegalArgumentException("maxActiveSessions must be positive");
        }

        List<Object> result = redissonClient.getScript(StringCodec.INSTANCE).eval(
                RScript.Mode.READ_WRITE,
                ENTER_QUEUE_SCRIPT,
                RScript.ReturnType.LIST,
                List.of(
                        RedisKey.publicState(performanceId),
                        RedisKey.publicEntered(performanceId, queueId),
                        RedisKey.publicSessions(performanceId),
                        RedisKey.publicQueue(performanceId, queueId)
                ),
                seq,
                admissionToken,
                ttlDuration(shoppingSessionTtl).toMillis(),
                maxActiveSessions
        );

        long status = asLong(result.get(0));
        if (status == 1L) {
            return EnterResult.admitted(asString(result.get(1)), asLong(result.get(2)));
        }
        if (status == 2L) {
            return EnterResult.full();
        }
        if (status == 3L) {
            return EnterResult.expired();
        }
        return EnterResult.notAdmitted();
    }

    @Override
    public void advancePublicState(
            final Long performanceId,
            final int maxAdmitPerSecond,
            final int maxActiveSessions
    ) {
        validatePositive(performanceId, "performanceId");
        if (maxAdmitPerSecond <= 0 || maxActiveSessions <= 0) {
            return;
        }

        RLock lock = redissonClient.getLock(RedisKey.advanceLock(performanceId));
        if (!tryAdvanceLock(lock)) {
            return;
        }
        try {
            redissonClient.getScript(StringCodec.INSTANCE).eval(
                    RScript.Mode.READ_WRITE,
                    ADVANCE_QUEUE_STATE_SCRIPT,
                    RScript.ReturnType.LONG,
                    List.of(RedisKey.publicState(performanceId), RedisKey.publicSessions(performanceId)),
                    maxAdmitPerSecond,
                    maxActiveSessions
            );
            removeWaitingPerformanceIfNoPendingPublicQueue(performanceId);
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    private boolean tryAdvanceLock(final RLock lock) {
        try {
            return lock.tryLock(0L, ADVANCE_LOCK_LEASE_MILLIS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private RMap<String, String> publicStateMap(final Long performanceId) {
        return redissonClient.getMap(RedisKey.publicState(performanceId), StringCodec.INSTANCE);
    }

    private RSet<String> waitingPerformanceSet() {
        return redissonClient.getSet(RedisKey.waitingPerformances(), StringCodec.INSTANCE);
    }

    private void removeWaitingPerformanceIfNoPendingPublicQueue(final Long performanceId) {
        PublicState state = readPublicState(performanceId, 1L);
        if (state.tailSeq() <= state.admittedUntilSeq()) {
            waitingPerformanceSet().remove(performanceKey(performanceId));
        }
    }

    private String performanceKey(final Long value) {
        return String.valueOf(value);
    }

    private Duration ttlDuration(final Duration duration) {
        return Duration.ofMillis(Math.max(1L, duration.toMillis()));
    }

    private static String readScript(final String location) {
        try {
            return StreamUtils.copyToString(
                    new ClassPathResource(location).getInputStream(),
                    StandardCharsets.UTF_8
            );
        } catch (IOException exception) {
            throw new IllegalStateException("failed to read Redis script " + location, exception);
        }
    }

    private String asString(final Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private long asLong(final Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String stringValue) {
            return Long.parseLong(stringValue);
        }
        throw new IllegalStateException("Redis script returned non numeric value");
    }

    private long parseLong(final String value, final long defaultValue) {
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException exception) {
            return defaultValue;
        }
    }

    private void validatePositive(final Long value, final String name) {
        if (value == null || value <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }

    private void validateNotBlank(final String value, final String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
