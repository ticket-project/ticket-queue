package com.ticket.queue.deploy;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class NginxDeployConfigTest {

    private static final Path NGINX_CONFIG = Path.of("deploy/nginx/default.conf");
    private static final Path COMPOSE_CONFIG = Path.of("deploy/docker-compose.yml");
    private static final Path SCHEDULER_APPLICATION_CONFIG = Path.of("queue-scheduler/src/main/resources/application.yml");

    @Test
    void queue_api_origin_is_not_cached_by_nginx() {
        assertThat(read(NGINX_CONFIG))
                .contains("add_header Cache-Control \"no-store\" always;")
                .doesNotContain("public, max-age")
                .doesNotContain("s-maxage");
    }

    @Test
    void scheduler_receives_no_api_secret() {
        String compose = read(COMPOSE_CONFIG);
        String scheduler = compose.substring(compose.indexOf("\n  scheduler:"), compose.indexOf("\n  nginx:"));

        assertThat(scheduler + read(SCHEDULER_APPLICATION_CONFIG))
                .doesNotContain("env_file:")
                .doesNotContain("JWT_SECRET")
                .doesNotContain("QUEUE_TOKEN_SECRET")
                .doesNotContain("ADMISSION_TOKEN_SECRET_KEY");
    }

    @Test
    void deploy_redis_port_is_not_published() {
        assertThat(read(COMPOSE_CONFIG)).doesNotContain("6379:6379");
    }

    private String read(final Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }
}
