package com.xiangwang.service.impl;

import com.xiangwang.dto.Result;
import com.xiangwang.entity.VoucherOrder;
import com.xiangwang.mapper.VoucherOrderMapper;
import com.xiangwang.service.ISeckillVoucherService;
import com.xiangwang.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.xiangwang.utils.RedisIdWorker;
import com.xiangwang.utils.UserHolder;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.concurrent.TimeUnit;

import static com.xiangwang.config.RabbitMQConfig.SECKILL_EXCHANGE;
import static com.xiangwang.config.RabbitMQConfig.SECKILL_ROUTING_KEY;
import static com.xiangwang.utils.RedisConstants.LOCK_ORDER_KEY;

/**
 * 秒杀订单服务 —— RabbitMQ 异步削峰版本
 *
 * 整体链路：
 *   1. Lua 脚本原子校验库存 + 一人一单 + 扣减（Redis 层挡住绝大部分流量）
 *   2. 扣减成功后，向 RabbitMQ 投递订单消息（异步，不阻塞用户请求）
 *   3. 消费者从队列拉取消息，加分布式锁后落库
 *   4. 落库失败消息进入死信队列，人工兜底
 *
 * 为什么不用 Redis Stream 了：
 *   Stream 的消费组和 ACK 机制够用，但生产环境更倾向 RabbitMQ：
 *   - 死信队列、延迟队列、消息 TTL 开箱即用
 *   - 可视化管控台，消息积压一眼可见
 *   - 与公司现有运维体系（监控、告警）打通更成熟
 */
