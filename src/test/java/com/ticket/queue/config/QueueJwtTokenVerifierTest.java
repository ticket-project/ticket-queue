package com.ticket.queue.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class QueueJwtTokenVerifierTest {

    private static final String SECRET = "0123456789abcdef0123456789abcdef";

    @Test
    void verify_restores_authenticated_member_from_bearer_access_token() {
        QueueJwtTokenVerifier verifier = new QueueJwtTokenVerifier(properties());
        String token = issueToken(10L, "MEMBER", Instant.now().plusSeconds(1800));

        AuthenticatedMember member = verifier.verify("Bearer " + token);

        assertThat(member.memberId()).isEqualTo(10L);
        assertThat(member.role()).isEqualTo("MEMBER");
    }

    @Test
    void verify_rejects_invalid_bearer_header() {
        QueueJwtTokenVerifier verifier = new QueueJwtTokenVerifier(properties());

        assertThatThrownBy(() -> verifier.verify("not-a-bearer-token"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("invalid");
    }

    private String issueToken(final Long memberId, final String role, final Instant expiresAt) {
        return Jwts.builder()
                .issuer("ticket")
                .subject(String.valueOf(memberId))
                .claim("role", role)
                .issuedAt(Date.from(Instant.now()))
                .expiration(Date.from(expiresAt))
                .signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8)))
                .compact();
    }

    private QueueJwtProperties properties() {
        QueueJwtProperties properties = new QueueJwtProperties();
        properties.setIssuer("ticket");
        properties.setSecretKey(SECRET);
        properties.setAccessTokenExpirationSeconds(1800L);
        return properties;
    }
}
