package com.ticket.queue.infra;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.redisson.config.ClusterServersConfig;
import org.redisson.config.Config;
import org.redisson.config.SingleServerConfig;

class RedissonConfigTest {

    @Test
    void cluster_config_uses_tls_username_and_password_for_managed_redis() {
        RedissonConfig redissonConfig = new RedissonConfig(
                "localhost",
                6379,
                "ticket-managed-redis.southeastasia.redis.azure.net:10000",
                "default",
                "access-key",
                true);

        Config config = redissonConfig.buildConfig();

        assertThat(config.isClusterConfig()).isTrue();

        ClusterServersConfig cluster = config.useClusterServers();
        assertThat(cluster.getNodeAddresses())
                .containsExactly("rediss://ticket-managed-redis.southeastasia.redis.azure.net:10000");
        assertThat(cluster.getUsername()).isEqualTo("default");
        assertThat(cluster.getPassword()).isEqualTo("access-key");
        assertThat(cluster.isSslEnableEndpointIdentification()).isTrue();
    }

    @Test
    void single_server_config_can_apply_password_without_tls_for_local_redis() {
        RedissonConfig redissonConfig = new RedissonConfig(
                "redis",
                6379,
                "",
                "",
                "local-password",
                false);

        Config config = redissonConfig.buildConfig();

        assertThat(config.isSingleConfig()).isTrue();

        SingleServerConfig single = config.useSingleServer();
        assertThat(single.getAddress()).isEqualTo("redis://redis:6379");
        assertThat(single.getUsername()).isNull();
        assertThat(single.getPassword()).isEqualTo("local-password");
    }

    @Test
    void explicit_rediss_node_address_is_preserved() {
        RedissonConfig redissonConfig = new RedissonConfig(
                "localhost",
                6379,
                "rediss://ticket-managed-redis.southeastasia.redis.azure.net:10000",
                "default",
                "access-key",
                true);

        Config config = redissonConfig.buildConfig();

        assertThat(config.useClusterServers().getNodeAddresses())
                .containsExactly("rediss://ticket-managed-redis.southeastasia.redis.azure.net:10000");
    }
}
