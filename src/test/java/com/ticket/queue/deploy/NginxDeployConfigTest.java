package com.ticket.queue.deploy;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;

class NginxDeployConfigTest {

    private static final Path NGINX_CONFIG = Path.of("deploy/nginx/default.conf");
    private static final Path COMPOSE_CONFIG = Path.of("deploy/docker-compose.yml");
    private static final Path SCHEDULER_APPLICATION_CONFIG = Path.of("queue-scheduler/src/main/resources/application.yml");
    private static final Path API_APPLICATION_CONFIG = Path.of("queue-api/src/main/resources/application.yml");
    private static final Path ENV_EXAMPLE = Path.of("deploy/env.example");

    /** API(join)와 scheduler(advance)가 같은 값을 봐야 하는 대기열 정책이다. 한쪽만 바뀌면 scheduler가 전진시키지 않는 shard에 사용자가 쌓인다. */
    private static final Map<String, String> SHARED_QUEUE_POLICY = Map.of(
            "QUEUE_DEFAULT_QUEUE_TTL", "24h",
            "QUEUE_DEFAULT_REFRESH_AFTER_MS", "5000",
            "QUEUE_SHARD_COUNT", "128",
            "QUEUE_SLOT_SIZE_MILLIS", "50");

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

    @Test
    void api_and_scheduler_share_the_same_queue_policy_defaults() {
        String api = read(API_APPLICATION_CONFIG);
        String scheduler = read(SCHEDULER_APPLICATION_CONFIG);
        String compose = read(COMPOSE_CONFIG);
        String schedulerService = compose.substring(compose.indexOf("\n  scheduler:"), compose.indexOf("\n  nginx:"));
        String env = read(ENV_EXAMPLE);

        SHARED_QUEUE_POLICY.forEach((key, value) -> {
            assertThat(api).as("api %s", key).contains("${" + key + ":" + value + "}");
            assertThat(scheduler).as("scheduler %s", key).contains("${" + key + ":" + value + "}");
            assertThat(schedulerService).as("compose scheduler %s", key).contains(key + ": ${" + key + ":-" + value + "}");
            assertThat(env).as("env.example %s", key).contains(key + "=" + value);
        });
    }

    private String read(final Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }
}
