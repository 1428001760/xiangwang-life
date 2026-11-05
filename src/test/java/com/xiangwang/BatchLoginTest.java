package com.xiangwang;

import com.xiangwang.entity.User;
import com.xiangwang.mapper.UserMapper;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@SpringBootTest
public class BatchLoginTest {

    @Resource
    private UserMapper userMapper;
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    // 黑马点评原生常量
    private static final String LOGIN_USER_KEY = "login:";
    private static final Integer LOGIN_USER_TTL = 1800; // 30分钟

    @Test
    void batchLogin() {
        // 1. 查询所有用户
        List<User> users = userMapper.selectList(null);
        System.out.println("用户总数：" + users.size());

        // 2. 原生逻辑：存 用户ID 到Redis（项目本来就是这么存的！）
        for (User user : users) {
            String token = UUID.randomUUID().toString().replace("-", "");
            String key = LOGIN_USER_KEY + token;
            // 🔥 核心：只存用户ID，完美匹配拦截器，绝不报错！
            stringRedisTemplate.opsForValue().set(key, user.getId().toString(), LOGIN_USER_TTL, TimeUnit.SECONDS);
            System.out.println("token = " + token);
        }
        System.out.println("✅ 批量登录生成完成！");
    }
}