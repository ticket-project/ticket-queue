package com.ticket.queue.infra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ticket.queue.domain.EnterResult;
import com.ticket.queue.domain.JoinResult;
import com.ticket.queue.domain.QueueShardSlot;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.redisson.api.RLock;
import org.redisson.api.RMap;
import org.redisson.api.RScript;
import org.redisson.api.RSet;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;

class RedisAdmissionStateStoreTest {

    @Test
    void join_script_keeps_global_public_state_out_of_hot_path() {
        String script = RedisScriptLoader.load("redis/join_queue.lua");

        assertThat(script)
                .doesNotContain("'admittedUntilSeq'")
                .doesNotContain("'tailSeq'")
                .doesNotContain("'refreshAfterMs'")
                .doesNotContain("'status'")
                .doesNotContain("'serverTimeMillis'")
                .doesNotContain("HSETNX")
                .doesNotContain("XADD")
                .doesNotContain("publicState");
        assertThat(script).contains("local ticket_value =");
        assertThat(script).contains("redis.call('PEXPIRE', KEYS[1], ttl_millis)");
        assertThat(script).contains("redis.call('SET', KEYS[3], ticket_value, 'PX', ttl_millis)");
        assertThat(script).contains("local marker_ttl_millis = tonumber(ARGV[6])");
        assertThat(script).contains("redis.call('SET', KEYS[6], '1', 'PX', marker_ttl_millis)");
    }

    @Test
    void advance_script_reads_tail_sequence_from_shard_counter() {
        String script = RedisScriptLoader.load("redis/advance_queue_state.lua");

        assertThat(script).contains("local tail_seq = tonumber(redis.call('GET', KEYS[4]) or '0')");
        assertThat(script).contains("'tailSeq'");
        assertThat(script).contains("redis.call('PEXPIRE', KEYS[1], ttl_millis)");
        assertThat(script).contains("redis.call('PEXPIRE', KEYS[4], ttl_millis)");
        assertThat(script).contains("first_pending_slot(serving_seq)");
    }

    @Test
    void admission_session_script_checks_existing_admission_before_capacity() {
        String script = RedisScriptLoader.load("redis/admit_queue_session.lua");

        assertThat(script).contains("local existing_token =");
        assertThat(script).contains("local active_count = redis.call('ZCARD', KEYS[2])");
        assertThat(script.indexOf("local existing_token =")).isLessThan(script.indexOf("local active_count ="));
        assertThat(script).contains("redis.call('PEXPIRE', KEYS[2], session_ttl_millis)");
    }

    @Test
    void sharded_queue_keys_include_event_and_shard_in_hash_tag() {
        assertThat(RedisKey.shardSequence(1L, 17)).isEqualTo("q:{1:17}:seq");
        assertThat(RedisKey.shardState(1L, 17)).isEqualTo("q:{1:17}:state");
        assertThat(RedisKey.shardUser(1L, 17, "user-hash")).isEqualTo("q:{1:17}:user:user-hash");
        assertThat(RedisKey.shardQueue(1L, 17, "queue-1")).isEqualTo("q:{1:17}:queue:queue-1");
        assertThat(RedisKey.shardEntered(1L, 17, "queue-1")).isEqualTo("q:{1:17}:entered:queue-1");
        assertThat(RedisKey.shardSessions(1L, 17)).isEqualTo("q:{1:17}:sessions");
        assertThat(RedisKey.performanceSessions(1L)).isEqualTo("q:{1}:sessions");
        assertThat(RedisKey.performanceEntered(1L, "queue-1")).isEqualTo("q:{1}:entered:queue-1");
        assertThat(RedisKey.shardSlotTail(1L, 17)).isEqualTo("q:{1:17}:slot-tail");
        assertThat(RedisKey.shardPendingSlots(1L, 17)).isEqualTo("q:{1:17}:pending-slots");
        assertThat(RedisKey.shardWaitingMarker(1L, 17)).isEqualTo("q:{1:17}:waiting-marker");
        assertThat(RedisKey.publicState(1L)).isEqualTo("q:{1}:state");
    }

