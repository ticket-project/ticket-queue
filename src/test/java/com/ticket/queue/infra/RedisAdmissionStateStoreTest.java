package com.ticket.queue.infra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ticket.queue.domain.JoinResult;
import com.ticket.queue.domain.EnterResult;
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
    void public_queue_keys_use_q_prefix_and_performance_hash_tag() {
        assertThat(RedisKey.publicSequence(1L)).isEqualTo("q:{1}:seq");
        assertThat(RedisKey.publicState(1L)).isEqualTo("q:{1}:state");
        assertThat(RedisKey.publicUser(1L, "user-hash")).isEqualTo("q:{1}:user:user-hash");
        assertThat(RedisKey.publicQueue(1L, "queue-1")).isEqualTo("q:{1}:queue:queue-1");
        assertThat(RedisKey.publicEntered(1L, "queue-1")).isEqualTo("q:{1}:entered:queue-1");
        assertThat(RedisKey.publicSessions(1L)).isEqualTo("q:{1}:sessions");
        assertThat(RedisKey.publicJoinStream(1L)).isEqualTo("q:{1}:join-stream");
    }

    @Test
    @SuppressWarnings("unchecked")
    void joinQueue_runs_lua_script_and_registers_performance_for_scheduler() {
        RedissonClient redissonClient = mock(RedissonClient.class);
        RScript script = mock(RScript.class);
        RSet<String> waitingPerformanceSet = mock(RSet.class);
        RedisAdmissionStateStore store = new RedisAdmissionStateStore(redissonClient);
        ArgumentCaptor<List<Object>> keysCaptor = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<Object[]> argsCaptor = ArgumentCaptor.forClass(Object[].class);

        when(redissonClient.getScript(StringCodec.INSTANCE)).thenReturn(script);
        when(redissonClient.<String>getSet(RedisKey.waitingPerformances(), StringCodec.INSTANCE))
                .thenReturn(waitingPerformanceSet);
        when(script.eval(
                eq(RScript.Mode.READ_WRITE),
                anyString(),
                eq(RScript.ReturnType.LIST),
                any(List.class),
                any(Object[].class)
        )).thenReturn(List.of("queue-1", 42L, 1L));

        JoinResult actual = store.joinQueue(
                1L,
                "user-hash",
                "queue-1",
                Duration.ofHours(24),
                5_000L
        );

        assertThat(actual.queueId()).isEqualTo("queue-1");
        assertThat(actual.seq()).isEqualTo(42L);
        assertThat(actual.created()).isTrue();
        verify(script).eval(
                eq(RScript.Mode.READ_WRITE),
                anyString(),
                eq(RScript.ReturnType.LIST),
                keysCaptor.capture(),
                argsCaptor.capture()
        );
        assertThat(keysCaptor.getValue())
                .containsExactly(
                        RedisKey.publicSequence(1L),
                        RedisKey.publicState(1L),
                        RedisKey.publicUser(1L, "user-hash"),
                        RedisKey.publicQueue(1L, "queue-1"),
                        RedisKey.publicJoinStream(1L)
                );
        assertThat(argsCaptor.getValue())
                .containsExactly("queue-1", "user-hash", 86_400_000L, 5_000L);
        verify(waitingPerformanceSet).add("1");
    }

    @Test
    @SuppressWarnings("unchecked")
    void readPublicState_returns_shared_state_without_user_data() {
        RedissonClient redissonClient = mock(RedissonClient.class);
        RMap<String, String> stateMap = mock(RMap.class);
        RedisAdmissionStateStore store = new RedisAdmissionStateStore(redissonClient);

        when(redissonClient.<String, String>getMap(RedisKey.publicState(1L), StringCodec.INSTANCE))
                .thenReturn(stateMap);
        when(stateMap.readAllMap())
                .thenReturn(Map.of(
                        "status", "OPEN",
                        "admittedUntilSeq", "100",
                        "tailSeq", "1000",
                        "refreshAfterMs", "5000"
                ));

        assertThat(store.readPublicState(1L, 1_000L))
                .satisfies(state -> {
                    assertThat(state.performanceId()).isEqualTo(1L);
                    assertThat(state.status()).isEqualTo("OPEN");
                    assertThat(state.admittedUntilSeq()).isEqualTo(100L);
                    assertThat(state.tailSeq()).isEqualTo(1_000L);
                    assertThat(state.refreshAfterMs()).isEqualTo(5_000L);
                    assertThat(state.serverTimeMillis()).isPositive();
                });
    }

    @Test
    @SuppressWarnings("unchecked")
    void enterQueue_maps_lua_result_to_admitted_result() {
        RedissonClient redissonClient = mock(RedissonClient.class);
        RScript script = mock(RScript.class);
        RedisAdmissionStateStore store = new RedisAdmissionStateStore(redissonClient);
        ArgumentCaptor<List<Object>> keysCaptor = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<Object[]> argsCaptor = ArgumentCaptor.forClass(Object[].class);

        when(redissonClient.getScript(StringCodec.INSTANCE)).thenReturn(script);
        when(script.eval(
                eq(RScript.Mode.READ_WRITE),
                anyString(),
                eq(RScript.ReturnType.LIST),
                any(List.class),
                any(Object[].class)
        )).thenReturn(List.of(1L, "admission-token", 1_717_000_900_000L));

        EnterResult actual = store.enterQueue(
                1L,
                "queue-1",
                100L,
                "admission-token",
                Duration.ofMinutes(15),
                5_000
        );

        assertThat(actual.status()).isEqualTo(EnterResult.Status.ADMITTED);
        assertThat(actual.admissionToken()).isEqualTo("admission-token");
        assertThat(actual.expiresAtMillis()).isEqualTo(1_717_000_900_000L);
        verify(script).eval(
                eq(RScript.Mode.READ_WRITE),
                anyString(),
                eq(RScript.ReturnType.LIST),
                keysCaptor.capture(),
                argsCaptor.capture()
        );
        assertThat(keysCaptor.getValue())
                .containsExactly(
                        RedisKey.publicState(1L),
                        RedisKey.publicEntered(1L, "queue-1"),
                        RedisKey.publicSessions(1L),
                        RedisKey.publicQueue(1L, "queue-1")
                );
        assertThat(argsCaptor.getValue())
                .containsExactly(100L, "admission-token", 900_000L, 5_000);
    }

    @Test
    @SuppressWarnings("unchecked")
    void advancePublicState_increments_admitted_sequence_with_lua_script() throws InterruptedException {
        RedissonClient redissonClient = mock(RedissonClient.class);
        RScript script = mock(RScript.class);
        RLock lock = mock(RLock.class);
        RMap<String, String> stateMap = mock(RMap.class);
        RSet<String> waitingPerformanceSet = mock(RSet.class);
        RedisAdmissionStateStore store = new RedisAdmissionStateStore(redissonClient);
        ArgumentCaptor<List<Object>> keysCaptor = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<Object[]> argsCaptor = ArgumentCaptor.forClass(Object[].class);

        when(redissonClient.getLock(RedisKey.advanceLock(1L))).thenReturn(lock);
        when(lock.tryLock(0L, 5_000L, TimeUnit.MILLISECONDS)).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);
        when(redissonClient.getScript(StringCodec.INSTANCE)).thenReturn(script);
        when(redissonClient.<String, String>getMap(RedisKey.publicState(1L), StringCodec.INSTANCE))
                .thenReturn(stateMap);
        when(stateMap.readAllMap()).thenReturn(Map.of("admittedUntilSeq", "500", "tailSeq", "1000"));
        when(redissonClient.<String>getSet(RedisKey.waitingPerformances(), StringCodec.INSTANCE))
                .thenReturn(waitingPerformanceSet);

        store.advancePublicState(1L, 500, 5_000);

        verify(script).eval(
                eq(RScript.Mode.READ_WRITE),
                anyString(),
                eq(RScript.ReturnType.LONG),
                keysCaptor.capture(),
                argsCaptor.capture()
        );
        assertThat(keysCaptor.getValue())
                .containsExactly(RedisKey.publicState(1L), RedisKey.publicSessions(1L));
        assertThat(argsCaptor.getValue()).containsExactly(500, 5_000);
        verify(lock).unlock();
    }
}
