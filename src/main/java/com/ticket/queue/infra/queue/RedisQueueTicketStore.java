package com.ticket.queue.infra.queue;

import com.ticket.queue.domain.model.QueueEntryStatus;
import com.ticket.queue.domain.model.QueueRedisKey;
import com.ticket.queue.domain.model.QueueSession;
import com.ticket.queue.domain.model.QueueTicket;
import com.ticket.queue.domain.port.QueueTicketStore;
import com.ticket.queue.domain.port.UuidSupplier;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.redisson.api.RAtomicLong;
import org.redisson.api.RBucket;
import org.redisson.api.RScoredSortedSet;
import org.redisson.api.RSet;
import org.redisson.api.RSetCache;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class RedisQueueTicketStore implements QueueTicketStore {

    private static final String STATE_WAITING = "WAITING";
    private static final String STATE_ACTIVE = "ACTIVE";
    private static final String SESSION_DELIMITER = ":";
    private static final int REGISTER_RETRY_LIMIT = 3;

    private final RedissonClient redissonClient;
    private final UuidSupplier uuidSupplier;

    @Override
    public long countActive(final Long performanceId) {
        return activeSet(performanceId).size();
    }

    @Override
    public long countWaiting(final Long performanceId) {
        return waitingSet(performanceId).size();
    }

    @Override
    public QueueTicket registerWaiting(
            final Long performanceId,
            final Long memberId,
            final Duration entryRetention
    ) {
        validatePositive(performanceId, "performanceId");
        validatePositive(memberId, "memberId");

        Optional<QueueTicket> current = findTicket(performanceId, memberId);
        if (current.isPresent()) {
            return current.get();
        }

        String memberKey = memberKey(memberId);
        RBucket<String> stateBucket = memberStateBucket(performanceId, memberId);
        for (int retry = 0; retry < REGISTER_RETRY_LIMIT; retry++) {
            if (stateBucket.trySet(STATE_WAITING, ttlMillis(entryRetention), TimeUnit.MILLISECONDS)) {
                long sequence = sequence(performanceId).incrementAndGet();
                boolean added = waitingSet(performanceId).add(sequence, memberKey);
                if (added) {
                    waitingPerformanceSet().add(memberKey(performanceId));
                    return new QueueTicket(performanceId, memberId, QueueEntryStatus.WAITING, sequence);
                }
                stateBucket.delete();
            }

            current = findTicket(performanceId, memberId);
            if (current.isPresent()) {
                return current.get();
            }
        }

        throw new IllegalStateException("failed to register queue waiting member");
    }
    @Override
    public QueueSession createSession(
            final Long performanceId,
            final Long memberId,
            final Duration sessionTtl
    ) {
        String queueSessionId = uuidSupplier.get().toString();
        String value = performanceId + SESSION_DELIMITER + memberId;
        sessionBucket(queueSessionId).set(value, ttlMillis(sessionTtl), TimeUnit.MILLISECONDS);
        return new QueueSession(queueSessionId, performanceId, memberId);
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
        String[] parts = value.split(SESSION_DELIMITER);
        if (parts.length != 2) {
            sessionBucket(queueSessionId).delete();
            return Optional.empty();
        }
        return Optional.of(new QueueSession(
                queueSessionId,
                Long.parseLong(parts[0]),
                Long.parseLong(parts[1])
        ));
    }

    @Override
    public void deleteSession(final String queueSessionId) {
        if (queueSessionId != null && !queueSessionId.isBlank()) {
            sessionBucket(queueSessionId).delete();
        }
    }

    @Override
    public Optional<Long> findWaitingPosition(final Long performanceId, final Long memberId) {
        Integer rank = waitingSet(performanceId).rank(memberKey(memberId));
        if (rank == null) {
            return Optional.empty();
        }
        return Optional.of(rank.longValue() + 1L);
    }

    @Override
    public Optional<QueueTicket> findTicket(final Long performanceId, final Long memberId) {
        String memberKey = memberKey(memberId);
        if (activeSet(performanceId).contains(memberKey)) {
            return Optional.of(new QueueTicket(performanceId, memberId, QueueEntryStatus.ADMITTED, null));
        }

        Integer rank = waitingSet(performanceId).rank(memberKey);
        if (rank != null) {
            return Optional.of(new QueueTicket(
                    performanceId,
                    memberId,
                    QueueEntryStatus.WAITING,
                    rank.longValue() + 1L
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
    public Optional<QueueTicket> admitNextWaiting(
            final Long performanceId,
            final Duration activeTtl,
            final Duration entryRetention,
            final LocalDateTime now
    ) {
        RScoredSortedSet<String> waitingSet = waitingSet(performanceId);
        Collection<String> candidates = waitingSet.valueRange(0, 0);
        if (candidates.isEmpty()) {
            removeWaitingPerformanceIfEmpty(performanceId);
            return Optional.empty();
        }

        String memberKey = candidates.iterator().next();
        if (!waitingSet.remove(memberKey)) {
            return Optional.empty();
        }

        Long memberId = Long.valueOf(memberKey);
        RBucket<String> stateBucket = memberStateBucket(performanceId, memberId);
        if (!STATE_WAITING.equals(stateBucket.get())) {
            removeWaitingPerformanceIfEmpty(performanceId);
            return Optional.empty();
        }

        activeSet(performanceId).add(memberKey, ttlMillis(activeTtl), TimeUnit.MILLISECONDS);
        stateBucket.set(STATE_ACTIVE, ttlMillis(entryRetention), TimeUnit.MILLISECONDS);
        removeWaitingPerformanceIfEmpty(performanceId);
        return Optional.of(new QueueTicket(performanceId, memberId, QueueEntryStatus.ADMITTED, null));
    }

    @Override
    public void leaveWaiting(final Long performanceId, final Long memberId) {
        waitingSet(performanceId).remove(memberKey(memberId));
        RBucket<String> stateBucket = memberStateBucket(performanceId, memberId);
        if (STATE_WAITING.equals(stateBucket.get())) {
            stateBucket.delete();
        }
        removeWaitingPerformanceIfEmpty(performanceId);
    }

    @Override
    public void leaveAdmitted(final Long performanceId, final Long memberId) {
        activeSet(performanceId).remove(memberKey(memberId));
        RBucket<String> stateBucket = memberStateBucket(performanceId, memberId);
        if (STATE_ACTIVE.equals(stateBucket.get())) {
            stateBucket.delete();
        }
    }

    private RScoredSortedSet<String> waitingSet(final Long performanceId) {
        return redissonClient.getScoredSortedSet(QueueRedisKey.waiting(performanceId), StringCodec.INSTANCE);
    }

    private RSetCache<String> activeSet(final Long performanceId) {
        return redissonClient.getSetCache(QueueRedisKey.active(performanceId), StringCodec.INSTANCE);
    }

    private RAtomicLong sequence(final Long performanceId) {
        return redissonClient.getAtomicLong(QueueRedisKey.sequence(performanceId));
    }
    private RBucket<String> memberStateBucket(final Long performanceId, final Long memberId) {
        return redissonClient.getBucket(QueueRedisKey.memberState(performanceId, memberId), StringCodec.INSTANCE);
    }

    private RBucket<String> sessionBucket(final String queueSessionId) {
        return redissonClient.getBucket(QueueRedisKey.session(queueSessionId), StringCodec.INSTANCE);
    }

    private RSet<String> waitingPerformanceSet() {
        return redissonClient.getSet(QueueRedisKey.waitingPerformances(), StringCodec.INSTANCE);
    }

    private void removeWaitingPerformanceIfEmpty(final Long performanceId) {
        if (waitingSet(performanceId).isEmpty()) {
            waitingPerformanceSet().remove(memberKey(performanceId));
        }
    }

    private String memberKey(final Long value) {
        return String.valueOf(value);
    }

    private long ttlMillis(final Duration duration) {
        return Math.max(1L, duration.toMillis());
    }

    private void validatePositive(final Long value, final String name) {
        if (value == null || value <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }
}