package org.example.bookmarket.common.config;

import org.redisson.api.RedissonClient;
import org.redisson.spring.cache.RedissonSpringCacheManager;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.HashMap;
import java.util.Map;

@Configuration
@EnableCaching
public class CacheConfig {

    @Bean
    public CacheManager cacheManager(RedissonClient redissonClient) {
        Map<String, org.redisson.spring.cache.CacheConfig> config = new HashMap<>();


        config.put("categories", new org.redisson.spring.cache.CacheConfig(24 * 60 * 60 * 1000, 12 * 60 * 60 * 1000));

        config.put("ai-suggestions", new org.redisson.spring.cache.CacheConfig(60 * 60 * 1000, 0));

        return new RedissonSpringCacheManager(redissonClient, config);
    }
}