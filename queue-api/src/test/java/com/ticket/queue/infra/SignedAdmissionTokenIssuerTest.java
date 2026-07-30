package com.ticket.queue.infra;

import com.ticket.queue.config.AdmissionTokenProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SignedAdmissionTokenIssuerTest {

    private static final String SECRET = "0123456789abcdef0123456789abcdef";

    @Test
    void issue_creates_member_bound_admission_token() {
        SignedAdmissionTokenIssuer issuer = new SignedAdmissionTokenIssuer(queueAdmissionProperties());

        String token = issuer.issue(10L, 1L, "queue-1", Duration.ofMinutes(15));

        Claims claims = Jwts.parser()
                .requireIssuer("ticket-queue")
                .verifyWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8)))
                .build()
                .parseSignedClaims(token)
                .getPayload();
        assertThat(claims.getSubject()).isEqualTo("10");
        assertThat(claims.get("performanceId", Number.class).longValue()).isEqualTo(1L);
        assertThat(claims.get("scope", String.class)).isEqualTo("ticket-admission");
    }

    private AdmissionTokenProperties queueAdmissionProperties() {
        AdmissionTokenProperties properties = new AdmissionTokenProperties();
        properties.setIssuer("ticket-queue");
        properties.setAudience("ticket-api");
        properties.setSecretKey(SECRET);
        return properties;
    }
}
