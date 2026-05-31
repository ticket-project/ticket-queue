package com.ticket.queue.config;

import com.ticket.support.passport.Passport;
import com.ticket.support.passport.web.InternalAuthPassportFilter;
import com.ticket.support.passport.web.InternalAuthPassportVerifier;
import com.ticket.support.security.internalauth.InternalAuthPassportService;
import com.ticket.support.security.internalauth.InternalAuthTokenException;
import com.ticket.support.security.internalauth.InternalAuthTokenProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@Configuration
@RequiredArgsConstructor
public class InternalAuthConfig {

    private final InternalAuthProperties internalAuthProperties;

    @Bean
    public InternalAuthPassportService internalAuthPassportService() {
        internalAuthProperties.validate();
        return new InternalAuthPassportService(new InternalAuthTokenProperties(
                internalAuthProperties.getIssuer(),
                internalAuthProperties.getAudience(),
                internalAuthProperties.getSecretKey(),
                internalAuthProperties.getExpirationSeconds()
        ));
    }

    @Bean
    public InternalAuthPassportVerifier internalAuthPassportVerifier(
            final InternalAuthPassportService internalAuthPassportService
    ) {
        return internalAuthHeader -> verifyInternalAuth(internalAuthPassportService, internalAuthHeader);
    }

    @Bean
    public InternalAuthPassportFilter internalAuthPassportFilter(
            final InternalAuthPassportVerifier internalAuthPassportVerifier
    ) {
        return new InternalAuthPassportFilter(internalAuthPassportVerifier);
    }

    private Passport verifyInternalAuth(
            final InternalAuthPassportService internalAuthPassportService,
            final String internalAuthHeader
    ) {
        try {
            return internalAuthPassportService.verifyBearer(internalAuthHeader);
        } catch (InternalAuthTokenException | IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid internal auth token");
        }
    }
}
