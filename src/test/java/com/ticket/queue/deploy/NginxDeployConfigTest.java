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
    private static final Path DEPLOY_WORKFLOW = Path.of(".github/workflows/deploy.yml");

    @Test
    void proxies_queue_api_without_static_state_cache() {
        String config = read(NGINX_CONFIG);

        assertThat(config)
                .contains("location /api/v1/queue/ {")
                .contains("proxy_pass http://queue:8090;")
                .contains("proxy_hide_header Cache-Control;")
                .contains("add_header Cache-Control \"no-store\" always;")
                .contains("add_header X-Content-Type-Options \"nosniff\" always;")
                .doesNotContain("location /queue-state/")
                .doesNotContain("root /usr/share/nginx/html")
                .doesNotContain("queue-state-access.log")
                .doesNotContain("public, max-age")
                .doesNotContain("s-maxage")
                .doesNotContain("stale-while-revalidate");
    }

    @Test
    void compose_does_not_mount_public_state_static_origin() {
        String compose = read(COMPOSE_CONFIG);

        assertThat(compose)
                .contains("./nginx/default.conf:/etc/nginx/conf.d/default.conf:ro")
                .doesNotContain("./public-state")
                .doesNotContain("/usr/share/nginx/html");
    }

    @Test
    void deploy_workflow_does_not_prepare_static_public_state_directory() {
        String workflow = read(DEPLOY_WORKFLOW);

        assertThat(workflow)
                .contains("sudo mkdir -p /opt/ticket-queue/nginx")
                .contains("source: \"deploy/nginx/default.conf\"")
                .doesNotContain("public-state")
                .doesNotContain("queue-state");
    }

    private String read(final Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }
}
