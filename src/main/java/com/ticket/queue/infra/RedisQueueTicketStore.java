package com.ticket.queue.infra;

import com.ticket.queue.domain.QueueEntryStatus;
import com.ticket.queue.domain.QueueSession;
import com.ticket.queue.domain.QueueSessionCreation;
import com.ticket.queue.domain.QueueTicket;
import com.ticket.queue.domain.QueueTicketStore;
import com.ticket.queue.domain.UuidSupplier;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.redisson.api.RAtomicLong;
import org.redisson.api.RBucket;
import org.redisson.api.RScript;
import org.redisson.api.RScoredSortedSet;
import org.redisson.api.RSet;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class RedisQueueTicketStore implements QueueTicketStore {

    private static final String STATE_WAITING = "WAITING";
    private static final String STATE_ACTIVE = "ACTIVE";
    private static final String ADMIT_WAITING_BATCH_SCRIPT = """
            local limit = tonumber(ARGV[1])
            local maxActiveUsers = tonumber(ARGV[2])
            local activeTtlMillis = tonumber(ARGV[3])
            local memberStatePrefix = ARGV[4]
            local time = redis.call('TIME')
            local nowMillis = tonumber(time[1]) * 1000 + math.floor(tonumber(time[2]) / 1000)
            local activeExpiresAt = nowMillis + activeTtlMillis
            local admitted = 0
            redis.call('ZREMRANGEBYSCORE', KEYS[2], 0, nowMillis)
            local activeCount = redis.call('ZCARD', KEYS[2])
            local available = maxActiveUsers - activeCount
            if available <= 0 then
              return 0
            end
            local batchLimit = math.min(limit, available)
            local candidates = redis.call('ZRANGE', KEYS[1], 0, batchLimit - 1)

            for _, queueSessionId in ipairs(candidates) do
              local stateKey = memberStatePrefix .. queueSessionId
              local state = redis.call('GET', stateKey)

              redis.call('ZREM', KEYS[1], queueSessionId)
              if state == 'WAITING' then
                redis.call('ZADD', KEYS[2], activeExpiresAt, queueSessionId)
                redis.call('PSETEX', stateKey, activeTtlMillis, 'ACTIVE')
                admitted = admitted + 1
              end
            end

            return admitted
            """;

    private final RedissonClient redissonClient;
    private final UuidSupplier uuidSupplier;

    @Override
    public void registerWaiting(
            final Long performanceId,
            final String queueSessionId,
            final Duration sessionTtl
    ) {
        validatePositive(performanceId, "performanceId");
        validateNotBlank(queueSessionId, "queueSessionId");

        RBucket<String> stateBucket = memberStateBucket(performanceId, queueSessionId);
        if (!stateBucket.setIfAbsent(STATE_WAITING, ttlDuration(sessionTtl))) {
            throw new IllegalStateException("queue waiting member already registered");
        }

        long sequence = sequence(performanceId).incrementAndGet();
        boolean added = waitingSet(performanceId).add(sequence, queueSessionId);
        if (added) {
            waitingPerformanceSet().add(performanceKey(performanceId));
            return;
        }

        stateBucket.delete();
        throw new IllegalStateException("failed to register queue waiting member");
    }

    @Override
    public QueueSessionCreation createSession(
            final Long performanceId,
            final Long memberId,
            final Duration sessionTtl
    ) {
        validatePositive(performanceId, "performanceId");
        validatePositive(memberId, "memberId");

        Duration sessionTtlDuration = ttlDuration(sessionTtl);
        RBucket<String> memberSessionBucket = memberSessionBucket(performanceId, memberId);
        Optional<QueueSession> existingSession = findMappedSession(memberSessionBucket);
        if (existingSession.isPresent()) {
            return new QueueSessionCreation(existingSession.get(), false);
        }

        String queueSessionId = uuidSupplier.get().toString();
        RBucket<String> sessionBucket = sessionBucket(queueSessionId);
        sessionBucket.set(String.valueOf(performanceId), sessionTtlDuration);

        if (memberSessionBucket.setIfAbsent(queueSessionId, sessionTtlDuration)) {
            return new QueueSessionCreation(new QueueSession(queueSessionId, performanceId), true);
        }

        sessionBucket.delete();
        return findMappedSession(memberSessionBucket)
                .map(session -> new QueueSessionCreation(session, false))
                .orElseGet(() -> createSession(performanceId, memberId, sessionTtl));
    }

    @Override
    public Optional<QueueSession> findSession(final String queueSessionId) {
        if (queueSessionId == null || queueSessionId.isBlank()) {
            return Optional.empty();
        }
        String value = sessionBucket(queueSessionId).get();
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        if (value.contains(":")) {
            sessionBucket(queueSessionId).delete();
            return Optional.empty();
        }
        return Optional.of(new QueueSession(queueSessionId, Long.parseLong(value)));
    }

    @Override
    public Optional<Long> findWaitingPosition(final Long performanceId, final String queueSessionId) {
        Integer rank = waitingSet(performanceId).rank(queueSessionId);
        if (rank == null) {
            return Optional.empty();
        }
        return Optional.of(rank.longValue() + 1L);
    }

    @Override
    public Optional<QueueTicket> findTicket(final Long performanceId, final String queueSessionId) {
        RBucket<String> stateBucket = memberStateBucket(performanceId, queueSessionId);
        RScoredSortedSet<String> activeSet = activeSet(performanceId);
        Double activeExpiresAt = activeSet.getScore(queueSessionId);
        if (activeExpiresAt != null) {
            long nowMillis = System.currentTimeMillis();
            String state = stateBucket.get();
            if (activeExpiresAt > nowMillis && STATE_ACTIVE.equals(state)) {
                return Optional.of(new QueueTicket(
                        performanceId,
                        queueSessionId,
                        QueueEntryStatus.ADMITTED,
                        Duration.ofMillis(Math.max(1L, activeExpiresAt.longValue() - nowMillis))
                ));
            }
            activeSet.remove(queueSessionId);
        }

        Integer rank = waitingSet(performanceId).rank(queueSessionId);
        if (rank != null) {
            return Optional.of(new QueueTicket(
                    performanceId,
                    queueSessionId,
                    QueueEntryStatus.WAITING
            ));
        }
        return Optional.empty();
    }

    @Override
    public Set<Long> findWaitingPerformanceIds() {
        return waitingPerformanceSet().readAll()
                .stream()
                .map(Long::valueOf)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    @Override
    public void admitWaitingBatch(
            final Long performanceId,
            final int limit,
            final int maxActiveUsers,
            final Duration activeTtl
    ) {
        validatePositive(performanceId, "performanceId");
        if (limit <= 0 || maxActiveUsers <= 0) {
            return;
        }

        Duration activeTtlDuration = ttlDuration(activeTtl);
        redissonClient.getScript(StringCodec.INSTANCE).eval(
                RScript.Mode.READ_WRITE,
                ADMIT_WAITING_BATCH_SCRIPT,
                RScript.ReturnType.LONG,
                List.of(QueueRedisKey.waiting(performanceId), QueueRedisKey.active(performanceId)),
                limit,
                maxActiveUsers,
                activeTtlDuration.toMillis(),
                QueueRedisKey.memberStatePrefix(performanceId)
        );
        removeWaitingPerformanceIfEmpty(performanceId);
    }

    private RScoredSortedSet<String> waitingSet(final Long performanceId) {
        return redissonClient.getScoredSortedSet(QueueRedisKey.waiting(performanceId), StringCodec.INSTANCE);
    }

    private RScoredSortedSet<String> activeSet(final Long performanceId) {
        return redissonClient.getScoredSortedSet(QueueRedisKey.active(performanceId), StringCodec.INSTANCE);
    }

    private RAtomicLong sequence(final Long performanceId) {
        return redissonClient.getAtomicLong(QueueRedisKey.sequence(performanceId));
    }

    private RBucket<String> memberStateBucket(final Long performanceId, final String queueSessionId) {
        return redissonClient.getBucket(QueueRedisKey.memberState(performanceId, queueSessionId), StringCodec.INSTANCE);
    }

    private RBucket<String> sessionBucket(final String queueSessionId) {
        return redissonClient.getBucket(QueueRedisKey.session(queueSessionId), StringCodec.INSTANCE);
    }

    private RBucket<String> memberSessionBucket(final Long performanceId, final Long memberId) {
        return redissonClient.getBucket(QueueRedisKey.memberSession(performanceId, memberId), StringCodec.INSTANCE);
    }

    private Optional<QueueSession> findMappedSession(final RBucket<String> memberSessionBucket) {
        String existingQueueSessionId = memberSessionBucket.get();
        if (existingQueueSessionId == null || existingQueueSessionId.isBlank()) {
            return Optional.empty();
        }

        Optional<QueueSession> session = findSession(existingQueueSessionId);
        if (session.isPresent()) {
            return session;
        }

        memberSessionBucket.delete();
        return Optional.empty();
    }

    private RSet<String> waitingPerformanceSet() {
        return redissonClient.getSet(QueueRedisKey.waitingPerformances(), StringCodec.INSTANCE);
    }

    private void removeWaitingPerformanceIfEmpty(final Long performanceId) {
        if (waitingSet(performanceId).isEmpty()) {
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
