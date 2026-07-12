package com.ticket.queue.config;

import java.nio.charset.StandardCharsets;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "security.admission")
public class AdmissionTokenProperties {

    private String issuer = "ticket-queue";
    private String audience = "ticket-api";
    private String secretKey;

    public void validate() {
        if (issuer == null || issuer.isBlank()) {
            throw new IllegalArgumentException("admission token issuer must not be blank");
        }
        if (audience == null || audience.isBlank()) {
            throw new IllegalArgumentException("admission token audience must not be blank");
        }
        if (secretKey == null || secretKey.isBlank()) {
            throw new IllegalArgumentException("admission token secret key must not be blank");
        }
        if (secretKey.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalArgumentException("admission token secret key must be at least 32 bytes for HS256");
        }
    }
}