    @Test
    @SuppressWarnings("unchecked")
    void joinQueue_runs_shard_local_lua_script_and_registers_performance_for_scheduler() {
        RedissonClient redissonClient = mock(RedissonClient.class);
        RScript script = mock(RScript.class);
        RSet<String> waitingPerformanceSet = mock(RSet.class);
        RedisAdmissionStateStore store = new RedisAdmissionStateStore(redissonClient);
        ArgumentCaptor<List<Object>> keysCaptor = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<Object[]> argsCaptor = ArgumentCaptor.forClass(Object[].class);

        when(redissonClient.getScript(StringCodec.INSTANCE)).thenReturn(script);
        when(redissonClient.<String>getSet(RedisKey.waitingPerformances(), StringCodec.INSTANCE))
                .thenReturn(waitingPerformanceSet);
        when(script.scriptLoad(anyString())).thenReturn("join-sha");
        when(script.evalSha(
                eq(RScript.Mode.READ_WRITE),
                eq("join-sha"),
                eq(RScript.ReturnType.LIST),
                any(List.class),
                any(Object[].class)
        )).thenReturn(List.of("queue-1", 42L, 24_691L, 1_234_550L, 1L, 1L));

        JoinResult actual = store.joinQueue(
                1L,
                "user-hash",
                "queue-1",
                new QueueShardSlot(17, 24_691L, 1_234_550L),
                Duration.ofHours(24)
        );

        assertThat(actual.queueId()).isEqualTo("queue-1");
        assertThat(actual.shardId()).isEqualTo(17);
        assertThat(actual.localSeq()).isEqualTo(42L);
        assertThat(actual.slotId()).isEqualTo(24_691L);
        assertThat(actual.slotStartMillis()).isEqualTo(1_234_550L);
        assertThat(actual.created()).isTrue();
        verify(script).evalSha(
                eq(RScript.Mode.READ_WRITE),
                eq("join-sha"),
                eq(RScript.ReturnType.LIST),
                keysCaptor.capture(),
                argsCaptor.capture()
        );
        verify(script, never()).eval(
                eq(RScript.Mode.READ_WRITE),
                anyString(),
                eq(RScript.ReturnType.LIST),
                any(List.class),
                any(Object[].class)
        );
        assertThat(keysCaptor.getValue())
                .containsExactly(
                        RedisKey.shardSequence(1L, 17),
                        RedisKey.shardUser(1L, 17, "user-hash"),
                        RedisKey.shardQueue(1L, 17, "queue-1"),
                        RedisKey.shardSlotTail(1L, 17),
                        RedisKey.shardPendingSlots(1L, 17),
                        RedisKey.shardWaitingMarker(1L, 17)
                );
        assertThat(argsCaptor.getValue())
                .containsExactly("queue-1", "user-hash", 86_400_000L, 24_691L, 1_234_550L, 10_000L);
        verify(waitingPerformanceSet).add("1");
    }

    @Test
    @SuppressWarnings("unchecked")
    void joinQueue_registers_scheduler_work_for_each_new_queue_entry() {
        RedissonClient redissonClient = mock(RedissonClient.class);
        RScript script = mock(RScript.class);
        RSet<String> waitingPerformanceSet = mock(RSet.class);
        RedisAdmissionStateStore store = new RedisAdmissionStateStore(redissonClient);

        when(redissonClient.getScript(StringCodec.INSTANCE)).thenReturn(script);
        when(redissonClient.<String>getSet(RedisKey.waitingPerformances(), StringCodec.INSTANCE))
                .thenReturn(waitingPerformanceSet);
        when(script.scriptLoad(anyString())).thenReturn("join-sha");
        when(script.evalSha(
                eq(RScript.Mode.READ_WRITE),
                eq("join-sha"),
                eq(RScript.ReturnType.LIST),
                any(List.class),
                any(Object[].class)
        )).thenReturn(List.of("queue-1", 42L, 24_691L, 1_234_550L, 1L, 1L));

        JoinResult actual = store.joinQueue(
                1L,
                "user-hash",
                "queue-1",
                new QueueShardSlot(17, 24_691L, 1_234_550L),
                Duration.ofHours(24)
        );

        assertThat(actual.created()).isTrue();
        verify(waitingPerformanceSet).add("1");
    }

