package com.hmdp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@SpringBootTest
public class BatchLoginFinalTest {

    @Resource
    private UserMapper userMapper;
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    // 黑马点评项目固定常量（和你登录拦截器完全一致）
    private static final String LOGIN_USER_KEY = "login:";
    private static final Integer LOGIN_USER_TTL = 30; // 30分钟过期

    // Spring自带的Jackson序列化工具（项目里默认有，不用额外依赖）
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void batchLoginAllUsers() throws JsonProcessingException {
        // 1. 查询数据库所有用户
        List<User> users = userMapper.selectList(null);
        System.out.println("📊 数据库总用户数：" + users.size());

        // 2. 100%还原原登录逻辑：生成Token → 序列化User对象存入Redis
        for (User user : users) {
            // 生成无横杠的唯一Token（修正UUID报错）
            String token = UUID.randomUUID().toString().replace("-", "");
            // Redis key：login:token
            String key = LOGIN_USER_KEY + token;
            // 关键：用Jackson序列化User对象为JSON字符串（替换fastjson，解决依赖问题）
            String userJson = objectMapper.writeValueAsString(user);
            // 存入Redis，设置30分钟过期
            stringRedisTemplate.opsForValue().set(key, userJson, LOGIN_USER_TTL, TimeUnit.MINUTES);
            System.out.println("✅ 生成Token成功：" + token + "  用户手机号：" + user.getPhone());
        }

        System.out.println("\n🎉 所有用户Token生成完成！格式与原登录接口完全一致！");
    }
}