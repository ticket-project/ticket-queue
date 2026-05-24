package com.ticket.queue.config;

import com.ticket.support.security.jwt.JwtProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "security.jwt")
public class QueueJwtProperties {

    private String issuer = "ticket";
    private String secretKey = "local-development-access-token-secret-key-32bytes";
    private long accessTokenExpirationSeconds = 1_800L;

    public JwtProperties toJwtProperties() {
        return new JwtProperties(issuer, secretKey, accessTokenExpirationSeconds);
    }

    public String getIssuer() {
        return issuer;
    }

    public void setIssuer(final String issuer) {
        this.issuer = issuer;
    }

    public String getSecretKey() {
        return secretKey;
    }

    public void setSecretKey(final String secretKey) {
        this.secretKey = secretKey;
    }

    public long getAccessTokenExpirationSeconds() {
        return accessTokenExpirationSeconds;
    }

    public void setAccessTokenExpirationSeconds(final long accessTokenExpirationSeconds) {
        this.accessTokenExpirationSeconds = accessTokenExpirationSeconds;
    }
}