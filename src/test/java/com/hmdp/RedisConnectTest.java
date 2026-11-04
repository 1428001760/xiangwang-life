package com.hmdp;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import jakarta.annotation.Resource;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
public class RedisConnectTest {
    // 注入你的3个客户端
    @Resource
    private RedissonClient redissonClient;
    @Resource
    private RedissonClient redissonClient2;
    @Resource
    private RedissonClient redissonClient3;

    // 测试3个Redis是否连通
    @Test
    void testConnect(){
        // 测试节点1 (6379)
        System.out.println("节点1 连通状态：" + redissonClient.getNodesGroup().pingAll());
        // 测试节点2 (6380)
        System.out.println("节点2 连通状态：" + redissonClient2.getNodesGroup().pingAll());
        // 测试节点3 (6381)
        System.out.println("节点3 连通状态：" + redissonClient3.getNodesGroup().pingAll());
    }
}