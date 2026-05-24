package com.ticket.queue.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.queue.api")
public class QueueAdmissionProperties {

    private Duration sessionTtl = Duration.ofHours(1);

    public Duration getSessionTtl() {
        return sessionTtl;
    }

    public void setSessionTtl(final Duration sessionTtl) {
        this.sessionTtl = sessionTtl;
    }
}