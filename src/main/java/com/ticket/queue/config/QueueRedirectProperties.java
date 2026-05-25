package com.ticket.queue.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "app.queue.redirect")
public class QueueRedirectProperties {

    private String ticketingUrlTemplate = "/booking/seat?performanceId={performanceId}";

    public String resolve(final Long performanceId) {
        return ticketingUrlTemplate.replace("{performanceId}", String.valueOf(performanceId));
    }
}
