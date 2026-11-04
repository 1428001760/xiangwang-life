-- 1.参数列表
-- 1.1.优惠券id
local voucherId = ARGV[1]
-- 1.2.用户id
local userId = ARGV[2]
-- 1.3.订单id
local orderId = ARGV[3]

-- 2.数据key
-- 2.1.库存key
local stockKey = 'seckill:stock:' .. voucherId
-- 2.2.订单key
local orderKey = 'seckill:order:' .. voucherId

-- 3.脚本业务 ===================== 这里修改了 =====================
-- 3.1.获取库存
local stock = redis.call('get', stockKey)
-- 3.2.判断：库存不存在 OR 库存不足
if not stock or tonumber(stock) <= 0 then
    -- 库存不足，返回1
    return 1
end
-- ==============================================================

-- 3.3.判断用户是否下单 SISMEMBER orderKey userId
if(redis.call('sismember', orderKey, userId) == 1) then
    -- 3.4.存在，说明是重复下单，返回2
    return 2
end
-- 3.5.扣减库存 incrby stockKey -1
redis.call('incrby', stockKey, -1)
-- 3.6.下单（保存用户）sadd orderKey userId
redis.call('sadd', orderKey, userId)

-- 3.7.MQ 版本：不再在 Lua 中投递消息
--     原因：Lua 只能操作 Redis，无法直接向 RabbitMQ 发消息
--     改为「Lua 原子扣减成功后，由 Java 侧发送 MQ 消息」
--     若 MQ 发送失败，Java 侧回滚库存与下单标记（见 seckillVoucher）
return 0