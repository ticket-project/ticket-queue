package com.ticket.queue.config;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import java.time.Duration;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

@Getter
@Setter
@Component
@Validated
@ConfigurationProperties(prefix = "app.queue")
public class AdvancementProperties {

    @NotNull
    private Duration defaultQueueTtl = Duration.ofHours(24);
    @Positive
    private int advanceBatchSize = 500;
    @Positive
    private long defaultRefreshAfterMs = 5_000L;
    @Positive
    private int shardCount = 128;
    @Positive
    private long slotSizeMillis = 50L;
    @PositiveOrZero
    private long slotCloseGraceMillis = 200L;
}
