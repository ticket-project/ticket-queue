package com.ticket.queue.config;

import com.ticket.support.passport.Passport;
import com.ticket.support.passport.web.PassportAuthenticationFilter;
import com.ticket.support.passport.web.PassportVerifier;
import com.ticket.support.token.passport.PassportService;
import com.ticket.support.token.passport.PassportTokenException;
import com.ticket.support.token.passport.PassportTokenProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@Configuration
@RequiredArgsConstructor
public class PassportConfig {

    private final PassportProperties passportProperties;

    @Bean
    public PassportService passportService() {
        passportProperties.validate();
        return new PassportService(new PassportTokenProperties(
                passportProperties.getIssuer(),
                passportProperties.getAudience(),
                passportProperties.getSecretKey(),
                passportProperties.getExpirationSeconds()
        ));
    }

    @Bean
    public PassportVerifier passportVerifier(
            final PassportService passportService
    ) {
        return passportHeader -> verifyPassport(passportService, passportHeader);
    }

    @Bean
    public PassportAuthenticationFilter passportAuthenticationFilter(
            final PassportVerifier passportVerifier
    ) {
        return new PassportAuthenticationFilter(passportVerifier);
    }

    private Passport verifyPassport(
            final PassportService passportService,
            final String passportHeader
    ) {
        try {
            return passportService.verifyBearer(passportHeader);
        } catch (PassportTokenException | IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid passport token");
        }
    }
}
