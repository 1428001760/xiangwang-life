# 巷往生活服务平台（hm-dianping）

> 本地生活 O2O 服务平台 · 高并发秒杀场景实践

一个覆盖**商户浏览、探店笔记、优惠券秒杀**的本地生活服务平台后端。项目重点不在业务堆量，而在**把高并发场景下的几个经典难题做对**：热点数据怎么扛、库存怎么不超卖、订单怎么不重复、锁怎么不失效。

---

## 技术栈

| 分类 | 技术 |
| --- | --- |
| 语言 / 运行时 | Java 21 |
| 框架 | Spring Boot 3.1.12、MyBatis-Plus 3.5.5 |
| 存储 | MySQL 8.0、Redis 7 |
| 消息队列 | RabbitMQ 3.13（异步削峰 + 死信兜底） |
| 分布式锁 | Redisson 3.13.6 |
| 部署 | Docker / Docker Compose、Linux systemd |

---

## 核心设计

### 1. 缓存三防：穿透 / 击穿 / 雪崩

不是笼统地"加了 Redis"，而是针对三种失效场景分别下药：

| 问题 | 手段 | 落地位置 |
| --- | --- | --- |
| **穿透**（查不存在的数据打穿到 DB） | 空值缓存 + 短 TTL（2 min），配合 ID 合法性校验 | `ShopServiceImpl#queryById` |
| **击穿**（热点 key 过期瞬间大量请求直达 DB） | 逻辑过期 + 互斥锁重建，只有拿到锁的线程去查库 | `ShopServiceImpl#queryWithLogicalExpire` |
| **雪崩**（大批 key 同时过期） | TTL 加随机抖动 + 热点数据预热 | 缓存写入路径 |

统一使用 `StringRedisTemplate` + 自定义序列化，规避乱码与跨版本兼容问题。

### 2. 秒杀一致性：Lua 原子扣减 + MQ 异步落库

秒杀的核心矛盾是：**Redis 扣减要快，DB 落库要稳**。拆成两段：

```
请求 → [Lua 脚本] 库存校验 + 一人一单校验 + 扣减 + 记录用户  ← 单次原子操作
     → [RabbitMQ] 投递订单消息                                ← 异步削峰
     → [Consumer] 加分布式锁 → 事务落库                        ← 保证最终一致
```

**Lua 脚本为什么必须原子**：如果"查库存 → 判断 → 扣减"分三次网络往返，中间会被其他请求插入，直接超卖。Lua 在 Redis 单线程内执行，天然互斥。

**为什么用 MQ 而不是同步落库**：秒杀瞬时 QPS 远超 DB 写入能力。同步落库会让请求线程全部阻塞在数据库连接上，Tomcat 线程池迅速打满。MQ 把"抢购"和"落库"解耦，前端只需拿到"抢到了"的响应。

### 3. MQ 可靠性：三重保障

消息队列最怕的是**消息丢了却没人知道**。这里做了三层：

1. **持久化**：交换机与队列均 `durable`，Broker 重启不丢
2. **手动 ACK**：`acknowledge-mode: manual`，业务处理成功才 `basicAck`；失败按指数退避重试 3 次，仍失败则 `basicNack` 转入死信队列
3. **死信兜底**：`x-dead-letter-exchange` 配置死信交换机，失败消息进入 `seckill.order.dlx.queue` 触发告警，绝不静默丢弃

**生产者侧的补偿**：Lua 只能操作 Redis，无法直接发 MQ 消息。所以流程是"Lua 扣减成功 → Java 发消息"，这中间存在失败窗口。若投递抛异常，用第二个 Lua 脚本原子回滚库存与下单标记，避免出现"库存扣了但订单没生成"。

### 4. 分布式锁演进

保留了两代实现，完整呈现选型思考过程：

**第一代：自研 SETNX 锁**
```
SET lock:order:{userId} {threadId} NX EX 10
```
- 问题 1：业务超时 → 锁提前释放 → 其他线程进入 → 超卖。解法：加**看门狗**自动续期
- 问题 2：线程 A 阻塞超过 TTL，锁被 B 拿到，A 恢复后直接 `DEL` 会**误删 B 的锁**。解法：删除前用 Lua 原子校验 `threadId` 是否匹配

