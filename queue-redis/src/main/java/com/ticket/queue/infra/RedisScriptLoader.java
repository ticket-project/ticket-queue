package com.ticket.queue.infra;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import org.springframework.core.io.ClassPathResource;
import org.springframework.util.StreamUtils;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
final class RedisScriptLoader {

    static String load(final String location) {
        try {
            return StreamUtils.copyToString(
                    new ClassPathResource(location).getInputStream(),
                    StandardCharsets.UTF_8
            );
        } catch (IOException exception) {
            throw new IllegalStateException("failed to read Redis script " + location, exception);
        }
    }
}
