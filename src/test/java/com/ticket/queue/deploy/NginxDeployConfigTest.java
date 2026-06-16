package com.ticket.queue.deploy;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NginxDeployConfigTest {

    @Test
    void cachesQueueServerStateApiAtNginx() throws IOException {
        final String nginx = Files.readString(Path.of("deploy/nginx/default.conf"));

        assertTrue(nginx.contains("location ~ ^/api/v1/queue/performances/[0-9]+/state$"));
        assertTrue(nginx.contains("proxy_pass http://queue:8090;"));
        assertTrue(nginx.contains("proxy_hide_header Cache-Control;"));
        assertTrue(nginx.contains("add_header Cache-Control \"public, max-age=1, s-maxage=1, stale-while-revalidate=5\" always;"));
        assertFalse(nginx.contains("location /queue-state/"));
        assertFalse(nginx.contains("try_files $uri =404;"));
    }

    @Test
    void doesNotMountStaticPublicStateDirectory() throws IOException {
        final String compose = Files.readString(Path.of("deploy/docker-compose.yml"));

        assertFalse(compose.contains("./public-state:/app/build/public-state"));
        assertFalse(compose.contains("./public-state:/usr/share/nginx/html:ro"));
    }
}
