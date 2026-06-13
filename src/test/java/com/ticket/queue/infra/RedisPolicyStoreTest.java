package com.ticket.queue.infra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ticket.queue.domain.Policy;
import com.ticket.queue.domain.PolicyMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;

class RedisPolicyStoreTest {

    @Test
    @SuppressWarnings("unchecked")
    void save는_회차별_queue_policy_snapshot을_Redis에_저장한다() {
        RedissonClient redissonClient = mock(RedissonClient.class);
        RBucket<String> bucket = mock(RBucket.class);
        RedisPolicyStore store = new RedisPolicyStore(redissonClient);
        Policy policy = new Policy(
                10,
                200,
                Duration.ofMinutes(3),
                Duration.ofMinutes(30),
                PolicyMode.AUTO,
                LocalDateTime.of(2026, 5, 24, 19, 50),
                LocalDateTime.of(2026, 5, 24, 21, 0)
        );

        when(redissonClient.<String>getBucket(RedisKey.policy(1L), StringCodec.INSTANCE))
                .thenReturn(bucket);

        store.save(1L, policy);

        verify(bucket).set(eq("10|200|180000|1800000|AUTO|2026-05-24T19:50|2026-05-24T21:00"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void findByPerformanceId는_Redis_snapshot을_Policy로_복원한다() {
        RedissonClient redissonClient = mock(RedissonClient.class);
        RBucket<String> bucket = mock(RBucket.class);
        RedisPolicyStore store = new RedisPolicyStore(redissonClient);

        when(redissonClient.<String>getBucket(RedisKey.policy(1L), StringCodec.INSTANCE))
                .thenReturn(bucket);
        when(bucket.get()).thenReturn("10|200|180000|1800000|AUTO|2026-05-24T19:50|2026-05-24T21:00");

        Optional<Policy> actual = store.findByPerformanceId(1L);

        assertThat(actual).isPresent();
        assertThat(actual.get().admitLimitPerTick()).isEqualTo(10);
        assertThat(actual.get().maxActiveUsers()).isEqualTo(200);
        assertThat(actual.get().activeTtl()).isEqualTo(Duration.ofMinutes(3));
        assertThat(actual.get().sessionTtl()).isEqualTo(Duration.ofMinutes(30));
        assertThat(actual.get().queueMode()).isEqualTo(PolicyMode.AUTO);
        assertThat(actual.get().preopenQueueStartAt()).isEqualTo(LocalDateTime.of(2026, 5, 24, 19, 50));
        assertThat(actual.get().orderCloseTime()).isEqualTo(LocalDateTime.of(2026, 5, 24, 21, 0));
    }

    @Test
    @SuppressWarnings("unchecked")
    void findByPerformanceId는_기존_4필드_snapshot을_FORCE_ON_정책으로_복원한다() {
        RedissonClient redissonClient = mock(RedissonClient.class);
        RBucket<String> bucket = mock(RBucket.class);
        RedisPolicyStore store = new RedisPolicyStore(redissonClient);

        when(redissonClient.<String>getBucket(RedisKey.policy(1L), StringCodec.INSTANCE))
                .thenReturn(bucket);
        when(bucket.get()).thenReturn("10|200|180000|1800000");

        Optional<Policy> actual = store.findByPerformanceId(1L);

        assertThat(actual).isPresent();
        assertThat(actual.get().queueMode()).isEqualTo(PolicyMode.FORCE_ON);
    }
}
