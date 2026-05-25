package com.ticket.queue.infra;

import java.util.Arrays;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.redisson.config.Config;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

@Configuration
public class RedissonConfig {

    private static final String REDIS_SCHEME = "redis://";
    private static final String REDISS_SCHEME = "rediss://";

    @Value("${spring.data.redis.host:localhost}")
    private String redisHost;

    @Value("${spring.data.redis.port:6379}")
    private int redisPort;

    @Value("${spring.data.redis.cluster.nodes:}")
    private String clusterNodes;

    @Bean(destroyMethod = "shutdown")
    public RedissonClient redissonClient() {
        Config config = new Config();
        config.setCodec(StringCodec.INSTANCE);

        if (StringUtils.hasText(clusterNodes)) {
            String[] nodes = Arrays.stream(clusterNodes.split(","))
                    .map(String::trim)
                    .filter(StringUtils::hasText)
                    .map(this::toRedisAddress)
                    .toArray(String[]::new);
            config.useClusterServers().addNodeAddress(nodes);
            return Redisson.create(config);
        }

        config.useSingleServer().setAddress(toRedisAddress(redisHost + ":" + redisPort));
        return Redisson.create(config);
    }

    private String toRedisAddress(final String address) {
        if (address.startsWith(REDIS_SCHEME) || address.startsWith(REDISS_SCHEME)) {
            return address;
        }
        return REDIS_SCHEME + address;
    }
}
