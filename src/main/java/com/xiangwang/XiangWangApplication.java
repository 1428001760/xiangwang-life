package com.xiangwang;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
@EnableAspectJAutoProxy(exposeProxy = true)
@MapperScan("com.xiangwang.mapper")
@SpringBootApplication
public class XiangWangApplication {
    public static void main(String[] args) {
        SpringApplication.run(XiangWangApplication.class, args);
    }
}