package com.hmdp.config;

import org.springframework.amqp.core.*;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.HashMap;
import java.util.Map;

/**
 * RabbitMQ 配置 —— 秒杀订单异步削峰
 *
 * 设计要点：
 * 1. 使用 Direct 交换机（路由键精确匹配，性能最好，秒杀场景足够）
 * 2. 队列开启持久化，防止 Broker 重启丢消息
 * 3. 配置死信队列（DLX），消费失败的消息进入死信队列人工兜底
 * 4. 消息使用 JSON 序列化，方便排查
 */
@Configuration
public class RabbitMQConfig {

    /** 秒杀订单交换机 */
    public static final String SECKILL_EXCHANGE = "seckill.exchange";
    /** 秒杀订单队列 */
    public static final String SECKILL_QUEUE = "seckill.order.queue";
    /** 秒杀订单路由键 */
    public static final String SECKILL_ROUTING_KEY = "seckill.order";

    /** 死信交换机 */
    public static final String DLX_EXCHANGE = "seckill.dlx.exchange";
    /** 死信队列 */
    public static final String DLX_QUEUE = "seckill.order.dlx.queue";
    /** 死信路由键 */
    public static final String DLX_ROUTING_KEY = "seckill.order.dlx";

    /**
     * 秒杀订单交换机（持久化、不自动删除）
     */
    @Bean
    public DirectExchange seckillExchange() {
        return ExchangeBuilder.directExchange(SECKILL_EXCHANGE)
                .durable(true)
                .build();
    }

    /**
     * 秒杀订单队列
     * 关键配置：
     * - durable(true)：队列持久化
     * - x-dead-letter-exchange：消费失败后转发到死信交换机
     * - x-message-ttl：消息最多存活 30 分钟，避免积压
     */
    @Bean
    public Queue seckillQueue() {
        Map<String, Object> args = new HashMap<>();
        // 死信交换机：消费重试仍失败时，消息转到这里人工处理
        args.put("x-dead-letter-exchange", DLX_EXCHANGE);
        args.put("x-dead-letter-routing-key", DLX_ROUTING_KEY);
        // 消息 TTL：30 分钟未消费则进入死信队列
        args.put("x-message-ttl", 30 * 60 * 1000);
        return QueueBuilder.durable(SECKILL_QUEUE).withArguments(args).build();
    }

    /**
     * 绑定：交换机 + 路由键 -> 队列
     */
    @Bean
    public Binding seckillBinding() {
        return BindingBuilder.bind(seckillQueue())
                .to(seckillExchange())
                .with(SECKILL_ROUTING_KEY);
    }

    /**
     * 死信交换机
     */
    @Bean
    public DirectExchange dlxExchange() {
        return ExchangeBuilder.directExchange(DLX_EXCHANGE).durable(true).build();
    }

    /**
     * 死信队列：存放处理失败的订单消息
     */
    @Bean
    public Queue dlxQueue() {
        return QueueBuilder.durable(DLX_QUEUE).build();
    }

    /**
     * 死信队列绑定
     */
    @Bean
    public Binding dlxBinding() {
        return BindingBuilder.bind(dlxQueue())
                .to(dlxExchange())
                .with(DLX_ROUTING_KEY);
    }

    /**
     * JSON 消息转换器：订单对象序列化为 JSON，可读性好、跨语言兼容
     */
    @Bean
    public MessageConverter jacksonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }
}
