package com.hmdp.utils;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.core.io.ClassPathResource;
import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
public class SimpleRedisLock implements ILock {
    // 成员变量保持你原来的，加final更规范（解决IDEA黄色警告）
    private final String name;
    private final StringRedisTemplate stringRedisTemplate;
    private static final String KEY_PREFIX = "lock:";
    // UUID兼容写法，所有JDK版本都支持，去掉横杠
    private static final String ID_PREFIX = UUID.randomUUID().toString().replace("-", "") + "-";
    // 静态加载Lua脚本：只加载一次，性能拉满
    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT;
    static {
        UNLOCK_SCRIPT = new DefaultRedisScript<>();
        UNLOCK_SCRIPT.setLocation(new ClassPathResource("unlock.lua"));
        UNLOCK_SCRIPT.setResultType(Long.class);
    }
    // 构造方法完全不动
    public SimpleRedisLock(String name, StringRedisTemplate stringRedisTemplate) {
        this.name = name;
        this.stringRedisTemplate = stringRedisTemplate;
    }
    // tryLock方法完全不动，保持原子加锁
    @Override
    public boolean tryLock(long timeoutSec) {
        String threadId = ID_PREFIX + Thread.currentThread().getId();
        Boolean success = stringRedisTemplate.opsForValue()
                .setIfAbsent(KEY_PREFIX + name, threadId, timeoutSec, TimeUnit.SECONDS);
        return Boolean.TRUE.equals(success);
    }
    // 核心改造：unlock方法换成Lua原子解锁！
    @Override
    public void unlock() {
        String threadId = ID_PREFIX + Thread.currentThread().getId();
        // 调用Lua脚本，原子执行「查锁+删锁」
         stringRedisTemplate.execute(UNLOCK_SCRIPT,Collections.singletonList(KEY_PREFIX+name),threadId);
    }
}