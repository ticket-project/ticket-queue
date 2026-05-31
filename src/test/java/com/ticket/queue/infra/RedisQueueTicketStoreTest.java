package com.ticket.queue.infra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ticket.queue.domain.QueueEntryStatus;
import com.ticket.queue.domain.QueueSessionCreation;
import com.ticket.queue.domain.QueueTicket;
import com.ticket.queue.domain.UuidSupplier;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.redisson.api.RAtomicLong;
import org.redisson.api.RBucket;
import org.redisson.api.RScript;
import org.redisson.api.RScoredSortedSet;
import org.redisson.api.RSet;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;

class RedisQueueTicketStoreTest {

    @Test
    @SuppressWarnings("unchecked")
    void registerWaiting은_기존_ticket을_조회하지_않고_waiting_등록만_수행한다() {
        RedissonClient redissonClient = mock(RedissonClient.class);
        UuidSupplier uuidSupplier = mock(UuidSupplier.class);
        RBucket<String> stateBucket = mock(RBucket.class);
        RAtomicLong sequence = mock(RAtomicLong.class);
        RScoredSortedSet<String> waitingSet = mock(RScoredSortedSet.class);
        RSet<String> waitingPerformanceSet = mock(RSet.class);
        RedisQueueTicketStore store = new RedisQueueTicketStore(redissonClient, uuidSupplier);

        when(redissonClient.<String>getBucket(QueueRedisKey.memberState(1L, "session-1"), StringCodec.INSTANCE))
                .thenReturn(stateBucket);
        when(redissonClient.getAtomicLong(QueueRedisKey.sequence(1L))).thenReturn(sequence);
        when(redissonClient.<String>getScoredSortedSet(QueueRedisKey.waiting(1L), StringCodec.INSTANCE))
                .thenReturn(waitingSet);
        when(redissonClient.<String>getSet(QueueRedisKey.waitingPerformances(), StringCodec.INSTANCE))
                .thenReturn(waitingPerformanceSet);
        when(stateBucket.setIfAbsent(eq("WAITING"), any(Duration.class))).thenReturn(true);
        when(sequence.incrementAndGet()).thenReturn(42L);
        when(waitingSet.add(42L, "session-1")).thenReturn(true);

        store.registerWaiting(1L, "session-1", Duration.ofHours(1));

        verify(stateBucket).setIfAbsent(eq("WAITING"), any(Duration.class));
        verify(sequence).incrementAndGet();
        verify(waitingSet).add(42L, "session-1");
        verify(waitingPerformanceSet).add("1");
        verify(waitingSet, never()).rank("session-1");
    }

    @Test
    void queue_key는_performanceId_hash_tag를_사용해_회차별_키를_같은_cluster_slot에_둔다() {
        assertThat(QueueRedisKey.waiting(1L)).isEqualTo("queue:{1}:waiting");
        assertThat(QueueRedisKey.active(1L)).isEqualTo("queue:{1}:active");
        assertThat(QueueRedisKey.sequence(1L)).isEqualTo("queue:{1}:sequence");
        assertThat(QueueRedisKey.memberState(1L, "session-1")).isEqualTo("queue:{1}:member:session-1");
        assertThat(QueueRedisKey.memberSession(1L, 10L)).isEqualTo("queue:{1}:member-session:10");
    }

