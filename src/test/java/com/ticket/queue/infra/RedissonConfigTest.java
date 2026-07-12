package com.ticket.queue.infra;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.redisson.config.Config;
import org.redisson.config.SingleServerConfig;

class RedissonConfigTest {

    @Test
    void single_server_config_uses_configured_host_and_port() {
        RedissonConfig redissonConfig = new RedissonConfig("redis", 6379);

        Config config = redissonConfig.buildConfig();

        assertThat(config.isSingleConfig()).isTrue();

        SingleServerConfig single = config.useSingleServer();
        assertThat(single.getAddress()).isEqualTo("redis://redis:6379");
        assertThat(single.getUsername()).isNull();
        assertThat(single.getPassword()).isNull();
    }
}
