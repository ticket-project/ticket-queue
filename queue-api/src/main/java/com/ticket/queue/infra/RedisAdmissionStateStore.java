package com.ticket.queue.infra;

import static com.ticket.queue.infra.RedisScriptLoader.load;
import static com.ticket.queue.infra.RedisValues.asLong;
import static com.ticket.queue.infra.RedisValues.asString;
import static com.ticket.queue.infra.RedisValues.parseLong;

import com.ticket.queue.domain.AdmissionStateStore;
import com.ticket.queue.domain.EnterResult;
import com.ticket.queue.domain.JoinResult;
import com.ticket.queue.domain.PublicState;
import com.ticket.queue.domain.QueueShardSlot;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.redisson.api.RMap;
import org.redisson.api.RScript;
import org.redisson.api.RSet;
import org.redisson.api.RedissonClient;
import org.redisson.client.RedisException;
import org.redisson.client.codec.StringCodec;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class RedisAdmissionStateStore implements AdmissionStateStore {

    private static final long WAITING_MARKER_TTL_MILLIS = 10_000L;
    private static final String JOIN_QUEUE_SCRIPT = load("redis/join_queue.lua");
    private static final String ENTER_QUEUE_SCRIPT = load("redis/enter_queue.lua");
    private static final String ADMIT_QUEUE_SESSION_SCRIPT = load("redis/admit_queue_session.lua");
    private static final String LEGACY_ENTER_QUEUE_SCRIPT = load("redis/legacy_enter_queue.lua");
    private static final String STATUS_OPEN = "OPEN";
    private static final String STATUS_EMPTY = "EMPTY";
    private static final String FIELD_STATUS = "status";
    private static final String FIELD_SHARD_COUNT = "shardCount";
    private static final String FIELD_SLOT_SIZE_MILLIS = "slotSizeMillis";
    private static final String FIELD_SERVING = "serving";
    private static final String FIELD_TAIL = "tail";
    private static final String FIELD_ADMITTED_UNTIL_SEQ = "admittedUntilSeq";
    private static final String FIELD_TAIL_SEQ = "tailSeq";
    private static final String FIELD_REFRESH_AFTER_MS = "refreshAfterMs";
    private static final String FIELD_RR_CURSOR = "rrCursor";
    private static final long ENTER_ADMITTED = 1L;
    private static final long ENTER_FULL = 2L;
    private static final long ENTER_EXPIRED = 3L;
    private static final long SESSION_ADMIT_DISABLED = 0L;
    private static final long SESSION_ADMIT_ENABLED = 1L;

    private final RedissonClient redissonClient;
    private final Map<String, String> scriptShaCache = new ConcurrentHashMap<>();

    @Override
    public JoinResult joinQueue(
            final Long performanceId,
            final String userIdHash,
            final String candidateQueueId,
            final QueueShardSlot shardSlot,
            final Duration queueTtl
    ) {
        validatePositive(performanceId, "performanceId");
        validateNotBlank(userIdHash, "userIdHash");
        validateNotBlank(candidateQueueId, "candidateQueueId");
        validateShardSlot(shardSlot);

        JoinScriptResult result = runJoinScript(performanceId, userIdHash, candidateQueueId, shardSlot, queueTtl);
        if (result.shouldRegisterWaitingPerformance()) {
            waitingPerformanceSet().add(performanceKey(performanceId));
        }

        return result.toJoinResult();
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
            final int shardId,
            final Long localSeq,
            final String admissionToken,
            final Duration shoppingSessionTtl,
            final int maxActiveSessions
    ) {
        validatePositive(performanceId, "performanceId");
        validateNotBlank(queueId, "queueId");
        validateNonNegative(shardId, "shardId");
        validatePositive(localSeq, "localSeq");
        validateNotBlank(admissionToken, "admissionToken");
        if (maxActiveSessions <= 0) {
            throw new IllegalArgumentException("maxActiveSessions must be positive");
        }

        EnterResult existing = toEnterResult(runSessionScript(
                performanceId,
                queueId,
                admissionToken,
                shoppingSessionTtl,
                maxActiveSessions,
                SESSION_ADMIT_DISABLED
        ));
        if (existing.status() == EnterResult.Status.ADMITTED) {
            return existing;
        }

        long readiness = asLong(runEnterReadinessScript(performanceId, queueId, shardId, localSeq).get(0));
        if (readiness == ENTER_EXPIRED) {
            return EnterResult.expired();
        }
        if (readiness != ENTER_ADMITTED) {
            return EnterResult.notAdmitted();
        }

        return toEnterResult(runSessionScript(
                performanceId,
                queueId,
                admissionToken,
                shoppingSessionTtl,
                maxActiveSessions,
                SESSION_ADMIT_ENABLED
        ));
    }

    @Override
    public EnterResult enterLegacyQueue(
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

        return toEnterResult(evalScript(
                LEGACY_ENTER_QUEUE_SCRIPT,
                RScript.ReturnType.LIST,
                legacyEnterKeys(performanceId, queueId),
                seq,
                admissionToken,
                ttlDuration(shoppingSessionTtl).toMillis(),
                maxActiveSessions
        ));
    }

    private JoinScriptResult runJoinScript(
            final Long performanceId,
            final String userIdHash,
            final String candidateQueueId,
            final QueueShardSlot shardSlot,
            final Duration queueTtl
    ) {
        List<Object> result = evalScript(
                JOIN_QUEUE_SCRIPT,
                RScript.ReturnType.LIST,
                joinKeys(performanceId, userIdHash, candidateQueueId, shardSlot.shardId()),
                candidateQueueId,
                userIdHash,
                ttlDuration(queueTtl).toMillis(),
                shardSlot.slotId(),
                shardSlot.slotStartMillis(),
                WAITING_MARKER_TTL_MILLIS
        );
        return JoinScriptResult.from(performanceId, shardSlot.shardId(), result);
    }

    private List<Object> joinKeys(
            final Long performanceId,
            final String userIdHash,
            final String candidateQueueId,
            final int shardId
    ) {
        return List.of(
                RedisKey.shardSequence(performanceId, shardId),
                RedisKey.shardUser(performanceId, shardId, userIdHash),
                RedisKey.shardQueue(performanceId, shardId, candidateQueueId),
                RedisKey.shardSlotTail(performanceId, shardId),
                RedisKey.shardPendingSlots(performanceId, shardId),
                RedisKey.shardWaitingMarker(performanceId, shardId)
        );
    }

    private PublicState toPublicState(
            final Long performanceId,
            final Map<String, String> values,
            final long defaultRefreshAfterMs
    ) {
        Map<Integer, Long> serving = parseShardMap(values.get(FIELD_SERVING));
        Map<Integer, Long> tail = parseShardMap(values.get(FIELD_TAIL));
        long refreshAfterMs = parseLong(values.get(FIELD_REFRESH_AFTER_MS), defaultRefreshAfterMs);
        int shardCount = Math.toIntExact(parseLong(values.get(FIELD_SHARD_COUNT), Math.max(serving.size(), tail.size())));
        long slotSizeMillis = parseLong(values.get(FIELD_SLOT_SIZE_MILLIS), 0L);

        return new PublicState(
                performanceId,
                publicStatus(values, serving, tail),
                shardCount,
                slotSizeMillis,
                serving,
                tail,
                refreshAfterMs,
                System.currentTimeMillis()
        );
    }

    private String publicStatus(
            final Map<String, String> values,
            final Map<Integer, Long> serving,
            final Map<Integer, Long> tail
    ) {
        return values.getOrDefault(FIELD_STATUS, hasPending(serving, tail) ? STATUS_OPEN : STATUS_EMPTY);
    }

    private List<Object> runEnterReadinessScript(
            final Long performanceId,
            final String queueId,
            final int shardId,
            final Long localSeq
    ) {
        return evalScript(
                ENTER_QUEUE_SCRIPT,
                RScript.ReturnType.LIST,
                enterKeys(performanceId, queueId, shardId),
                localSeq
        );
    }

    private List<Object> runSessionScript(
            final Long performanceId,
            final String queueId,
            final String admissionToken,
            final Duration shoppingSessionTtl,
            final int maxActiveSessions,
            final long admitRequested
    ) {
        return evalScript(
                ADMIT_QUEUE_SESSION_SCRIPT,
                RScript.ReturnType.LIST,
                sessionKeys(performanceId, queueId),
                admissionToken,
                ttlDuration(shoppingSessionTtl).toMillis(),
                maxActiveSessions,
                admitRequested,
                queueId
        );
    }

    private List<Object> enterKeys(
            final Long performanceId,
            final String queueId,
            final int shardId
    ) {
        return List.of(
                RedisKey.shardState(performanceId, shardId),
                RedisKey.shardQueue(performanceId, shardId, queueId)
        );
    }

    private List<Object> sessionKeys(
            final Long performanceId,
            final String queueId
    ) {
        return List.of(
                RedisKey.performanceEntered(performanceId, queueId),
                RedisKey.performanceSessions(performanceId)
        );
    }

    private List<Object> legacyEnterKeys(
            final Long performanceId,
            final String queueId
    ) {
        return List.of(
                RedisKey.publicState(performanceId),
                RedisKey.performanceEntered(performanceId, queueId),
                RedisKey.performanceSessions(performanceId),
                RedisKey.legacyQueue(performanceId, queueId)
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

    private <T> T evalScript(
            final String scriptBody,
            final RScript.ReturnType returnType,
            final List<Object> keys,
            final Object... args
    ) {
        RScript script = redissonClient.getScript(StringCodec.INSTANCE);
        String scriptSha = scriptShaCache.computeIfAbsent(scriptBody, script::scriptLoad);
        try {
            return script.evalSha(RScript.Mode.READ_WRITE, scriptSha, returnType, keys, args);
        } catch (RedisException exception) {
            if (!isNoScript(exception)) {
                throw exception;
            }
            String reloadedSha = script.scriptLoad(scriptBody);
            scriptShaCache.put(scriptBody, reloadedSha);
            return script.evalSha(RScript.Mode.READ_WRITE, reloadedSha, returnType, keys, args);
        }
    }

    private boolean isNoScript(final RedisException exception) {
        String message = exception.getMessage();
        return message != null && message.contains("NOSCRIPT");
    }

    private boolean hasPending(final Map<Integer, Long> serving, final Map<Integer, Long> tail) {
        return tail.entrySet().stream()
                .anyMatch(entry -> entry.getValue() > serving.getOrDefault(entry.getKey(), 0L));
    }

    private Map<Integer, Long> parseShardMap(final String encoded) {
        Map<Integer, Long> result = new LinkedHashMap<>();
        if (encoded == null || encoded.isBlank()) {
            return result;
        }
        for (String entry : encoded.split(",")) {
            String[] parts = entry.split(":", -1);
            if (parts.length == 2 && !parts[0].isBlank() && !parts[1].isBlank()) {
                try {
                    result.put(Integer.parseInt(parts[0]), Long.parseLong(parts[1]));
                } catch (NumberFormatException ignored) {
                    // Ignore malformed public state entries so one bad value does not break /state.
                }
            }
        }
        return result;
    }

    private RMap<String, String> publicStateMap(final Long performanceId) {
        return redissonClient.getMap(RedisKey.publicState(performanceId), StringCodec.INSTANCE);
    }

    private RSet<String> waitingPerformanceSet() {
        return redissonClient.getSet(RedisKey.waitingPerformances(), StringCodec.INSTANCE);
    }

    private String performanceKey(final Long value) {
        return String.valueOf(value);
    }

    private Duration ttlDuration(final Duration duration) {
        return Duration.ofMillis(Math.max(1L, duration.toMillis()));
    }

    private void validateShardSlot(final QueueShardSlot shardSlot) {
        if (shardSlot == null) {
            throw new IllegalArgumentException("shardSlot must not be null");
        }
        validateNonNegative(shardSlot.shardId(), "shardId");
        if (shardSlot.slotId() < 0) {
            throw new IllegalArgumentException("slotId must be non-negative");
        }
        if (shardSlot.slotStartMillis() < 0) {
            throw new IllegalArgumentException("slotStartMillis must be non-negative");
        }
    }

    private void validatePositive(final Long value, final String name) {
        if (value == null || value <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }

    private void validateNonNegative(final int value, final String name) {
        if (value < 0) {
            throw new IllegalArgumentException(name + " must be non-negative");
        }
    }

    private void validateNotBlank(final String value, final String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }

    private record JoinScriptResult(
            Long performanceId,
            String queueId,
            int shardId,
            long localSeq,
            long slotId,
            long slotStartMillis,
            boolean created,
            boolean shouldRegisterWaitingPerformance
    ) {

        private static JoinScriptResult from(
                final Long performanceId,
                final int shardId,
                final List<Object> result
        ) {
            return new JoinScriptResult(
                    performanceId,
                    asString(result.get(0)),
                    shardId,
                    asLong(result.get(1)),
                    asLong(result.get(2)),
                    asLong(result.get(3)),
                    asLong(result.get(4)) == 1L,
                    result.size() > 5 && asLong(result.get(5)) == 1L
            );
        }

        private JoinResult toJoinResult() {
            return new JoinResult(
                    performanceId,
                    queueId,
                    shardId,
                    localSeq,
                    slotId,
                    slotStartMillis,
                    created
            );
        }
    }
}