**第二代：Redisson**
直接用 `RLock`，可重入、看门狗续期、Lua 原子释放全部内置，工程上更稳。

> 结论：自研锁是理解原理的必要过程，生产环境直接上 Redisson。

### 5. 登录与会话

- 验证码登录，用户信息以 **Hash** 结构存 Redis，实现集群会话共享
- **双层拦截器**：`RefreshTokenInterceptor` 负责 token 校验与无感续期（对所有请求生效），`LoginInterceptor` 只做登录态判断（拦截需登录的路径），职责分离
- `ThreadLocal` 传递用户上下文，请求结束 `remove()` 防止线程池复用导致的内存泄漏

### 6. 深分页优化

`LIMIT 1000000, 10` 的问题不是"扫了 100 万行"，而是**回表 100 万次**。优化方案：

- 建立**联合覆盖索引**，让查询在索引上完成，不回表
- **游标分页**：用 `lastId` 替代 `offset`，`WHERE id > lastId ORDER BY id LIMIT 10`，把 O(n) 变成 O(log n)
- 接口层返回 `ScrollResult`（list + offset），前端滚动加载

---

## 快速开始

### 1. 启动依赖服务

```bash
docker run -d --name redis -p 6379:6379 redis:7 \
  redis-server --requirepass your-redis-password

docker run -d --name mysql -p 3306:3306 \
  -e MYSQL_ROOT_PASSWORD=your-mysql-password \
  -e MYSQL_DATABASE=hm_dianping mysql:8.0

docker run -d --name rabbitmq -p 5672:5672 -p 15672:15672 \
  -e RABBITMQ_DEFAULT_USER=admin \
  -e RABBITMQ_DEFAULT_PASS=your-mq-password \
  rabbitmq:3.13-management
```

> Redisson 配置了 6379 / 6380 / 6381 三个节点用于联锁演示。若只跑单节点，把 `application.yml` 里 `redisson.node2-port` / `node3-port` 改成 6379 即可。

### 2. 导入数据库

```bash
mysql -uroot -p hm_dianping < sql/hm_dianping.sql
```

### 3. 配置环境变量

所有敏感配置已外部化，通过环境变量注入（默认值仅供本地调试）：

```bash
export MYSQL_HOST=localhost
export MYSQL_PASSWORD=your-mysql-password
export REDIS_HOST=localhost
export REDIS_PASSWORD=your-redis-password
export RABBITMQ_HOST=localhost
export RABBITMQ_USER=admin
export RABBITMQ_PASSWORD=your-mq-password
```

### 4. 启动

```bash
mvn spring-boot:run
```

访问 `http://localhost:8081`，RabbitMQ 管理台在 `http://localhost:15672`。

---

## 目录结构

```
src/main/java/com/hmdp/
├── config/          # Redisson 多节点、RabbitMQ 队列与死信、MVC 拦截器注册
├── controller/      # 商户、优惠券、订单、博客、关注等接口
├── mq/              # 秒杀订单消费者 + 死信队列监听
├── service/impl/    # 核心业务：缓存三防、秒杀、分布式锁
├── utils/           # Redis 常量、分布式锁工具、ID 生成器、登录拦截器
└── resources/
    ├── application.yml
    └── seckill.lua  # 原子扣减脚本
```

---

## 踩过的坑

- **`NOGROUP No such key 'stream.orders'`**：早期用 Redis Stream 做队列，消费组未创建会直接崩。迁移到 RabbitMQ 后此问题消失，也印证了"Stream 需要手工维护消费组，而 MQ 的队列声明是自动的"。
- **Redis 副本同步失败**：Docker on Windows 下用 bind mount 挂载 RDB/AOF 会因文件锁失败，改用 named volume 解决。
- **配置 BOM 头**：PowerShell `Set-Content -Encoding UTF8` 会写入 BOM，导致 Redis 报 `Bad directive`，需去掉前三个字节。
- **`spring-boot-maven-plugin` 缺失**：只打 thin jar 无法 `java -jar`，需显式配置 repackage。

---

## License

MIT
