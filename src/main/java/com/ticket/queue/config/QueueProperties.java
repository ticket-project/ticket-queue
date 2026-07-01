package com.ticket.queue.config;

import java.time.Duration;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "app.queue")
public class QueueProperties {

    private Duration defaultQueueTtl = Duration.ofHours(24);
    private Duration shoppingSessionTtl = Duration.ofMinutes(15);
    private int defaultMaxActiveSessions = 5_000;
    private int defaultMaxAdmitPerSecond = 500;
    private long defaultRefreshAfterMs = 5_000L;
    private int shardCount = 128;
    private long slotSizeMillis = 50L;
    private long slotCloseGraceMillis = 200L;
    private long joinPollAfterMs = 1_000L;
    private String queueTokenSecret;
    private boolean schedulerEnabled = true;
}
