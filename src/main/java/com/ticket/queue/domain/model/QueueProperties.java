package com.ticket.queue.domain.model;

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

    private QueueLevel defaultLevel = QueueLevel.LEVEL_1;
    private int defaultMaxActiveUsers = 300;
    private Duration defaultEntryTokenTtl = Duration.ofMinutes(5);
    private Duration entryRetention = Duration.ofHours(1);
}