@Slf4j
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;
    @Resource
    private RedisIdWorker redisIdWorker;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private RedissonClient redissonClient;
    @Resource
    private RabbitTemplate rabbitTemplate;
    /** 注入自身代理，保证 @Transactional 生效 */
    @Resource
    private IVoucherOrderService self;

    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;

    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    /**
     * 秒杀下单接口
     *
     * 执行流程：
     *   1. 生成全局唯一订单 ID（Redis 自增）
     *   2. 执行 Lua：库存校验 + 一人一单校验 + 扣减（原子）
     *   3. Lua 返回 0 表示抢购成功，投递 MQ 消息
     *   4. MQ 投递失败 → 回滚 Redis 中的库存与下单标记（关键补偿）
     */
    @Override
    public Result seckillVoucher(Long voucherId) {
        Long userId = UserHolder.getUser().getId();
        long orderId = redisIdWorker.nextId("order");

        // 1. 执行 Lua 脚本：原子扣减库存 + 一人一单校验
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(),
                userId.toString(),
                String.valueOf(orderId)
        );

        int r = result.intValue();
        if (r != 0) {
            return Result.fail(r == 1 ? "库存不足" : "不能重复下单");
        }

        // 2. 扣减成功，投递 MQ 消息异步落库
        VoucherOrder voucherOrder = new VoucherOrder()
                .setId(orderId)
                .setUserId(userId)
                .setVoucherId(voucherId);

        try {
            rabbitTemplate.convertAndSend(SECKILL_EXCHANGE, SECKILL_ROUTING_KEY, voucherOrder);
            log.info("秒杀成功，订单已投递MQ，订单ID：{}，用户ID：{}", orderId, userId);
        } catch (Exception e) {
            // ============ 关键补偿逻辑 ============
            // MQ 投递失败，必须回滚 Redis 中已扣减的库存与下单标记，
            // 否则会出现「库存扣了但订单没生成」的数据不一致问题
            log.error("MQ 投递失败，开始回滚 Redis 库存，订单ID：{}", orderId, e);
            rollbackSeckillStock(voucherId, userId);
            return Result.fail("系统繁忙，请稍后重试");
        }

        return Result.ok(orderId);
    }

    /**
     * 回滚 Redis 中的秒杀库存与下单标记
     *
     * 使用 Lua 保证两个操作的原子性：
     *   - 库存 +1
     *   - 从已下单集合移除该用户
     */
    private static final String ROLLBACK_LUA =
            "redis.call('incrby', KEYS[1], 1);" +
            "redis.call('srem', KEYS[2], ARGV[1]);" +
            "return 1;";

    private void rollbackSeckillStock(Long voucherId, Long userId) {
        try {
            String stockKey = "seckill:stock:" + voucherId;
            String orderKey = "seckill:order:" + voucherId;
            DefaultRedisScript<Long> script = new DefaultRedisScript<>(ROLLBACK_LUA, Long.class);
            stringRedisTemplate.execute(script,
                    java.util.Arrays.asList(stockKey, orderKey),
                    userId.toString());
            log.info("Redis 库存回滚成功，voucherId：{}，userId：{}", voucherId, userId);
        } catch (Exception ex) {
            // 回滚也失败属于极端情况，记录告警日志，由定时任务对账修复
            log.error("!!! Redis 库存回滚失败，需人工介入，voucherId：{}，userId：{}",
                    voucherId, userId, ex);
        }
    }

    /**
     * 处理订单落库（由 MQ 消费者调用）
     *
     * 加分布式锁的原因：
     *   同一个用户可能有多个并发请求到达（尽管 Lua 已挡住大部分），
     *   数据库层面仍需保证「一人一单」不被并发击穿。
     *   这里用 Redisson 锁，粒度是 userId，避免锁整个订单表。
     */
    @Override
    public void handleVoucherOrder(VoucherOrder voucherOrder) {
        Long userId = voucherOrder.getUserId();
        RLock lock = redissonClient.getLock(LOCK_ORDER_KEY + userId);
        boolean isLock = lock.tryLock();

        if (!isLock) {
            log.warn("获取锁失败，可能存在重复消费，订单ID：{}", voucherOrder.getId());
            return;
        }

        try {
            self.createVoucherOrder(voucherOrder);
        } finally {
            lock.unlock();
        }
    }

    /**
     * 订单落库（事务方法）
     *
     * 三层兜底：
     *   1. 应用层：查询是否已下单（防重）
     *   2. DB 层：库存扣减带 stock > 0 条件（防超卖）
     *   3. 建议加唯一索引 (user_id, voucher_id) 作为最后一道防线
     */
    @Transactional
    @Override
    public void createVoucherOrder(VoucherOrder voucherOrder) {
        // 1. 防重：查该用户是否已购买该券
        Long count = query().eq("user_id", voucherOrder.getUserId())
                .eq("voucher_id", voucherOrder.getVoucherId()).count();
        if (count > 0) {
            log.warn("用户已购买，订单ID：{}", voucherOrder.getId());
            return;
        }

        // 2. 扣库存：where stock > 0 保证不会超卖
        boolean success = seckillVoucherService.update()
                .setSql("stock = stock - 1")
                .eq("voucher_id", voucherOrder.getVoucherId())
                .gt("stock", 0)
                .update();
        if (!success) {
            log.warn("库存不足，订单ID：{}", voucherOrder.getId());
            return;
        }

        // 3. 保存订单
        save(voucherOrder);
        log.info("订单落库成功，订单ID：{}", voucherOrder.getId());
    }

    /**
     * 旧版同步下单方法（保留用于对比 / 降级）
     */
    @Override
    @Transactional
    public Result createVoucherOrder(Long voucherId) {
        Long userId = UserHolder.getUser().getId();
        Long count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
        if (count > 0) return Result.fail("用户已购买");

        boolean success = seckillVoucherService.update()
                .setSql("stock = stock - 1")
                .eq("voucher_id", voucherId).gt("stock", 0).update();
        if (!success) return Result.fail("库存不足");

        VoucherOrder order = new VoucherOrder();
        order.setId(redisIdWorker.nextId("order"));
        order.setUserId(userId);
        order.setVoucherId(voucherId);
        save(order);
        return Result.ok(order.getId());
    }
}
