package com.ticket.queue.infra;

import static org.assertj.core.api.Assertions.assertThat;

import com.ticket.queue.domain.EnterResult;
import com.ticket.queue.domain.JoinResult;
import com.ticket.queue.domain.PublicState;
import com.ticket.queue.domain.QueueShardSlot;
import java.time.Duration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.Redisson;
import org.redisson.api.RMap;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.redisson.config.Config;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
class RedisQueueContractIntegrationTest {

    private static final int REDIS_PORT = 6379;
    private static final int SHARD_COUNT = 2;
    private static final long SLOT_SIZE_MILLIS = 50L;
    private static final Duration QUEUE_TTL = Duration.ofSeconds(5);
    private static final Duration SESSION_TTL = Duration.ofMillis(400);

    @Container
    private static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine"))
            .withExposedPorts(REDIS_PORT);

    private static RedissonClient redissonClient;

    private RedisAdmissionStateStore admissionStore;
    private RedisQueueAdvancementStore advancementStore;

    @BeforeAll
    static void setUpRedisClient() {
        Config config = new Config();
        config.setCodec(StringCodec.INSTANCE);
        config.useSingleServer()
                .setAddress("redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(REDIS_PORT));
        redissonClient = Redisson.create(config);
    }

    @AfterAll
    static void closeRedisClient() {
        if (redissonClient != null) {
            redissonClient.shutdown();
        }
    }

    @BeforeEach
    void resetRedis() {
        redissonClient.getKeys().flushall();
        admissionStore = new RedisAdmissionStateStore(redissonClient);
        advancementStore = new RedisQueueAdvancementStore(redissonClient);
    }

    @Test
    void join_advance_enter_is_atomic_idempotent_and_session_ttl_bound() throws Exception {
        QueueShardSlot slot = closedSlot(0);

        JoinResult joined = admissionStore.joinQueue(1L, "user-a", "queue-a", slot, QUEUE_TTL);
        JoinResult duplicate = admissionStore.joinQueue(1L, "user-a", "queue-b", slot, QUEUE_TTL);

        assertThat(joined.created()).isTrue();
        assertThat(joined.localSeq()).isEqualTo(1L);
        assertThat(duplicate.created()).isFalse();
        assertThat(duplicate.queueId()).isEqualTo("queue-a");
        assertThat(advancementStore.findWaitingPerformanceIds()).containsExactly(1L);
        assertThat(admissionStore.enterQueue(
                1L, "queue-a", 0, 1L, "admission-a", SESSION_TTL
        ).status()).isEqualTo(EnterResult.Status.NOT_ADMITTED);

        advance(1L, 10);

        PublicState state = admissionStore.readPublicState(1L, 1_000L);
        assertThat(state.serving()).containsEntry(0, 1L).containsEntry(1, 0L);
        assertThat(state.tail()).containsEntry(0, 1L).containsEntry(1, 0L);
        assertThat(state.status()).isEqualTo("EMPTY");

        EnterResult admitted = admissionStore.enterQueue(
                1L, "queue-a", 0, 1L, "admission-a", SESSION_TTL
        );
        EnterResult retried = admissionStore.enterQueue(
                1L, "queue-a", 0, 1L, "different-token", SESSION_TTL
        );

        assertThat(admitted.status()).isEqualTo(EnterResult.Status.ADMITTED);
        assertThat(retried).isEqualTo(admitted);
        assertThat(redissonClient
                .getMap(RedisKey.performanceEntered(1L, "queue-a"), StringCodec.INSTANCE)
                .remainTimeToLive()).isBetween(1L, SESSION_TTL.toMillis());

        awaitCondition(
                () -> redissonClient.getKeys().countExists(RedisKey.performanceEntered(1L, "queue-a")) == 0L,
                "admission session did not expire"
        );

        EnterResult reentered = admissionStore.enterQueue(
                1L, "queue-a", 0, 1L, "admission-b", SESSION_TTL
        );
        assertThat(reentered.status()).isEqualTo(EnterResult.Status.ADMITTED);
        assertThat(reentered.admissionToken()).isEqualTo("admission-b");
    }

    @Test
    void scheduler_advances_closed_slot_round_robin_and_cleans_waiting_registry() {
        QueueShardSlot shardZeroSlot = closedSlot(0);
        QueueShardSlot shardOneSlot = new QueueShardSlot(
                1,
                shardZeroSlot.slotId(),
                shardZeroSlot.slotStartMillis()
        );
        admissionStore.joinQueue(2L, "user-a", "queue-a", shardZeroSlot, QUEUE_TTL);
        admissionStore.joinQueue(2L, "user-b", "queue-b", shardOneSlot, QUEUE_TTL);

        advance(2L, 1);
        PublicState first = admissionStore.readPublicState(2L, 1_000L);
        assertThat(first.serving()).containsEntry(0, 1L).containsEntry(1, 0L);
        assertThat(first.status()).isEqualTo("OPEN");

        advance(2L, 1);
        PublicState second = admissionStore.readPublicState(2L, 1_000L);
        assertThat(second.serving()).containsEntry(0, 1L).containsEntry(1, 1L);
        assertThat(second.status()).isEqualTo("EMPTY");

        redissonClient.getKeys().delete(
                RedisKey.shardWaitingMarker(2L, 0),
                RedisKey.shardWaitingMarker(2L, 1)
        );
        advance(2L, 1);
        assertThat(advancementStore.findWaitingPerformanceIds()).isEmpty();
    }

    @Test
    void expired_queue_ticket_is_rejected_by_enter_lua() throws Exception {
        QueueShardSlot slot = closedSlot(0);
        admissionStore.joinQueue(3L, "user-a", "queue-a", slot, Duration.ofMillis(150));

        awaitCondition(
                () -> redissonClient.getKeys().countExists(RedisKey.shardQueue(3L, 0, "queue-a")) == 0L,
                "queue ticket did not expire"
        );

        EnterResult result = admissionStore.enterQueue(
                3L, "queue-a", 0, 1L, "admission-a", SESSION_TTL
        );
        assertThat(result.status()).isEqualTo(EnterResult.Status.EXPIRED);
    }

    @Test
    void legacy_enter_uses_global_state_and_keeps_session_idempotent() {
        RMap<String, String> publicState = redissonClient.getMap(RedisKey.publicState(4L), StringCodec.INSTANCE);
        publicState.put("admittedUntilSeq", "1");
        publicState.expire(QUEUE_TTL);
        RMap<String, String> legacyTicket = redissonClient.getMap(
                RedisKey.legacyQueue(4L, "legacy-queue"),
                StringCodec.INSTANCE
        );
        legacyTicket.put("seq", "1");
        legacyTicket.expire(QUEUE_TTL);

        EnterResult admitted = admissionStore.enterLegacyQueue(
                4L, "legacy-queue", 1L, "legacy-admission", SESSION_TTL
        );
        EnterResult retried = admissionStore.enterLegacyQueue(
                4L, "legacy-queue", 1L, "different-token", SESSION_TTL
        );

        assertThat(admitted.status()).isEqualTo(EnterResult.Status.ADMITTED);
        assertThat(retried).isEqualTo(admitted);
    }

    private QueueShardSlot closedSlot(final int shardId) {
        long slotStartMillis = System.currentTimeMillis() - 1_000L;
        long slotId = Math.floorDiv(slotStartMillis, SLOT_SIZE_MILLIS);
        return new QueueShardSlot(shardId, slotId, slotStartMillis);
    }

    private void advance(final Long performanceId, final int batchSize) {
        advancementStore.advancePublicState(
                performanceId,
                batchSize,
                SHARD_COUNT,
                SLOT_SIZE_MILLIS,
                0L,
                QUEUE_TTL,
                1_000L
        );
    }

    private void awaitCondition(
            final CheckedBooleanSupplier condition,
            final String failureMessage
    ) throws Exception {
        long deadlineNanos = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() >= deadlineNanos) {
                throw new AssertionError(failureMessage);
            }
            Thread.sleep(25L);
        }
    }

    @FunctionalInterface
    private interface CheckedBooleanSupplier {
        boolean getAsBoolean() throws Exception;
    }
}
