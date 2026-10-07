package com.ticket.queue.config;

import java.nio.charset.StandardCharsets;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "security.jwt")
public class QueueJwtProperties {

    private String issuer = "ticket";
    private String secretKey;

    public void validate() {
        if (issuer == null || issuer.isBlank()) {
            throw new IllegalArgumentException("security.jwt.issuer must not be blank");
        }
        if (secretKey == null || secretKey.isBlank()) {
            throw new IllegalArgumentException("security.jwt.secret-key must not be blank");
        }
        if (secretKey.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalArgumentException("security.jwt.secret-key must be at least 32 bytes for HS256");
        }
    }
}
