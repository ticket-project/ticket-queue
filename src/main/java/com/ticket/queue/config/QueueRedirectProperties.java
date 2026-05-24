package com.ticket.queue.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.queue.redirect")
public class QueueRedirectProperties {

    private String ticketingUrlTemplate = "/booking/seat?performanceId={performanceId}";

    public String resolve(final Long performanceId) {
        return ticketingUrlTemplate.replace("{performanceId}", String.valueOf(performanceId));
    }

    public String getTicketingUrlTemplate() {
        return ticketingUrlTemplate;
    }

    public void setTicketingUrlTemplate(final String ticketingUrlTemplate) {
        this.ticketingUrlTemplate = ticketingUrlTemplate;
    }
}