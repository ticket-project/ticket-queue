package com.ticket.queue.config;

import com.ticket.support.security.admission.AdmissionTokenProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "security.admission")
public class QueueAdmissionTokenProperties {

    private String issuer = "ticket-queue";
    private String audience = "ticket-api";
    private String secretKey = "local-development-admission-secret-key-32bytes";
    private long expirationSeconds = 300L;

    public AdmissionTokenProperties toAdmissionTokenProperties() {
        return new AdmissionTokenProperties(issuer, audience, secretKey, expirationSeconds);
    }

    public String getIssuer() {
        return issuer;
    }

    public void setIssuer(final String issuer) {
        this.issuer = issuer;
    }

    public String getAudience() {
        return audience;
    }

    public void setAudience(final String audience) {
        this.audience = audience;
    }

    public String getSecretKey() {
        return secretKey;
    }

    public void setSecretKey(final String secretKey) {
        this.secretKey = secretKey;
    }

    public long getExpirationSeconds() {
        return expirationSeconds;
    }

    public void setExpirationSeconds(final long expirationSeconds) {
        this.expirationSeconds = expirationSeconds;
    }
}