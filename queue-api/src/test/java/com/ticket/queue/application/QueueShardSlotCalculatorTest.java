package com.ticket.queue.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.ticket.queue.config.QueueProperties;
import com.ticket.queue.domain.QueueShardSlot;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class QueueShardSlotCalculatorTest {

    @Test
    void calculates_slot_from_server_time_and_stable_shard_from_member() {
        QueueProperties properties = new QueueProperties();
        properties.setShardCount(128);
        properties.setSlotSizeMillis(50L);
        Clock clock = Clock.fixed(Instant.ofEpochMilli(1_234_567L), ZoneOffset.UTC);
        QueueShardSlotCalculator calculator = new QueueShardSlotCalculator(properties, clock);

        QueueShardSlot first = calculator.calculate(100L, 10L);
        QueueShardSlot second = calculator.calculate(100L, 10L);

        assertThat(first.slotId()).isEqualTo(24_691L);
        assertThat(first.slotStartMillis()).isEqualTo(1_234_550L);
        assertThat(first.shardId()).isBetween(0, 127);
        assertThat(second.shardId()).isEqualTo(first.shardId());
    }
}
