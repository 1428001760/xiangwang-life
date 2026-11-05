package com.xiangwang;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import java.util.Set;
@SpringBootTest
public class ClearRedisTokenTest {

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Test
    public void clearAllLoginTokens() {
        // 1. 获取所有 login: 开头的key（测试环境用keys完全没问题）
        Set<String> keys = stringRedisTemplate.keys("login:*");

        if (keys == null || keys.isEmpty()) {
            System.out.println("✅ Redis里没有找到Token，不用删除！");
            return;
        }

        // 2. 批量删除所有key
        stringRedisTemplate.delete(keys);
        System.out.println("✅ 成功删除 " + keys.size() + " 个Token！");
    }
}