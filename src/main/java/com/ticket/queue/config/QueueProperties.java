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

    private int admitLimitPerTick = 50;
    private int maxActiveUsers = 1_000;
    private Duration activeTtl = Duration.ofMinutes(5);
}
