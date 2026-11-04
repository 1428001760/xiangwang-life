package com.hmdp.utils;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import java.util.concurrent.TimeUnit;
public class RedissonLock implements ILock {
    private final String name;
    private final RedissonClient redissonClient;
    private static final String KEY_PREFIX = "lock:";
    public RedissonLock(String name, RedissonClient redissonClient) {
        this.name = name;
        this.redissonClient = redissonClient;
    }
    @Override
    public boolean tryLock(long timeoutSec) {
        RLock lock = redissonClient.getLock(KEY_PREFIX+name);
        try {
            return lock.tryLock(0, timeoutSec, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            return false;
        }
    }
    // 老师要求的无参tryLock()
    public boolean tryLock() {
        return tryLock(30);
    }

    @Override
    public void unlock() {
        RLock lock = redissonClient.getLock(KEY_PREFIX+name);
        if (lock.isHeldByCurrentThread()) {
            lock.unlock();
        }
    }
}