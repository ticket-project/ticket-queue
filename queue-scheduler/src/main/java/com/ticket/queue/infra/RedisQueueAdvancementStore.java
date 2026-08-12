package com.ticket.queue.infra;

import static com.ticket.queue.infra.RedisScriptLoader.load;
import static com.ticket.queue.infra.RedisValues.asLong;
import static com.ticket.queue.infra.RedisValues.parseLong;

import com.ticket.queue.domain.QueueAdvanceResult;
import com.ticket.queue.domain.QueueAdvancementStore;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.redisson.api.RLock;
import org.redisson.api.RMap;
import org.redisson.api.RScript;
import org.redisson.api.RSet;
import org.redisson.api.RedissonClient;
import org.redisson.client.RedisException;
import org.redisson.client.codec.StringCodec;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class RedisQueueAdvancementStore implements QueueAdvancementStore {

    private static final long ADVANCE_LOCK_LEASE_MILLIS = 5_000L;
    private static final String ADVANCE_QUEUE_STATE_SCRIPT = load("redis/advance_queue_state.lua");
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

    private final RedissonClient redissonClient;
    private final Map<String, String> scriptShaCache = new ConcurrentHashMap<>();

    @Override
    public Set<Long> findWaitingPerformanceIds() {
        return waitingPerformanceSet().readAll()
                .stream()
                .map(Long::valueOf)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    @Override
    public QueueAdvanceResult advancePublicState(
            final Long performanceId,
            final int advanceBatchSize,
            final int shardCount,
            final long slotSizeMillis,
            final long slotCloseGraceMillis,
            final Duration stateTtl,
            final long refreshAfterMs
    ) {
        validatePositive(performanceId, "performanceId");
        if (advanceBatchSize <= 0
                || shardCount <= 0
                || slotSizeMillis <= 0
                || slotCloseGraceMillis < 0
                || stateTtl == null
                || stateTtl.isZero()
                || stateTtl.isNegative()
                || refreshAfterMs <= 0) {
            return QueueAdvanceResult.skipped();
        }

        RLock lock = redissonClient.getLock(RedisKey.advanceLock(performanceId));
        if (!tryAdvanceLock(lock)) {
            return QueueAdvanceResult.skipped();
        }
        try {
            List<ShardQueueState> states = readShardStates(performanceId, shardCount, stateTtl);
            int rrCursor = readRoundRobinCursor(performanceId, shardCount);
            int remaining = advanceBatchSize;
            int admittedCount = 0;
            long nowMillis = System.currentTimeMillis();
            long lastClosedSlotId = Math.floorDiv(nowMillis - slotCloseGraceMillis, slotSizeMillis);

            while (remaining > 0) {
                long slotId = nextClosedSlotId(states, lastClosedSlotId);
                if (slotId < 0) {
                    break;
                }

                AdvancePlan plan = planSlotAdvances(states, slotId, remaining, rrCursor);
                if (!plan.hasAdvances()) {
                    break;
                }
                applyAdvancePlan(performanceId, states, plan, stateTtl);
                remaining -= plan.advancedCount();
                admittedCount += plan.advancedCount();
                rrCursor = plan.nextCursor();
            }

            publishPublicState(performanceId, states, shardCount, slotSizeMillis, refreshAfterMs, rrCursor, stateTtl);
            removeWaitingPerformanceIfNoPendingShard(states, performanceId);
            return QueueAdvanceResult.observed(
                    admittedCount,
                    backlog(states),
                    oldestPendingSlotAgeMillis(states, slotSizeMillis, nowMillis)
            );
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    private long backlog(final List<ShardQueueState> states) {
        return states.stream()
                .mapToLong(state -> Math.max(0L, state.tailSeq() - state.servingSeq()))
                .sum();
    }

    private long oldestPendingSlotAgeMillis(
            final List<ShardQueueState> states,
            final long slotSizeMillis,
            final long nowMillis
    ) {
        return states.stream()
                .filter(ShardQueueState::hasPendingSlot)
                .mapToLong(ShardQueueState::firstSlotId)
                .min()
                .stream()
                .map(slotId -> Math.max(0L, nowMillis - slotId * slotSizeMillis))
                .findFirst()
                .orElse(0L);
    }

    private List<ShardQueueState> readShardStates(
            final Long performanceId,
            final int shardCount,
            final Duration stateTtl
    ) {
        List<CompletableFuture<ShardQueueState>> futures = new ArrayList<>(shardCount);
        for (int shardId = 0; shardId < shardCount; shardId++) {
            final int id = shardId;
            futures.add(CompletableFuture.supplyAsync(() -> readShardState(performanceId, id, stateTtl)));
        }
        return futures.stream()
                .map(CompletableFuture::join)
                .collect(Collectors.toCollection(ArrayList::new));
    }

    private ShardQueueState readShardState(
            final Long performanceId,
            final int shardId,
            final Duration stateTtl
    ) {
        List<Object> result = evalScript(
                ADVANCE_QUEUE_STATE_SCRIPT,
                RScript.ReturnType.LIST,
                shardStateKeys(performanceId, shardId),
                "SNAPSHOT",
                ttlDuration(stateTtl).toMillis()
        );
        return toShardQueueState(shardId, result);
    }

    private ShardQueueState advanceShard(
            final Long performanceId,
            final int shardId,
            final int increment,
            final Duration stateTtl
    ) {
        List<Object> result = evalScript(
                ADVANCE_QUEUE_STATE_SCRIPT,
                RScript.ReturnType.LIST,
                shardStateKeys(performanceId, shardId),
                "ADVANCE",
                ttlDuration(stateTtl).toMillis(),
                increment
        );
        return toShardQueueState(shardId, result);
    }

    private List<Object> shardStateKeys(final Long performanceId, final int shardId) {
        return List.of(
                RedisKey.shardState(performanceId, shardId),
                RedisKey.shardPendingSlots(performanceId, shardId),
                RedisKey.shardSlotTail(performanceId, shardId),
                RedisKey.shardSequence(performanceId, shardId)
        );
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

    private ShardQueueState toShardQueueState(final int shardId, final List<Object> result) {
        return new ShardQueueState(
                shardId,
                asLong(result.get(0)),
                asLong(result.get(1)),
                asLong(result.get(2)),
                asLong(result.get(3)),
                asLong(result.get(4))
        );
    }

    private int readRoundRobinCursor(final Long performanceId, final int shardCount) {
        String value = publicStateMap(performanceId).get(FIELD_RR_CURSOR);
        return Math.floorMod(Math.toIntExact(parseLong(value, 0L)), shardCount);
    }

    private AdvancePlan planSlotAdvances(
            final List<ShardQueueState> states,
            final long slotId,
            final int capacity,
            final int initialCursor
    ) {
        int[] increments = new int[states.size()];
        int remaining = capacity;
        int cursor = initialCursor;
        boolean progressed;
        do {
            progressed = false;
            int passCursor = cursor;
            for (int offset = 0; offset < states.size() && remaining > 0; offset++) {
                int shardId = Math.floorMod(passCursor + offset, states.size());
                ShardQueueState state = states.get(shardId);
                if (state.canAdvance(slotId, increments[shardId])) {
                    increments[shardId]++;
                    remaining--;
                    cursor = Math.floorMod(shardId + 1, states.size());
                    progressed = true;
                }
            }
        } while (progressed && remaining > 0);

        return new AdvancePlan(increments, capacity - remaining, cursor);
    }

    private void applyAdvancePlan(
            final Long performanceId,
            final List<ShardQueueState> states,
            final AdvancePlan plan,
            final Duration stateTtl
    ) {
        int[] increments = plan.increments();
        List<CompletableFuture<ShardQueueState>> futures = new ArrayList<>();
        List<Integer> activeShardIds = new ArrayList<>();
        for (int shardId = 0; shardId < increments.length; shardId++) {
            if (increments[shardId] > 0) {
                final int id = shardId;
                final int increment = increments[shardId];
                activeShardIds.add(id);
                futures.add(CompletableFuture.supplyAsync(() -> advanceShard(performanceId, id, increment, stateTtl)));
            }
        }
        for (int i = 0; i < futures.size(); i++) {
            states.set(activeShardIds.get(i), futures.get(i).join());
        }
    }

    private long nextClosedSlotId(final List<ShardQueueState> states, final long lastClosedSlotId) {
        long minSlotId = Long.MAX_VALUE;
        for (ShardQueueState state : states) {
            long slotId = state.firstSlotId();
            if (slotId >= 0 && slotId <= lastClosedSlotId && state.hasPendingSlot() && slotId < minSlotId) {
                minSlotId = slotId;
            }
        }
        return minSlotId == Long.MAX_VALUE ? -1L : minSlotId;
    }

    private void publishPublicState(
            final Long performanceId,
            final List<ShardQueueState> states,
            final int shardCount,
            final long slotSizeMillis,
            final long refreshAfterMs,
            final int rrCursor,
            final Duration stateTtl
    ) {
        Map<Integer, Long> serving = new LinkedHashMap<>();
        Map<Integer, Long> tail = new LinkedHashMap<>();
        for (ShardQueueState state : states) {
            serving.put(state.shardId(), state.servingSeq());
            tail.put(state.shardId(), state.tailSeq());
        }

        Map<String, String> values = new LinkedHashMap<>();
        values.put(FIELD_STATUS, hasPending(states) ? STATUS_OPEN : STATUS_EMPTY);
        values.put(FIELD_SHARD_COUNT, String.valueOf(shardCount));
        values.put(FIELD_SLOT_SIZE_MILLIS, String.valueOf(slotSizeMillis));
        values.put(FIELD_SERVING, encodeShardMap(serving));
        values.put(FIELD_TAIL, encodeShardMap(tail));
        values.put(FIELD_ADMITTED_UNTIL_SEQ, String.valueOf(maxValue(serving)));
        values.put(FIELD_TAIL_SEQ, String.valueOf(maxValue(tail)));
        values.put(FIELD_REFRESH_AFTER_MS, String.valueOf(refreshAfterMs));
        values.put(FIELD_RR_CURSOR, String.valueOf(rrCursor));
        values.put("serverTimeMillis", String.valueOf(System.currentTimeMillis()));
        RMap<String, String> stateMap = publicStateMap(performanceId);
        stateMap.putAll(values);
        stateMap.expire(ttlDuration(stateTtl));
    }

    private long maxValue(final Map<Integer, Long> values) {
        return values.values()
                .stream()
                .mapToLong(Long::longValue)
                .max()
                .orElse(0L);
    }

    private boolean hasPending(final List<ShardQueueState> states) {
        return states.stream().anyMatch(ShardQueueState::hasPendingSlot);
    }

    private String encodeShardMap(final Map<Integer, Long> values) {
        return values.entrySet().stream()
                .map(entry -> entry.getKey() + ":" + entry.getValue())
                .collect(Collectors.joining(","));
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

    private void removeWaitingPerformanceIfNoPendingShard(
            final List<ShardQueueState> states,
            final Long performanceId
    ) {
        if (!hasPending(states)) {
            RSet<String> waitingPerformances = waitingPerformanceSet();
            String performanceKey = performanceKey(performanceId);
            waitingPerformances.remove(performanceKey);
            if (anyShardWaitingMarkerExists(performanceId, states)) {
                waitingPerformances.add(performanceKey);
            }
        }
    }

    private boolean anyShardWaitingMarkerExists(
            final Long performanceId,
            final List<ShardQueueState> states
    ) {
        List<CompletableFuture<Boolean>> futures = states.stream()
                .map(ShardQueueState::shardId)
                .map(shardId -> redissonClient
                        .getBucket(RedisKey.shardWaitingMarker(performanceId, shardId), StringCodec.INSTANCE)
                        .isExistsAsync()
                        .toCompletableFuture())
                .toList();
        return futures.stream()
                .map(CompletableFuture::join)
                .anyMatch(Boolean::booleanValue);
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

    private record ShardQueueState(
            int shardId,
            long servingSeq,
            long tailSeq,
            long activeCount,
            long firstSlotId,
            long firstSlotTail
    ) {

        private boolean canAdvance(final long slotId, final int plannedIncrement) {
            return firstSlotId == slotId && servingSeq + plannedIncrement < firstSlotTail;
        }

        private boolean hasPendingSlot() {
            return firstSlotId >= 0 && servingSeq < firstSlotTail;
        }
    }

    private record AdvancePlan(
            int[] increments,
            int advancedCount,
            int nextCursor
    ) {

        private boolean hasAdvances() {
            return advancedCount > 0;
        }
    }


}
