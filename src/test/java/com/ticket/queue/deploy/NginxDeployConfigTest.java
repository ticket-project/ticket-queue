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
    private static final Path ENV_EXAMPLE = Path.of("deploy/env.example");
    private static final Path DATADOG_REDIS_CONFIG = Path.of("deploy/datadog/conf.d/redisdb.d/conf.yaml");
    private static final Path DOCKERFILE = Path.of("Dockerfile");
    private static final Path DEPLOY_WORKFLOW = Path.of(".github/workflows/deploy.yml");
    private static final Path APPLICATION_CONFIG = Path.of("src/main/resources/application.yml");

    @Test
    void proxies_queue_api_without_static_state_cache() {
        String config = read(NGINX_CONFIG);

        assertThat(config)
                .contains("listen 80;")
                .contains("return 301 https://$host$request_uri;")
                .contains("listen 443 ssl;")
                .contains("ssl_certificate /etc/letsencrypt/live/queue.oneticket.site/fullchain.pem;")
                .contains("ssl_certificate_key /etc/letsencrypt/live/queue.oneticket.site/privkey.pem;")
                .contains("location /.well-known/acme-challenge/ {")
                .contains("root /var/www/certbot;")
                .contains("location /api/v1/queue/ {")
                .contains("proxy_pass http://queue:8090;")
                .contains("proxy_set_header X-Forwarded-Proto https;")
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
                .contains("\"443:443\"")
                .contains("./certbot/www:/var/www/certbot:ro")
                .contains("/etc/letsencrypt:/etc/letsencrypt:ro")
                .doesNotContain("./public-state")
                .doesNotContain("/usr/share/nginx/html");
    }

    @Test
    void deploy_workflow_does_not_prepare_static_public_state_directory() {
        String workflow = read(DEPLOY_WORKFLOW);

        assertThat(workflow)
                .contains("sudo mkdir -p /opt/ticket-queue/nginx")
                .contains("sudo mkdir -p /opt/ticket-queue/certbot/www")
                .contains("sudo mkdir -p /opt/ticket-queue/datadog/conf.d/redisdb.d")
                .contains("source: \"deploy/docker-compose.yml\"")
                .contains("source: \"deploy/nginx/default.conf\"")
                .contains("source: \"deploy/datadog/conf.d/redisdb.d/conf.yaml\"")
                .contains("sudo test -f /etc/letsencrypt/live/queue.oneticket.site/fullchain.pem")
                .contains("sudo test -f /etc/letsencrypt/live/queue.oneticket.site/privkey.pem")
                .contains("missing TLS certificate: /etc/letsencrypt/live/queue.oneticket.site/fullchain.pem")
                .contains("missing TLS private key: /etc/letsencrypt/live/queue.oneticket.site/privkey.pem")
                .doesNotContain("public-state")
                .doesNotContain("queue-state");
    }

    @Test
    void docker_image_packages_datadog_java_agent() {
        String dockerfile = read(DOCKERFILE);

        assertThat(dockerfile)
                .contains("ARG DD_JAVA_AGENT_VERSION=")
                .contains("/opt/datadog/dd-java-agent.jar")
                .contains("COPY build/libs/*.jar app.jar");
    }

    @Test
    void compose_wires_datadog_agent_for_apm_logs_and_openmetrics() {
        String compose = read(COMPOSE_CONFIG);

        assertThat(compose)
                .contains("JAVA_TOOL_OPTIONS: -javaagent:/opt/datadog/dd-java-agent.jar")
                .contains("DD_AGENT_HOST: datadog-agent")
                .contains("DD_SERVICE: ticket-queue")
                .contains("DD_ENV: ${DD_ENV:-prod}")
                .contains("DD_SITE: ${DD_SITE:-us5.datadoghq.com}")
                .contains("DD_APM_ENABLED: \"true\"")
                .contains("DD_LOGS_ENABLED: \"true\"")
                .contains("DD_RUNTIME_METRICS_ENABLED: \"true\"")
                .contains("com.datadoghq.ad.logs")
                .contains("com.datadoghq.ad.checks")
                .contains("/actuator/prometheus")
                .contains("namespace\":\"ticket_queue");
    }

    @Test
    void compose_uses_managed_redis_for_queue_and_does_not_define_docker_redis() {
        String compose = read(COMPOSE_CONFIG);

        assertThat(compose)
                .contains("SPRING_DATA_REDIS_CLUSTER_NODES: ${REDIS_CLUSTER_NODES:?REDIS_CLUSTER_NODES is required}")
                .contains("SPRING_DATA_REDIS_USERNAME: ${REDIS_USERNAME:-default}")
                .contains("SPRING_DATA_REDIS_PASSWORD: ${REDIS_PASSWORD:?REDIS_PASSWORD is required}")
                .contains("SPRING_DATA_REDIS_SSL_ENABLED: ${REDIS_SSL_ENABLED:-true}")
                .doesNotContain("image: redis:7-alpine")
                .doesNotContain("container_name: ticket-queue-redis")
                .doesNotContain("local-redis")
                .doesNotContain("redis-data")
                .doesNotContain("SPRING_DATA_REDIS_HOST: redis")
                .doesNotContain("SPRING_DATA_REDIS_PORT: 6379")
                .doesNotContain("- redis\n      - datadog-agent");
    }

    @Test
    void datadog_agent_has_managed_redis_integration_config() {
        String compose = read(COMPOSE_CONFIG);
        String redisIntegration = read(DATADOG_REDIS_CONFIG);

        assertThat(compose)
                .contains("./datadog/conf.d/redisdb.d/conf.yaml:/etc/datadog-agent/conf.d/redisdb.d/conf.yaml:ro")
                .contains("REDIS_HOST: ${REDIS_HOST:?REDIS_HOST is required}")
                .contains("REDIS_PORT: ${REDIS_PORT:-10000}")
                .contains("REDIS_USERNAME: ${REDIS_USERNAME:-default}")
                .contains("REDIS_PASSWORD: ${REDIS_PASSWORD:?REDIS_PASSWORD is required}")
                .doesNotContain("DD_CONTAINER_EXCLUDE_METRICS: name:ticket-queue-redis");

        assertThat(redisIntegration)
                .contains("host: \"%%env_REDIS_HOST%%\"")
                .contains("port: \"%%env_REDIS_PORT%%\"")
                .contains("username: \"%%env_REDIS_USERNAME%%\"")
                .contains("password: \"%%env_REDIS_PASSWORD%%\"")
                .contains("ssl: true")
                .contains("service:ticket-managed-redis");
    }

    @Test
    void env_example_documents_datadog_configuration() {
        String env = read(ENV_EXAMPLE);

        assertThat(env)
                .contains("DD_API_KEY=replace-with-datadog-api-key")
                .contains("DD_SITE=us5.datadoghq.com")
                .contains("DD_ENV=prod")
                .contains("DD_VERSION=latest")
                .contains("REDIS_HOST=ticket-managed-redis.southeastasia.redis.azure.net")
                .contains("REDIS_PORT=10000")
                .contains("REDIS_CLUSTER_NODES=rediss://ticket-managed-redis.southeastasia.redis.azure.net:10000")
                .contains("REDIS_USERNAME=default")
                .contains("REDIS_PASSWORD=replace-with-managed-redis-access-key")
                .contains("REDIS_SSL_ENABLED=true");
    }

    @Test
    void application_prometheus_metrics_include_datadog_common_tags() {
        String application = read(APPLICATION_CONFIG);

        assertThat(application)
                .contains("include: health,info,prometheus")
                .contains("metrics:")
                .contains("tags:")
                .contains("service: ${DD_SERVICE:ticket-queue}")
                .contains("env: ${DD_ENV:local}")
                .contains("version: ${DD_VERSION:local}");
    }

    private String read(final Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }
}
