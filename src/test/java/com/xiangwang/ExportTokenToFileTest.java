package com.xiangwang;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.util.Set;

@SpringBootTest
public class ExportTokenToFileTest {

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Test
    public void exportTokensToFile() throws IOException {
        // 🔥 固定路径：D:\develop\tokens.txt
        String filePath = "D:/develop/tokens.txt";

        // 读取Redis所有登录Token
        Set<String> keys = stringRedisTemplate.keys("login:*");
        if (keys == null || keys.isEmpty()) {
            System.out.println("❌ Redis无Token，请先运行步骤2！");
            return;
        }

        // 写入文件
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(filePath))) {
            for (String key : keys) {
                String token = key.replace("login:", "");
                writer.write(token);
                writer.newLine();
            }
        }

        System.out.println("✅ 文件导出成功！");
        System.out.println("✅ 总Token数：" + keys.size());
        System.out.println("✅ 文件位置：" + filePath);
    }
}