package com.hmdp.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Redisson 多节点配置
 *
 * 三个客户端分别连接 6379 / 6380 / 6381，用于演示 RedissonMultiLock 联锁。
 * 地址与密码全部从配置文件读取，便于在不同环境切换，避免硬编码。
 */
@Configuration
public class RedissonConfig {

    @Value("${spring.data.redis.host:localhost}")
    private String redisHost;

    @Value("${spring.data.redis.password:}")
    private String redisPassword;

    @Value("${redisson.node1-port:6379}")
    private int node1Port;

    @Value("${redisson.node2-port:6380}")
    private int node2Port;

    @Value("${redisson.node3-port:6381}")
    private int node3Port;

    private Config buildConfig(int port) {
        Config config = new Config();
        config.useSingleServer()
                .setAddress("redis://" + redisHost + ":" + port)
                .setPassword(redisPassword == null || redisPassword.isEmpty() ? null : redisPassword)
                .setDatabase(0);
        return config;
    }

    @Bean
    public RedissonClient redissonClient() {
        return Redisson.create(buildConfig(node1Port));
    }

    @Bean
    public RedissonClient redissonClient2() {
        return Redisson.create(buildConfig(node2Port));
    }

    @Bean
    public RedissonClient redissonClient3() {
        return Redisson.create(buildConfig(node3Port));
    }
}
