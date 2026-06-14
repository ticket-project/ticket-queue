package com.ticket.queue.infra;

import static com.ticket.queue.infra.RedisScriptLoader.load;
import static com.ticket.queue.infra.RedisValues.asLong;
import static com.ticket.queue.infra.RedisValues.asString;
import static com.ticket.queue.infra.RedisValues.parseLong;

import com.ticket.queue.domain.AdmissionStateStore;
import com.ticket.queue.domain.EnterResult;
import com.ticket.queue.domain.JoinResult;
import com.ticket.queue.domain.PublicState;
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
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class RedisAdmissionStateStore implements AdmissionStateStore {

    private static final long ADVANCE_LOCK_LEASE_MILLIS = 5_000L;
    private static final String JOIN_QUEUE_SCRIPT = load("redis/join_queue.lua");
    private static final String ENTER_QUEUE_SCRIPT = load("redis/enter_queue.lua");
    private static final String ADVANCE_QUEUE_STATE_SCRIPT = load("redis/advance_queue_state.lua");
    private static final String STATUS_OPEN = "OPEN";
    private static final String STATUS_EMPTY = "EMPTY";
    private static final String FIELD_STATUS = "status";
    private static final String FIELD_ADMITTED_UNTIL_SEQ = "admittedUntilSeq";
    private static final String FIELD_TAIL_SEQ = "tailSeq";
    private static final String FIELD_REFRESH_AFTER_MS = "refreshAfterMs";
    private static final long ENTER_ADMITTED = 1L;
    private static final long ENTER_FULL = 2L;
    private static final long ENTER_EXPIRED = 3L;

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

        List<Object> result = runJoinScript(performanceId, userIdHash, candidateQueueId, queueTtl, refreshAfterMs);
        waitingPerformanceSet().add(performanceKey(performanceId));

        return toJoinResult(performanceId, result);
    }

    @Override
    public PublicState readPublicState(final Long performanceId, final long refreshAfterMs) {
        validatePositive(performanceId, "performanceId");
        if (refreshAfterMs <= 0) {
            throw new IllegalArgumentException("refreshAfterMs must be positive");
        }

        return toPublicState(performanceId, publicStateMap(performanceId).readAllMap(), refreshAfterMs);
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

        return toEnterResult(runEnterScript(
                performanceId,
                queueId,
                seq,
                admissionToken,
                shoppingSessionTtl,
                maxActiveSessions
        ));
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
            runAdvanceScript(performanceId, maxAdmitPerSecond, maxActiveSessions);
            removeWaitingPerformanceIfNoPendingPublicQueue(performanceId);
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    private List<Object> runJoinScript(
            final Long performanceId,
            final String userIdHash,
            final String candidateQueueId,
            final Duration queueTtl,
            final long refreshAfterMs
    ) {
        return redissonClient.getScript(StringCodec.INSTANCE).eval(
                RScript.Mode.READ_WRITE,
                JOIN_QUEUE_SCRIPT,
                RScript.ReturnType.LIST,
                joinKeys(performanceId, userIdHash, candidateQueueId),
                candidateQueueId,
                userIdHash,
                ttlDuration(queueTtl).toMillis(),
                refreshAfterMs
        );
    }

    private List<Object> joinKeys(
            final Long performanceId,
            final String userIdHash,
            final String candidateQueueId
    ) {
        return List.of(
                RedisKey.publicSequence(performanceId),
                RedisKey.publicState(performanceId),
                RedisKey.publicUser(performanceId, userIdHash),
                RedisKey.publicQueue(performanceId, candidateQueueId),
                RedisKey.publicJoinStream(performanceId)
        );
    }

    private JoinResult toJoinResult(
            final Long performanceId,
            final List<Object> result
    ) {
        return new JoinResult(
                performanceId,
                asString(result.get(0)),
                asLong(result.get(1)),
                asLong(result.get(2)) == 1L
        );
    }

    private PublicState toPublicState(
            final Long performanceId,
            final Map<String, String> values,
            final long defaultRefreshAfterMs
    ) {
        long admittedUntilSeq = parseLong(values.get(FIELD_ADMITTED_UNTIL_SEQ), 0L);
        long tailSeq = parseLong(values.get(FIELD_TAIL_SEQ), 0L);
        long refreshAfterMs = parseLong(values.get(FIELD_REFRESH_AFTER_MS), defaultRefreshAfterMs);

        return new PublicState(
                performanceId,
                publicStatus(values, tailSeq),
                admittedUntilSeq,
                tailSeq,
                refreshAfterMs,
                System.currentTimeMillis()
        );
    }

    private String publicStatus(
            final Map<String, String> values,
            final long tailSeq
    ) {
        return values.getOrDefault(FIELD_STATUS, tailSeq > 0 ? STATUS_OPEN : STATUS_EMPTY);
    }

    private List<Object> runEnterScript(
            final Long performanceId,
            final String queueId,
            final Long seq,
            final String admissionToken,
            final Duration shoppingSessionTtl,
            final int maxActiveSessions
    ) {
        return redissonClient.getScript(StringCodec.INSTANCE).eval(
                RScript.Mode.READ_WRITE,
                ENTER_QUEUE_SCRIPT,
                RScript.ReturnType.LIST,
                enterKeys(performanceId, queueId),
                seq,
                admissionToken,
                ttlDuration(shoppingSessionTtl).toMillis(),
                maxActiveSessions
        );
    }

    private List<Object> enterKeys(
            final Long performanceId,
            final String queueId
    ) {
        return List.of(
                RedisKey.publicState(performanceId),
                RedisKey.publicEntered(performanceId, queueId),
                RedisKey.publicSessions(performanceId),
                RedisKey.publicQueue(performanceId, queueId)
        );
    }

    private EnterResult toEnterResult(final List<Object> result) {
        long status = asLong(result.get(0));
        if (status == ENTER_ADMITTED) {
            return EnterResult.admitted(asString(result.get(1)), asLong(result.get(2)));
        }
        if (status == ENTER_FULL) {
            return EnterResult.full();
        }
        if (status == ENTER_EXPIRED) {
            return EnterResult.expired();
        }
        return EnterResult.notAdmitted();
    }

    private void runAdvanceScript(
            final Long performanceId,
            final int maxAdmitPerSecond,
            final int maxActiveSessions
    ) {
        redissonClient.getScript(StringCodec.INSTANCE).eval(
                RScript.Mode.READ_WRITE,
                ADVANCE_QUEUE_STATE_SCRIPT,
                RScript.ReturnType.LONG,
                List.of(RedisKey.publicState(performanceId), RedisKey.publicSessions(performanceId)),
                maxAdmitPerSecond,
                maxActiveSessions
        );
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