    @Test
    @SuppressWarnings("unchecked")
    void readPublicState_returns_shard_serving_and_tail_maps() {
        RedissonClient redissonClient = mock(RedissonClient.class);
        RMap<String, String> stateMap = mock(RMap.class);
        RedisAdmissionStateStore store = new RedisAdmissionStateStore(redissonClient);

        when(redissonClient.<String, String>getMap(RedisKey.publicState(1L), StringCodec.INSTANCE))
                .thenReturn(stateMap);
        when(stateMap.readAllMap())
                .thenReturn(Map.of(
                        "status", "OPEN",
                        "shardCount", "128",
                        "slotSizeMillis", "50",
                        "serving", "0:100,1:90",
                        "tail", "0:1000,1:900",
                        "refreshAfterMs", "5000"
                ));

        assertThat(store.readPublicState(1L, 1_000L))
                .satisfies(state -> {
                    assertThat(state.performanceId()).isEqualTo(1L);
                    assertThat(state.status()).isEqualTo("OPEN");
                    assertThat(state.shardCount()).isEqualTo(128);
                    assertThat(state.slotSizeMillis()).isEqualTo(50L);
                    assertThat(state.serving()).containsEntry(0, 100L);
                    assertThat(state.tail()).containsEntry(0, 1_000L);
                    assertThat(state.refreshAfterMs()).isEqualTo(5_000L);
                    assertThat(state.serverTimeMillis()).isPositive();
                });
    }

    @Test
    @SuppressWarnings("unchecked")
    void enterQueue_uses_shard_local_keys_and_local_sequence() {
        RedissonClient redissonClient = mock(RedissonClient.class);
        RScript script = mock(RScript.class);
        RedisAdmissionStateStore store = new RedisAdmissionStateStore(redissonClient);
        ArgumentCaptor<List<Object>> keysCaptor = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<Object[]> argsCaptor = ArgumentCaptor.forClass(Object[].class);

        when(redissonClient.getScript(StringCodec.INSTANCE)).thenReturn(script);
        when(script.scriptLoad(anyString())).thenReturn("enter-sha");
        when(script.evalSha(
                eq(RScript.Mode.READ_WRITE),
                anyString(),
                eq(RScript.ReturnType.LIST),
                any(List.class),
                any(Object[].class)
        )).thenReturn(
                List.of(0L, "", 0L),
                List.of(1L),
                List.of(1L, "admission-token", 1_717_000_900_000L)
        );

        EnterResult actual = store.enterQueue(
                1L,
                "queue-1",
                17,
                100L,
                "admission-token",
                Duration.ofMinutes(15),
                5_000
        );

        assertThat(actual.status()).isEqualTo(EnterResult.Status.ADMITTED);
        verify(script, times(3)).evalSha(
                eq(RScript.Mode.READ_WRITE),
                anyString(),
                eq(RScript.ReturnType.LIST),
                keysCaptor.capture(),
                argsCaptor.capture()
        );
        assertThat(keysCaptor.getAllValues().get(0))
                .containsExactly(
                        RedisKey.performanceEntered(1L, "queue-1"),
                        RedisKey.performanceSessions(1L)
                );
        assertThat(keysCaptor.getAllValues().get(1))
                .containsExactly(
                        RedisKey.shardState(1L, 17),
                        RedisKey.shardQueue(1L, 17, "queue-1")
                );
        assertThat(keysCaptor.getAllValues().get(2))
                .containsExactly(
                        RedisKey.performanceEntered(1L, "queue-1"),
                        RedisKey.performanceSessions(1L)
                );
        assertThat(argsCaptor.getAllValues().get(0))
                .containsExactly("admission-token", 900_000L, 5_000, 0L, "queue-1");
        assertThat(argsCaptor.getAllValues().get(1))
                .containsExactly(100L);
        assertThat(argsCaptor.getAllValues().get(2))
                .containsExactly("admission-token", 900_000L, 5_000, 1L, "queue-1");
    }

