package com.ticket.queue.infra;

import java.util.Arrays;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.redisson.config.BaseConfig;
import org.redisson.config.ClusterServersConfig;
import org.redisson.config.Config;
import org.redisson.config.SingleServerConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

@Configuration
public class RedissonConfig {

    private static final String REDIS_SCHEME = "redis://";
    private static final String REDISS_SCHEME = "rediss://";

    private final String redisHost;
    private final int redisPort;
    private final String clusterNodes;
    private final String redisUsername;
    private final String redisPassword;
    private final boolean redisSslEnabled;

    public RedissonConfig(
            @Value("${spring.data.redis.host:localhost}") final String redisHost,
            @Value("${spring.data.redis.port:6379}") final int redisPort,
            @Value("${spring.data.redis.cluster.nodes:}") final String clusterNodes,
            @Value("${spring.data.redis.username:}") final String redisUsername,
            @Value("${spring.data.redis.password:}") final String redisPassword,
            @Value("${spring.data.redis.ssl.enabled:false}") final boolean redisSslEnabled) {
        this.redisHost = redisHost;
        this.redisPort = redisPort;
        this.clusterNodes = clusterNodes;
        this.redisUsername = redisUsername;
        this.redisPassword = redisPassword;
        this.redisSslEnabled = redisSslEnabled;
    }

    @Bean(destroyMethod = "shutdown")
    public RedissonClient redissonClient() {
        return Redisson.create(buildConfig());
    }

    Config buildConfig() {
        Config config = new Config();
        config.setCodec(StringCodec.INSTANCE);

        if (StringUtils.hasText(clusterNodes)) {
            String[] nodes = Arrays.stream(clusterNodes.split(","))
                    .map(String::trim)
                    .filter(StringUtils::hasText)
                    .map(this::toRedisAddress)
                    .toArray(String[]::new);
            ClusterServersConfig cluster = config.useClusterServers().addNodeAddress(nodes);
            applyCredentials(cluster);
            cluster.setSslEnableEndpointIdentification(usesTls(nodes));
            return config;
        }

        SingleServerConfig single = config.useSingleServer()
                .setAddress(toRedisAddress(redisHost + ":" + redisPort));
        applyCredentials(single);
        single.setSslEnableEndpointIdentification(usesTls(single.getAddress()));
        return config;
    }

    private String toRedisAddress(final String address) {
        if (address.startsWith(REDIS_SCHEME) || address.startsWith(REDISS_SCHEME)) {
            return address;
        }
        return redisSslEnabled ? REDISS_SCHEME + address : REDIS_SCHEME + address;
    }

    private <T extends BaseConfig<T>> void applyCredentials(final T serverConfig) {
        if (StringUtils.hasText(redisUsername)) {
            serverConfig.setUsername(redisUsername);
        }
        if (StringUtils.hasText(redisPassword)) {
            serverConfig.setPassword(redisPassword);
        }
    }

    private boolean usesTls(final String... addresses) {
        return Arrays.stream(addresses).anyMatch(address -> address.startsWith(REDISS_SCHEME));
    }
}
