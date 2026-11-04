package com.hmdp.utils;
import jakarta.annotation.Resource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
@Component
public class RedisIdWorker {
    private static final long BEGIN_TIMESTAMP = 1640995200L;
    private static final int COUNT_BITS = 32;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    public long nextId(String keyPrefix) {
        // 1. 当前秒数
        long nowSecond = LocalDateTime.now().toEpochSecond(ZoneOffset.UTC);
        // 2. 计算时间差（正确！）
        long timestamp = nowSecond - BEGIN_TIMESTAMP;
        // 3. 生成当天key
        String date = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy:MM:dd"));
        String key = "incr:" + keyPrefix + ":" + date;
        // 4. Redis自增
        Long count = stringRedisTemplate.opsForValue().increment(key);
        // 5. 拼接ID（✅ 这里必须用 timestamp！！！）
        return timestamp << COUNT_BITS | count;
    }
}