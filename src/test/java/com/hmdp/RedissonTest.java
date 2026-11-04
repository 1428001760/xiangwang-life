package com.hmdp;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import jakarta.annotation.Resource;
import org.springframework.boot.test.context.SpringBootTest;
import java.util.concurrent.TimeUnit;
/**
 * 老师原版Redisson可重入锁 + 连锁(MultiLock)
 */
@SpringBootTest
public class RedissonTest {
    // 日志对象
    private static final Logger log = LoggerFactory.getLogger(RedissonTest.class);
    //  老师原版注入
    @Resource
    private RedissonClient redissonClient;
    @Resource
    private RedissonClient redissonClient2;
    @Resource
    private RedissonClient redissonClient3;
    // 锁对象
    private RLock lock;

    // 老师原版初始化
    @BeforeEach
    void setUp() {
        // 1. 获取3个节点的锁
        RLock lock1 = redissonClient.getLock("order");
        RLock lock2 = redissonClient2.getLock("order");
        RLock lock3 = redissonClient3.getLock("order");
        // 2. 老师核心：MultiLock 连锁（最简单！）
        lock = redissonClient.getMultiLock(lock1, lock2, lock3);
    }
    // 老师原版测试方法 + 60秒超长等待（足够你Debug观察）
    @Test
    void method1() throws InterruptedException {
        // 尝试加锁
        boolean isLock = lock.tryLock(1L, TimeUnit.SECONDS);
        if (!isLock) {
            log.error("获取锁失败 .... 1");
            return;
        }
        try {
            log.info("获取锁成功 .... 1");
            // ===================== 超长等待 60 秒！ =====================
            Thread.sleep(1L);

            // 可重入测试
            method2();
            log.info("开始执行业务 .... 1");
        } finally {
            log.warn("准备释放锁 .... 1");
            lock.unlock();
        }
    }

    // 原版可重入方法
    void method2() {
        boolean isLock = lock.tryLock();
        if (!isLock) {
            log.error("获取锁失败 .... 2");
            return;
        }
        try {
            log.info("获取锁成功 .... 2");
            log.info("开始执行业务 .... 2");
        } finally {
            log.warn("准备释放锁 .... 2");
            lock.unlock();
        }
    }
}