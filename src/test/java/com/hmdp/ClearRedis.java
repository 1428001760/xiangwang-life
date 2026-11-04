package com.hmdp;

import jakarta.annotation.Resource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

@SpringBootTest
public class ClearRedis {
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Test
    void clear() {
        stringRedisTemplate.delete(stringRedisTemplate.keys("login:*"));
        System.out.println("🗑️ 清空登录token完成");
    }
}