    @Test
    @SuppressWarnings("unchecked")
    void advancePublicState_uses_event_lock_and_updates_projection() throws InterruptedException {
        RedissonClient redissonClient = mock(RedissonClient.class);
        RScript script = mock(RScript.class);
        RLock lock = mock(RLock.class);
        RMap<String, String> stateMap = mock(RMap.class);
        RedisAdmissionStateStore store = new RedisAdmissionStateStore(redissonClient);

        when(redissonClient.getLock(RedisKey.advanceLock(1L))).thenReturn(lock);
        when(lock.tryLock(0L, 5_000L, TimeUnit.MILLISECONDS)).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);
        when(redissonClient.getScript(StringCodec.INSTANCE)).thenReturn(script);
        when(redissonClient.<String, String>getMap(RedisKey.publicState(1L), StringCodec.INSTANCE))
                .thenReturn(stateMap);
        when(script.scriptLoad(anyString())).thenReturn("advance-sha");
        when(script.evalSha(
                eq(RScript.Mode.READ_WRITE),
                eq("advance-sha"),
                any(RScript.ReturnType.class),
                any(List.class),
                any(Object[].class)
        )).thenReturn(
                List.of(0L, 2L, 0L, 0L, 2L),
                0L,
                List.of(1L, 2L, 0L, 0L, 2L)
        );

        store.advancePublicState(1L, 10, 5_000, 1, 50L, 200L, Duration.ofHours(24), 5_000L);

        verify(stateMap).putAll(any(Map.class));
        verify(stateMap).expire(Duration.ofHours(24));
        verify(lock).unlock();
    }

    @Test
    @SuppressWarnings("unchecked")
    void advancePublicState_batches_multiple_admissions_for_the_same_shard() throws InterruptedException {
        RedissonClient redissonClient = mock(RedissonClient.class);
        RScript script = mock(RScript.class);
        RLock lock = mock(RLock.class);
        RMap<String, String> stateMap = mock(RMap.class);
        RedisAdmissionStateStore store = new RedisAdmissionStateStore(redissonClient);
        ArgumentCaptor<Object[]> argsCaptor = ArgumentCaptor.forClass(Object[].class);

        when(redissonClient.getLock(RedisKey.advanceLock(1L))).thenReturn(lock);
        when(lock.tryLock(0L, 5_000L, TimeUnit.MILLISECONDS)).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);
        when(redissonClient.getScript(StringCodec.INSTANCE)).thenReturn(script);
        when(redissonClient.<String, String>getMap(RedisKey.publicState(1L), StringCodec.INSTANCE))
                .thenReturn(stateMap);
        when(stateMap.get("rrCursor")).thenReturn("0");
        when(script.scriptLoad(anyString())).thenReturn("advance-sha");
        when(script.evalSha(
                eq(RScript.Mode.READ_WRITE),
                eq("advance-sha"),
                any(RScript.ReturnType.class),
                any(List.class),
                any(Object[].class)
        )).thenReturn(
                List.of(0L, 3L, 0L, 0L, 3L),
                0L,
                List.of(3L, 4L, 0L, 1L, 4L)
        );

        store.advancePublicState(1L, 3, 10, 1, 50L, 200L, Duration.ofHours(24), 5_000L);

        verify(script, times(3)).evalSha(
                eq(RScript.Mode.READ_WRITE),
                eq("advance-sha"),
                any(RScript.ReturnType.class),
                any(List.class),
                argsCaptor.capture()
        );
        assertThat(argsCaptor.getAllValues().get(0)).containsExactly("SNAPSHOT", 86_400_000L);
        assertThat(argsCaptor.getAllValues().get(1)).isEmpty();
        assertThat(argsCaptor.getAllValues().get(2)).containsExactly("ADVANCE", 86_400_000L, 3);
        verify(stateMap).putAll(any(Map.class));
        verify(lock).unlock();
    }
}
