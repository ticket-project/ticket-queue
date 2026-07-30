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
    private static final Path API_DOCKERFILE = Path.of("queue-api/Dockerfile");
    private static final Path SCHEDULER_DOCKERFILE = Path.of("queue-scheduler/Dockerfile");
    private static final Path DEPLOY_WORKFLOW = Path.of(".github/workflows/deploy.yml");
    private static final Path API_APPLICATION_CONFIG = Path.of("queue-api/src/main/resources/application.yml");
    private static final Path SCHEDULER_APPLICATION_CONFIG = Path.of("queue-scheduler/src/main/resources/application.yml");

    @Test
    void proxies_queue_api_without_static_state_cache() {
        String config = read(NGINX_CONFIG);

        assertThat(config)
                .contains("upstream queue_backend {")
                .contains("server queue:8090;")
                .contains("keepalive 128;")
                .contains("listen 80;")
                .contains("server_name queue.oneticket.site;")
                .contains("return 301 https://$host$request_uri;")
                .contains("listen 443 ssl;")
                .contains("http2 on;")
                .contains("ssl_certificate /etc/letsencrypt/live/queue.oneticket.site/fullchain.pem;")
                .contains("ssl_certificate_key /etc/letsencrypt/live/queue.oneticket.site/privkey.pem;")
                .contains("location /api/v1/queue/ {")
                .contains("proxy_pass http://queue_backend;")
                .contains("add_header Cache-Control \"no-store\" always;")
                .doesNotContain("queue-state.oneticket.site")
                .doesNotContain("location /queue-state/")
                .doesNotContain("public, max-age")
                .doesNotContain("s-maxage");
    }

    @Test
    void nginx_main_config_raises_worker_and_file_descriptor_limits() {
        String config = read(NGINX_MAIN_CONFIG);

        assertThat(config)
                .contains("worker_processes auto;")
                .contains("worker_rlimit_nofile 65535;")
                .contains("worker_connections 16384;")
                .contains("multi_accept on;")
                .contains("include /etc/nginx/conf.d/*.conf;");
    }

    @Test
    void compose_exposes_only_the_api_through_nginx() {
        String compose = read(COMPOSE_CONFIG);
        String api = serviceSection(compose, "queue", "scheduler");
        String scheduler = serviceSection(compose, "scheduler", "nginx");

        assertThat(api)
                .contains("image: ${QUEUE_API_DOCKER_IMAGE:?QUEUE_API_DOCKER_IMAGE is required}")
                .contains("env_file:")
                .contains("\"8090:8090\"")
                .contains("DD_SERVICE: ticket-queue-api");

        assertThat(scheduler)
                .contains("image: ${QUEUE_SCHEDULER_DOCKER_IMAGE:?QUEUE_SCHEDULER_DOCKER_IMAGE is required}")
                .contains("SPRING_DATA_REDIS_HOST: redis")
                .contains("QUEUE_MAX_ACTIVE_SESSIONS_PER_PERFORMANCE")
                .contains("QUEUE_MAX_ADMIT_PER_SECOND_PER_PERFORMANCE")
                .contains("QUEUE_ADVANCE_INTERVAL_MS")
                .contains("DD_SERVICE: ticket-queue-scheduler")
                .contains("- \"8091\"")
                .doesNotContain("env_file:")
                .doesNotContain("ports:")
                .doesNotContain("JWT_SECRET")
                .doesNotContain("QUEUE_TOKEN_SECRET")
                .doesNotContain("ADMISSION_TOKEN_SECRET_KEY");

        assertThat(compose)
                .contains("./nginx/nginx.conf:/etc/nginx/nginx.conf:ro")
                .contains("./nginx/default.conf:/etc/nginx/conf.d/default.conf:ro")
                .contains("/etc/letsencrypt:/etc/letsencrypt:ro")
                .doesNotContain("./public-state");
    }

    @Test
    void both_application_images_package_the_datadog_agent() {
        String apiDockerfile = read(API_DOCKERFILE);
        String schedulerDockerfile = read(SCHEDULER_DOCKERFILE);

        assertThat(apiDockerfile)
                .contains("ARG DD_JAVA_AGENT_VERSION=")
                .contains("/opt/datadog/dd-java-agent.jar")
                .contains("COPY build/libs/*.jar app.jar")
                .contains("EXPOSE 8090");
        assertThat(schedulerDockerfile)
                .contains("ARG DD_JAVA_AGENT_VERSION=")
                .contains("/opt/datadog/dd-java-agent.jar")
                .contains("COPY build/libs/*.jar app.jar")
                .contains("EXPOSE 8091");
    }

    @Test
    void compose_wires_separate_observability_for_api_and_scheduler() {
        String compose = read(COMPOSE_CONFIG);

        assertThat(compose)
                .contains("DD_SERVICE: ticket-queue-api")
                .contains("DD_SERVICE: ticket-queue-scheduler")
                .contains("service\":\"ticket-queue-api")
                .contains("service\":\"ticket-queue-scheduler")
                .contains("http://%%host%%:8090/actuator/prometheus")
                .contains("http://%%host%%:8091/actuator/prometheus")
                .contains("namespace\":\"ticket_queue_api")
                .contains("namespace\":\"ticket_queue_scheduler")
                .contains("DD_APM_ENABLED: \"true\"")
                .contains("DD_LOGS_ENABLED: \"true\"");
    }

    @Test
    void compose_runs_one_private_redis_shared_by_api_and_scheduler() {
        String compose = read(COMPOSE_CONFIG);

        assertThat(compose)
                .contains("image: redis:7-alpine")
                .contains("container_name: ticket-queue-redis")
                .contains("redis-data:/data")
                .contains("command: redis-server --appendonly no")
                .contains("condition: service_healthy")
                .contains("SPRING_DATA_REDIS_HOST: redis")
                .contains("SPRING_DATA_REDIS_PORT: \"6379\"")
                .doesNotContain("\"6379:6379\"")
                .doesNotContain("ticket-managed-redis");
    }

    @Test
    void deploy_workflow_builds_and_deploys_both_sha_pinned_images() {
        String workflow = read(DEPLOY_WORKFLOW);

        assertThat(workflow)
                .contains(":queue-api:bootJar")
                .contains(":queue-scheduler:bootJar")
                .contains("context: ./queue-api")
                .contains("file: ./queue-api/Dockerfile")
                .contains("context: ./queue-scheduler")
                .contains("file: ./queue-scheduler/Dockerfile")
                .contains("/ticket-queue-api:${{ github.sha }}")
                .contains("/ticket-queue-scheduler:${{ github.sha }}")
                .contains("API_IMAGE=\"${{ secrets.DOCKER_USERNAME }}/ticket-queue-api:${{ github.sha }}\"")
                .contains("SCHEDULER_IMAGE=\"${{ secrets.DOCKER_USERNAME }}/ticket-queue-scheduler:${{ github.sha }}\"")
                .contains("QUEUE_API_DOCKER_IMAGE=\"$API_IMAGE\"")
                .contains("QUEUE_SCHEDULER_DOCKER_IMAGE=\"$SCHEDULER_IMAGE\"")
                .contains("DEPLOY_DIR=\"/home/ubuntu/ticket-queue\"")
                .contains("environment: aws-queue")
                .doesNotContain("DOCKER_IMAGE=\"$IMAGE\"")
                .doesNotContain("AZURE_VM");
    }

    @Test
    void application_configs_have_independent_names_ports_and_secrets() {
        String api = read(API_APPLICATION_CONFIG);
        String scheduler = read(SCHEDULER_APPLICATION_CONFIG);

        assertThat(api)
                .contains("port: 8090")
                .contains("name: ticket-queue-api")
                .contains("service: ${DD_SERVICE:ticket-queue-api}")
                .contains("secret-key: ${JWT_SECRET}")
                .contains("secret-key: ${ADMISSION_TOKEN_SECRET_KEY}")
                .contains("queue-token-secret: ${QUEUE_TOKEN_SECRET}");

        assertThat(scheduler)
                .contains("port: 8091")
                .contains("name: ticket-queue-scheduler")
                .contains("service: ${DD_SERVICE:ticket-queue-scheduler}")
                .contains("advance-interval-ms: ${QUEUE_ADVANCE_INTERVAL_MS:1000}")
                .doesNotContain("JWT_SECRET")
                .doesNotContain("ADMISSION_TOKEN_SECRET_KEY")
                .doesNotContain("QUEUE_TOKEN_SECRET");
    }

    @Test
    void env_example_documents_both_images_and_the_scheduler_policy() {
        String env = read(ENV_EXAMPLE);

        assertThat(env)
                .contains("QUEUE_API_DOCKER_IMAGE=your-dockerhub-user/ticket-queue-api:latest")
                .contains("QUEUE_SCHEDULER_DOCKER_IMAGE=your-dockerhub-user/ticket-queue-scheduler:latest")
                .contains("QUEUE_MAX_ACTIVE_SESSIONS_PER_PERFORMANCE=5000")
                .contains("QUEUE_MAX_ADMIT_PER_SECOND_PER_PERFORMANCE=500")
                .contains("QUEUE_ADVANCE_INTERVAL_MS=1000")
                .contains("JWT_SECRET=replace-with-core-access-token-secret-32-byte-minimum")
                .contains("QUEUE_TOKEN_SECRET=replace-with-32-byte-minimum-secret")
                .contains("ADMISSION_TOKEN_SECRET_KEY=replace-with-32-byte-minimum-secret")
                .doesNotContain("APP_QUEUE_SCHEDULER_ENABLED")
                .doesNotContain("DOCKER_IMAGE=your-dockerhub-user/ticket-queue:latest");
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
                .contains("service:ticket-queue-redis")
                .doesNotContain("username:")
                .doesNotContain("password:");
    }

    @Test
    void local_compose_runs_only_redis() {
        String localCompose = read(LOCAL_COMPOSE_CONFIG);

        assertThat(localCompose)
                .contains("container_name: ticket-queue-local-redis")
                .contains("\"6379:6379\"")
                .doesNotContain("datadog-agent")
                .doesNotContain("QUEUE_API_DOCKER_IMAGE")
                .doesNotContain("QUEUE_SCHEDULER_DOCKER_IMAGE")
                .doesNotContain("\n  queue:")
                .doesNotContain("\n  scheduler:");
    }

    private String serviceSection(final String compose, final String service, final String nextService) {
        String startMarker = "\n  " + service + ":";
        String endMarker = "\n  " + nextService + ":";
        int start = compose.indexOf(startMarker);
        int end = compose.indexOf(endMarker, start + startMarker.length());
        assertThat(start).isGreaterThanOrEqualTo(0);
        assertThat(end).isGreaterThan(start);
        return compose.substring(start, end);
    }

    private String read(final Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }
}
