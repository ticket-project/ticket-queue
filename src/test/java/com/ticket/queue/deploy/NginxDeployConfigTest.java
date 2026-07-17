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
    private static final Path NGINX_MAIN_CONFIG = Path.of("deploy/nginx/nginx.conf");
    private static final Path COMPOSE_CONFIG = Path.of("deploy/docker-compose.yml");
    private static final Path LOCAL_COMPOSE_CONFIG = Path.of("docker-compose.local.yml");
    private static final Path ENV_EXAMPLE = Path.of("deploy/env.example");
    private static final Path DATADOG_REDIS_CONFIG = Path.of("deploy/datadog/conf.d/redisdb.d/conf.yaml");
    private static final Path DOCKERFILE = Path.of("Dockerfile");
    private static final Path DEPLOY_WORKFLOW = Path.of(".github/workflows/deploy.yml");
    private static final Path APPLICATION_CONFIG = Path.of("src/main/resources/application.yml");

    @Test
    void proxies_queue_api_without_static_state_cache() {
        String config = read(NGINX_CONFIG);

        assertThat(config)
                .contains("upstream queue_backend {")
                .contains("server queue:8090;")
                .contains("keepalive 128;")
                .contains("listen 80;")
                .contains("server_name _;")
                .contains("server_name queue.oneticket.site;")
                .contains("return 301 https://$host$request_uri;")
                .contains("listen 443 ssl;")
                .contains("http2 on;")
                .contains("ssl_certificate /etc/letsencrypt/live/queue.oneticket.site/fullchain.pem;")
                .contains("ssl_certificate_key /etc/letsencrypt/live/queue.oneticket.site/privkey.pem;")
                .contains("ssl_session_cache shared:TLS:20m;")
                .contains("ssl_session_timeout 10m;")
                .contains("location /.well-known/acme-challenge/ {")
                .contains("root /var/www/certbot;")
                .contains("location /api/v1/queue/ {")
                .contains("proxy_pass http://queue_backend;")
                .contains("proxy_http_version 1.1;")
                .contains("proxy_set_header Connection \"\";")
                .contains("proxy_set_header X-Forwarded-Proto $scheme;")
                .contains("proxy_set_header X-Forwarded-Proto https;")
                .contains("proxy_hide_header Cache-Control;")
                .contains("add_header Cache-Control \"no-store\" always;")
                .contains("add_header X-Content-Type-Options \"nosniff\" always;")
                .doesNotContain("upstream ticket_queue")
                .doesNotContain("keepalive 1024")
                .doesNotContain("queue-state.oneticket.site")
                .doesNotContain("location /queue-state/")
                .doesNotContain("root /usr/share/nginx/html")
                .doesNotContain("queue-state-access.log")
                .doesNotContain("public, max-age")
                .doesNotContain("s-maxage")
                .doesNotContain("stale-while-revalidate");
    }

    @Test
    void nginx_main_config_raises_worker_and_file_descriptor_limits() {
        String config = read(NGINX_MAIN_CONFIG);

        assertThat(config)
                .contains("worker_processes auto;")
                .contains("worker_rlimit_nofile 65535;")
                .contains("worker_connections 16384;")
                .contains("multi_accept on;")
                .contains("proto=$server_protocol")
                .contains("tls_reused=$ssl_session_reused")
                .contains("include /etc/nginx/conf.d/*.conf;");
    }

    @Test
    void compose_does_not_mount_public_state_static_origin() {
        String compose = read(COMPOSE_CONFIG);

        assertThat(compose)
                .contains("./nginx/nginx.conf:/etc/nginx/nginx.conf:ro")
                .contains("./nginx/default.conf:/etc/nginx/conf.d/default.conf:ro")
                .contains("\"443:443\"")
                .contains("ulimits:")
                .contains("nofile:")
                .contains("soft: 65535")
                .contains("hard: 65535")
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
                .contains("source: \"deploy/nginx/nginx.conf\"")
                .contains("source: \"deploy/nginx/default.conf\"")
                .contains("source: \"deploy/datadog/conf.d/redisdb.d/conf.yaml\"")
                .contains("test -f /etc/letsencrypt/live/queue.oneticket.site/fullchain.pem")
                .contains("test -f /etc/letsencrypt/live/queue.oneticket.site/privkey.pem")
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
    void compose_runs_private_docker_redis_and_queue_uses_single_redis() {
        String compose = read(COMPOSE_CONFIG);

        assertThat(compose)
                .contains("image: redis:7-alpine")
                .contains("container_name: ticket-queue-redis")
                .contains("expose:")
                .contains("- \"6379\"")
                .contains("redis-data:/data")
                .contains("command: redis-server --appendonly no")
                .contains("condition: service_healthy")
                .contains("SPRING_DATA_REDIS_HOST: redis")
                .contains("SPRING_DATA_REDIS_PORT: \"6379\"")
                .contains("redis-data:")
                .doesNotContain("SPRING_PROFILES_ACTIVE")
                .doesNotContain("SPRING_DATA_REDIS_CLUSTER_NODES")
                .doesNotContain("SPRING_DATA_REDIS_USERNAME")
                .doesNotContain("SPRING_DATA_REDIS_PASSWORD")
                .doesNotContain("SPRING_DATA_REDIS_SSL_ENABLED")
                .doesNotContain("\"6379:6379\"")
                .doesNotContain("ticket-managed-redis");
    }

    @Test
    void datadog_agent_scrapes_docker_redis_without_managed_auth() {
        String compose = read(COMPOSE_CONFIG);
        String redisIntegration = read(DATADOG_REDIS_CONFIG);

        assertThat(compose)
                .contains("./datadog/conf.d/redisdb.d/conf.yaml:/etc/datadog-agent/conf.d/redisdb.d/conf.yaml:ro")
                .contains("REDIS_HOST: redis")
                .contains("REDIS_PORT: \"6379\"")
                .doesNotContain("REDIS_USERNAME")
                .doesNotContain("REDIS_PASSWORD");

        assertThat(redisIntegration)
                .contains("host: \"%%env_REDIS_HOST%%\"")
                .contains("port: \"%%env_REDIS_PORT%%\"")
                .contains("ssl: false")
                .contains("env:%%env_DD_ENV%%")
                .contains("service:ticket-queue-redis")
                .contains("managed:false")
                .doesNotContain("username:")
                .doesNotContain("password:")
                .doesNotContain("ssl: true")
                .doesNotContain("ticket-managed-redis");
    }

    @Test
    void local_compose_runs_only_docker_redis_without_datadog_or_queue_app() {
        String localCompose = read(LOCAL_COMPOSE_CONFIG);

        assertThat(localCompose)
                .contains("services:")
                .contains("redis:")
                .contains("image: redis:7-alpine")
                .contains("container_name: ticket-queue-local-redis")
                .contains("\"6379:6379\"")
                .contains("redis-data:")
                .doesNotContain("datadog-agent")
                .doesNotContain("DD_API_KEY")
                .doesNotContain("DOCKER_IMAGE")
                .doesNotContain("SPRING_PROFILES_ACTIVE")
                .doesNotContain("\n  queue:");
    }

    @Test
    void application_uses_single_redis_with_env_overrides() {
        String application = read(APPLICATION_CONFIG);

        assertThat(application)
                .contains("host: ${REDIS_HOST:localhost}")
                .contains("port: ${REDIS_PORT:6379}")
                .contains("env: ${DD_ENV:local}")
                .doesNotContain("username: ${REDIS_USERNAME:}")
                .doesNotContain("password: ${REDIS_PASSWORD:}")
                .doesNotContain("enabled: ${REDIS_SSL_ENABLED:false}")
                .doesNotContain("nodes: ${REDIS_CLUSTER_NODES:}");
    }

    @Test
    void env_example_documents_datadog_and_application_secrets() {
        String env = read(ENV_EXAMPLE);

        assertThat(env)
                .contains("DD_API_KEY=replace-with-datadog-api-key")
                .contains("DD_SITE=us5.datadoghq.com")
                .contains("DD_ENV=prod")
                .contains("DD_VERSION=latest")
                .contains("JWT_SECRET=replace-with-core-access-token-secret-32-byte-minimum")
                .contains("QUEUE_TOKEN_SECRET=replace-with-32-byte-minimum-secret")
                .contains("ADMISSION_TOKEN_SECRET_KEY=replace-with-32-byte-minimum-secret")
                .doesNotContain("SPRING_PROFILES_ACTIVE")
                .doesNotContain("REDIS_CLUSTER_NODES")
                .doesNotContain("REDIS_USERNAME")
                .doesNotContain("REDIS_PASSWORD")
                .doesNotContain("REDIS_SSL_ENABLED")
                .doesNotContain("ticket-managed-redis");
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

    @Test
    void application_enables_tomcat_mbean_metrics_for_prometheus() {
        String application = read(APPLICATION_CONFIG);

        assertThat(application)
                .containsPattern("(?m)^server:\\R  tomcat:\\R    mbeanregistry:\\R      enabled: true$");
    }

    private String read(final Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }
}
