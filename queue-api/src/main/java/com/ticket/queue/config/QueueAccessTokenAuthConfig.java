package com.ticket.queue.config;

import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(QueueJwtProperties.class)
public class QueueAccessTokenAuthConfig {

    @Bean
    public AccessTokenAuthenticationFilter accessTokenAuthenticationFilter(final AccessTokenVerifier accessTokenVerifier) {
        return new AccessTokenAuthenticationFilter(accessTokenVerifier);
    }

    @Bean
    public AccessTokenVerifier accessTokenVerifier(final QueueJwtProperties properties, final Clock clock) {
        return new QueueJwtTokenVerifier(properties, clock);
    }
}
