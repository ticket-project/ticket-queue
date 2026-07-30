package com.ticket.queue.infra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.redisson.api.RLock;
import org.redisson.api.RMap;
import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;

class RedisQueueAdvancementStoreTest {

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
    @SuppressWarnings("unchecked")
    void advancePublicState_uses_event_lock_and_updates_projection() throws InterruptedException {
        RedissonClient redissonClient = mock(RedissonClient.class);
        RScript script = mock(RScript.class);
        RLock lock = mock(RLock.class);
        RMap<String, String> stateMap = mock(RMap.class);
        RedisQueueAdvancementStore store = new RedisQueueAdvancementStore(redissonClient);

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
        RedisQueueAdvancementStore store = new RedisQueueAdvancementStore(redissonClient);
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
    }}