package com.hmdp.mq;

import com.hmdp.config.RabbitMQConfig;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.service.IVoucherOrderService;
import com.rabbitmq.client.Channel;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * 秒杀订单 MQ 消费者
 *
 * 面试重点：
 * 1. 为什么手动 ACK？
 *    自动 ACK 模式下，消息一到消费者手里就认为消费成功。
 *    如果处理过程中抛异常或服务宕机，消息就丢了。
 *    手动 ACK 保证「业务处理成功才确认」，失败时消息重回队列。
 *
 * 2. 为什么要限制重试次数？
 *    如果消息本身有脏数据（比如 voucherId 不存在），
 *    无限重试会导致消息在队列里反复打转，阻塞正常消息。
 *    重试 3 次仍失败 → 进入死信队列 → 人工排查。
 *
 * 3. 为什么消费者是单线程？
 *    RabbitMQ 默认给每个消费者单线程消费。
 *    秒杀场景下单量虽大，但落库是数据库操作，
 *    盲目提高并发反而打垮数据库。要提升吞吐应加消费者实例（水平扩展），
 *    而不是在单实例里开线程池。
 */
@Slf4j
@Component
public class SeckillOrderConsumer {

    @Resource
    private IVoucherOrderService voucherOrderService;

    /** 最大重试次数，超过则进入死信队列 */
    private static final int MAX_RETRY = 3;

    /**
     * 监听秒杀订单队列
     *
     * @param voucherOrder MQ 中的订单消息（JSON 反序列化后的对象）
     */
    @RabbitListener(queues = RabbitMQConfig.SECKILL_QUEUE)
    public void onMessage(VoucherOrder voucherOrder, Channel channel,
                          @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag) throws IOException {
        log.info("收到秒杀订单消息：订单ID={}，用户ID={}，优惠券ID={}",
                voucherOrder.getId(), voucherOrder.getUserId(), voucherOrder.getVoucherId());

        int retry = 0;
        while (retry < MAX_RETRY) {
            try {
                // 核心落库逻辑：加分布式锁 + 事务
                voucherOrderService.handleVoucherOrder(voucherOrder);
                log.info("订单处理成功，订单ID：{}", voucherOrder.getId());
                // 手动 ACK：业务处理成功后确认消息，RabbitMQ 才会删除该消息
                channel.basicAck(deliveryTag, false);
                return;
            } catch (Exception e) {
                retry++;
                log.error("订单处理失败，第 {} 次重试，订单ID：{}", retry, voucherOrder.getId(), e);
                if (retry >= MAX_RETRY) {
                    // 重试耗尽：拒绝消息且不重回队列 → 进入死信队列（由 DLX 配置接管）
                    log.error("订单处理重试 {} 次仍失败，转入死信队列，订单ID：{}",
                            MAX_RETRY, voucherOrder.getId());
                    channel.basicNack(deliveryTag, false, false);
                    return;
                }
                // 指数退避，避免瞬时故障导致连续失败
                try {
                    Thread.sleep(200L * retry);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    /**
     * 死信队列监听 —— 兜底处理
     *
     * 进入这里的消息意味着：
     *   - 重试 3 次仍失败
     *   - 或消息 TTL 过期未被消费
     *
     * 生产环境的做法：
     *   1. 记录到数据库或告警系统
     *   2. 人工排查后决定重投或退款
     *   3. 绝不能静默丢弃
     */
    @RabbitListener(queues = RabbitMQConfig.DLX_QUEUE)
    public void onDeadLetter(VoucherOrder voucherOrder) {
        log.error("【死信告警】订单消息进入死信队列，需人工介入！订单ID={}，用户ID={}，优惠券ID={}",
                voucherOrder.getId(), voucherOrder.getUserId(), voucherOrder.getVoucherId());
        // TODO 生产环境：写入告警表 / 发送企业微信通知 / 触发对账任务
    }
}