    @Test
    @SuppressWarnings("unchecked")
    void createSession은_회원별_기존_session이_없으면_새_session을_저장한다() {
        RedissonClient redissonClient = mock(RedissonClient.class);
        UuidSupplier uuidSupplier = mock(UuidSupplier.class);
        RBucket<String> memberSessionBucket = mock(RBucket.class);
        RBucket<String> sessionBucket = mock(RBucket.class);
        RedisQueueTicketStore store = new RedisQueueTicketStore(redissonClient, uuidSupplier);
        String queueSessionId = "00000000-0000-0000-0000-000000000001";

        when(redissonClient.<String>getBucket(QueueRedisKey.memberSession(1L, 10L), StringCodec.INSTANCE))
                .thenReturn(memberSessionBucket);
        when(redissonClient.<String>getBucket(QueueRedisKey.session(queueSessionId), StringCodec.INSTANCE))
                .thenReturn(sessionBucket);
        when(memberSessionBucket.get()).thenReturn(null);
        when(uuidSupplier.get()).thenReturn(UUID.fromString(queueSessionId));
        when(memberSessionBucket.setIfAbsent(eq(queueSessionId), any(Duration.class))).thenReturn(true);

        QueueSessionCreation actual = store.createSession(1L, 10L, Duration.ofHours(1));

        assertThat(actual.created()).isTrue();
        assertThat(actual.session().queueSessionId()).isEqualTo(queueSessionId);
        assertThat(actual.session().performanceId()).isEqualTo(1L);
        verify(sessionBucket).set(eq("1"), any(Duration.class));
        verify(memberSessionBucket).setIfAbsent(eq(queueSessionId), any(Duration.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void createSession은_회원별_기존_session이_있으면_기존_session을_재사용한다() {
        RedissonClient redissonClient = mock(RedissonClient.class);
        UuidSupplier uuidSupplier = mock(UuidSupplier.class);
        RBucket<String> memberSessionBucket = mock(RBucket.class);
        RBucket<String> sessionBucket = mock(RBucket.class);
        RedisQueueTicketStore store = new RedisQueueTicketStore(redissonClient, uuidSupplier);

        when(redissonClient.<String>getBucket(QueueRedisKey.memberSession(1L, 10L), StringCodec.INSTANCE))
                .thenReturn(memberSessionBucket);
        when(redissonClient.<String>getBucket(QueueRedisKey.session("session-1"), StringCodec.INSTANCE))
                .thenReturn(sessionBucket);
        when(memberSessionBucket.get()).thenReturn("session-1");
        when(sessionBucket.get()).thenReturn("1");

        QueueSessionCreation actual = store.createSession(1L, 10L, Duration.ofHours(1));

        assertThat(actual.created()).isFalse();
        assertThat(actual.session().queueSessionId()).isEqualTo("session-1");
        assertThat(actual.session().performanceId()).isEqualTo(1L);
        verify(uuidSupplier, never()).get();
    }

    @Test
    @SuppressWarnings("unchecked")
    void admitWaitingBatch는_Lua_script로_waiting_active_state를_원자적으로_변경한다() {
        RedissonClient redissonClient = mock(RedissonClient.class);
        UuidSupplier uuidSupplier = mock(UuidSupplier.class);
        RScript script = mock(RScript.class);
        RScoredSortedSet<String> waitingSet = mock(RScoredSortedSet.class);
        RedisQueueTicketStore store = new RedisQueueTicketStore(redissonClient, uuidSupplier);
        ArgumentCaptor<List<Object>> keysCaptor = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<Object[]> argsCaptor = ArgumentCaptor.forClass(Object[].class);

        when(redissonClient.getScript(StringCodec.INSTANCE)).thenReturn(script);
        when(redissonClient.<String>getScoredSortedSet(QueueRedisKey.waiting(1L), StringCodec.INSTANCE))
                .thenReturn(waitingSet);
        when(waitingSet.isEmpty()).thenReturn(false);
        when(script.eval(
                eq(RScript.Mode.READ_WRITE),
                anyString(),
                eq(RScript.ReturnType.LONG),
                any(List.class),
                any()
        )).thenReturn(2L);

        store.admitWaitingBatch(
                1L,
                2,
                300,
                Duration.ofMinutes(5)
        );

        verify(script).eval(
                eq(RScript.Mode.READ_WRITE),
                anyString(),
                eq(RScript.ReturnType.LONG),
                keysCaptor.capture(),
                argsCaptor.capture()
        );
        assertThat(keysCaptor.getValue())
                .containsExactly(QueueRedisKey.waiting(1L), QueueRedisKey.active(1L));
        assertThat(argsCaptor.getValue())
                .containsExactly(2, 300, 300_000L, "queue:{1}:member:");
    }

    @Test
    @SuppressWarnings("unchecked")
    void findTicket은_active_zset_score로_active_ttl을_계산한다() {
        RedissonClient redissonClient = mock(RedissonClient.class);
        UuidSupplier uuidSupplier = mock(UuidSupplier.class);
        RScoredSortedSet<String> activeSet = mock(RScoredSortedSet.class);
        RBucket<String> stateBucket = mock(RBucket.class);
        RedisQueueTicketStore store = new RedisQueueTicketStore(redissonClient, uuidSupplier);

        when(redissonClient.<String>getScoredSortedSet(QueueRedisKey.active(1L), StringCodec.INSTANCE))
                .thenReturn(activeSet);
        when(redissonClient.<String>getBucket(QueueRedisKey.memberState(1L, "session-1"), StringCodec.INSTANCE))
                .thenReturn(stateBucket);
        when(activeSet.getScore("session-1")).thenReturn((double) System.currentTimeMillis() + 300_000D);
        when(stateBucket.get()).thenReturn("ACTIVE");

        Optional<QueueTicket> actual = store.findTicket(1L, "session-1");

        assertThat(actual).isPresent();
        assertThat(actual.get().status()).isEqualTo(QueueEntryStatus.ADMITTED);
        assertThat(actual.get().activeTtl()).isNotNull();
        assertThat(actual.get().activeTtl()).isPositive();
    }
}
