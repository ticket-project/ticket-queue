package com.ticket.queue.infra;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.redisson.config.Config;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RedissonConfig {

    private static final String REDIS_SCHEME = "redis://";

    private final String redisHost;
    private final int redisPort;

    public RedissonConfig(
            @Value("${spring.data.redis.host:localhost}") final String redisHost,
            @Value("${spring.data.redis.port:6379}") final int redisPort) {
        this.redisHost = redisHost;
        this.redisPort = redisPort;
    }

    @Bean(destroyMethod = "shutdown")
    public RedissonClient redissonClient() {
        return Redisson.create(buildConfig());
    }

    Config buildConfig() {
        Config config = new Config();
        config.setCodec(StringCodec.INSTANCE);
        config.useSingleServer().setAddress(REDIS_SCHEME + redisHost + ":" + redisPort);
        return config;
    }
}
