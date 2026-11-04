
# hm-dianping 项目链路回顾文档（技术栈 → 核心模块 → 请求流转 → 关键依赖 → 核心业务逻辑）

> 目标：把本仓库的“从请求进来到落库/落缓存”的主链路一次性串起来，并在每个环节标注**代码入口**与**关键设计点**，方便后续复盘学习。

---

## 1. 技术栈（Tech Stack）

### 1.1 运行时与构建
- 构建工具：Maven
  - 配置入口：`pom.xml`
- Java 版本：`21`
  - 配置入口：`pom.xml`（`maven.compiler.source/target`）
- Spring Boot：`3.1.12`
  - 配置入口：`pom.xml`（parent）

### 1.2 Web / MVC
- Spring Web（REST API）
  - 依赖入口：`pom.xml` → `spring-boot-starter-web`
  - 控制器入口：`src/main/java/com/hmdp/controller/*Controller.java`
- 拦截器链（鉴权 + Token 续期）
  - 注册入口：`src/main/java/com/hmdp/config/MvcConfig.java`
  - 实现入口：
    - `src/main/java/com/hmdp/utils/RefreshTokenInterceptor.java`
    - `src/main/java/com/hmdp/utils/LoginInterceptor.java`

### 1.3 数据层
- MySQL + JDBC Driver
  - 依赖入口：`pom.xml` → `mysql-connector-j`
  - 配置入口：`src/main/resources/application.yml` → `spring.datasource.*`
- ORM：MyBatis-Plus
  - 依赖入口：`pom.xml` → `mybatis-plus-boot-starter`
  - 全局插件配置入口：`src/main/java/com/hmdp/config/MybatisConfig.java`
  - Mapper 扫描入口：`src/main/java/com/hmdp/HmDianPingApplication.java`（`@MapperScan("com.hmdp.mapper")`）
  - XML Mapper：`src/main/resources/mapper/*.xml`（示例：`VoucherMapper.xml`）

### 1.4 Redis 与分布式能力
- Redis：Spring Data Redis + Jedis
  - 依赖入口：`pom.xml`（排除 `lettuce-core`，引入 `jedis` 与 `commons-pool2`）
  - 配置入口：`src/main/resources/application.yml` → `spring.data.redis.*`
  - 说明：本仓库依赖使用 Jedis，但 `application.yml` 中仍保留了 `lettuce.pool` 配置（`spring.data.redis.lettuce.pool`）。这不影响主链路理解，但属于“配置一致性”可复盘点。
- Redisson（分布式锁）
  - 依赖入口：`pom.xml` → `org.redisson:redisson`
  - 客户端配置入口：`src/main/java/com/hmdp/config/RedissonConfig.java`（提供 3 个 `RedissonClient` Bean）
- Lua 脚本（保证 Redis 侧原子性）
  - 秒杀脚本入口：`src/main/resources/seckill.lua`
  - 原子解锁脚本入口：`src/main/resources/unlock.lua`

### 1.5 辅助库
- Hutool：Bean/JSON/字符串工具
  - 依赖入口：`pom.xml` → `cn.hutool:hutool-all`
- Lombok：简化 POJO
  - 依赖入口：`pom.xml` → `lombok`

---

## 2. 核心模块（Core Modules）

下面按“项目主线学习顺序”列出模块，并给出每个模块最关键的**入口类/方法**。

### 2.1 用户模块：验证码登录 + Token 续期 + 用户上下文
- API 入口：`src/main/java/com/hmdp/controller/UserController.java`
  - `POST /user/code` → `sendCode()`
  - `POST /user/login` → `login()`
  - `GET /user/me` → `me()`
  - `POST /user/sign` → `sign()`（签到）
  - `GET /user/sign/count` → `signCount()`（连续签到）
- 核心服务：`src/main/java/com/hmdp/service/impl/UserServiceImpl.java`
  - `sendCode(phone, session)`：验证码写 Redis
  - `login(loginForm, session)`：校验验证码 → 创建/查询用户 → 生成 token → 用户信息写入 Redis Hash
  - `sign()` / `signCount()`：Bitmap 签到/统计
- 用户上下文（ThreadLocal）：`src/main/java/com/hmdp/utils/UserHolder.java`
- 关键常量：`src/main/java/com/hmdp/utils/RedisConstants.java`

### 2.2 店铺模块：缓存穿透/击穿/逻辑过期 + GEO 附近店铺
- API 入口：`src/main/java/com/hmdp/controller/ShopController.java`
  - `GET /shop/{id}` → `queryShopById()`
  - `PUT /shop` → `updateShop()`
  - `GET /shop/of/type` → `queryShopByType(typeId,current,x,y)`（含 GEO 查询分支）
- 核心服务：`src/main/java/com/hmdp/service/impl/ShopServiceImpl.java`
  - `queryById(id)`：主查询入口（优先逻辑过期，兜底穿透）
  - `queryWithLogicalExpire(id)`：逻辑过期（返回旧值 + 异步重建）
  - `saveShopRedis(id, expireSeconds)`：写逻辑过期结构
  - `queryWithPassThrough(id)`：缓存穿透（空值缓存）
  - `queryWithMutex(id)`：缓存击穿（互斥锁重建，递归重试）
  - `queryShopByType(typeId,current,x,y)`：Redis GEO radius 查询 + 回表 `ORDER BY FIELD` 保序
- 逻辑过期载体：`src/main/java/com/hmdp/utils/RedisData.java`

### 2.3 店铺类型：列表缓存
- API 入口：`src/main/java/com/hmdp/controller/ShopTypeController.java`
  - `GET /shop-type/list` → `queryTypeList()`
- 服务入口：`src/main/java/com/hmdp/service/impl/ShopTypeServiceImpl.java`
  - `queryTypeListWithCache()`：String 缓存（JSON 列表）

### 2.4 博客模块：热门/详情/点赞/点赞榜
- API 入口：`src/main/java/com/hmdp/controller/BlogController.java`
  - `POST /blog` → `saveBlog()`
  - `PUT /blog/like/{id}` → `likeBlog()`
  - `GET /blog/hot` → `queryHotBlog()`
  - `GET /blog/{id}` → `queryBlogById()`
  - `GET /blog/likes/{id}` → `queryBlogLikes()`（点赞 Top 用户）
- 服务入口：`src/main/java/com/hmdp/service/impl/BlogServiceImpl.java`
  - `queryHotBlog(current)`：按 liked 倒序分页
  - `queryBlogById(id)`：补充作者信息 + 是否点赞
  - `likeBlog(id)`：DB liked +/-1 + Redis ZSet 增删
  - `queryBlogLikes(id)`：ZSet Top5 → 回表并通过 `ORDER BY FIELD` 保序

### 2.5 关注模块：关注/取关/共同关注
- API 入口：`src/main/java/com/hmdp/controller/FollowController.java`
  - `PUT /follow/{id}/{isFollow}` → `follow()`
  - `GET /follow/or/not/{id}` → `isFollow()`
  - `GET /follow/common/{id}` → `followCommons()`
- 服务入口：`src/main/java/com/hmdp/service/impl/FollowServiceImpl.java`
  - `follow(followUserId, isFollow)`：DB + Redis Set 同步
  - `followCommons(id)`：Redis Set 交集 → 回表用户信息

### 2.6 Feed 流：收件箱模型（ZSet）+ 滚动分页
- 推送入口：`src/main/java/com/hmdp/service/impl/BlogServiceImpl.java`
  - `saveBlog(blog)`：保存 blog 后，查询粉丝（Follow 表）并对每个粉丝的 `feed:{fanId}` ZSet `ZADD(blogId, timestamp)`
- 拉取入口：`src/main/java/com/hmdp/controller/BlogController.java`
  - `GET /blog/of/follow?lastId=&offset=` → `queryBlogFollow()`
- 核心分页逻辑入口：`src/main/java/com/hmdp/service/impl/BlogServiceImpl.java`
  - `queryBlogFollow(max, offset)`：
    - `ZREVRANGEBYSCORE`（含 score 返回）
    - 处理“同分数（同时间戳）”导致的重复问题（通过 offset）
    - 回表强制按 Redis 顺序返回（`ORDER BY FIELD`）

### 2.7 优惠券 + 秒杀：Lua 原子校验 + Redis Stream 异步下单 + 分布式锁
- 优惠券 API：`src/main/java/com/hmdp/controller/VoucherController.java`
  - `POST /voucher` → `addVoucher()`
  - `POST /voucher/seckill` → `addSeckillVoucher()`（秒杀券入库 + 预热 Redis 库存）
  - `GET /voucher/list/{shopId}` → `queryVoucherOfShop()`
- 秒杀下单 API：`src/main/java/com/hmdp/controller/VoucherOrderController.java`
  - `POST /voucher-order/seckill/{id}` → `seckillVoucher()`
- 秒杀核心服务：`src/main/java/com/hmdp/service/impl/VoucherOrderServiceImpl.java`
  - `seckillVoucher(voucherId)`：执行 `seckill.lua`（库存校验/防重/扣库存/写 Stream）并返回 `orderId`
  - `@PostConstruct init()`：启动单线程消费者 `StreamOrderConsumer`
  - `StreamOrderConsumer.run()`：
    - `XREADGROUP` 读取 `stream.orders`
    - 解析为 `VoucherOrder` 并调用 `handleVoucherOrder()`
    - `XACK` 确认消息
  - `handleVoucherOrder(voucherOrder)`：Redisson 锁 `lock:order:{userId}` 防止并发重复落库
  - `createVoucherOrder(voucherOrder)`：事务方法（DB 防重 + 扣 MySQL 库存 + 保存订单）
- 秒杀 Lua 入口：`src/main/resources/seckill.lua`
- 秒杀券预热：`src/main/java/com/hmdp/service/impl/VoucherServiceImpl.java#addSeckillVoucher()`
  - 写 Redis 库存：`seckill:stock:{voucherId}`
  - 初始化用户集合：`seckill:order:{voucherId}`（注意：当前实现写入了一个空字符串成员，用于“建集合”，属于可优化点）

### 2.8 分布式锁：手写 Redis Lock vs Redisson
- 手写锁接口：`src/main/java/com/hmdp/utils/ILock.java`
- 手写锁实现：`src/main/java/com/hmdp/utils/SimpleRedisLock.java`
  - 加锁：`SETNX lock:{name} <uuid-threadId> EX <timeout>`
  - 解锁：执行 `unlock.lua` 原子校验归属并删除
- Redisson 封装：`src/main/java/com/hmdp/utils/RedissonLock.java`
  - 基于 Redisson 的 `RLock`

### 2.9 上传模块：文件落盘（本地目录）
- API 入口：`src/main/java/com/hmdp/controller/UploadController.java`
  - `POST /upload/blog`：保存图片文件到 `SystemConstants.IMAGE_UPLOAD_DIR`
  - `GET /upload/blog/delete`：删除文件
- 路径常量：`src/main/java/com/hmdp/utils/SystemConstants.java`

---

## 3. 请求流转（Request Flow）

> 这一节回答“一个 HTTP 请求进来之后，到底依次经过哪些组件？在哪里做了什么？”

### 3.1 全局入口
- 启动类：`src/main/java/com/hmdp/HmDianPingApplication.java`
  - `@SpringBootApplication`
  - `@MapperScan("com.hmdp.mapper")`
  - `@EnableAspectJAutoProxy(exposeProxy = true)`：允许通过 AOP 代理调用（常见于事务/切面需要）

### 3.2 拦截器链（非常关键：登录态从哪里来？在哪里“续期”？）
- 注册与顺序：`src/main/java/com/hmdp/config/MvcConfig.java`
  - `RefreshTokenInterceptor`：`order(0)`，全路径 `/**`
  - `LoginInterceptor`：`order(1)`，对部分路径放行（`excludePathPatterns(...)`）

#### 3.2.1 RefreshTokenInterceptor（Token 解析 + ThreadLocal 注入 + TTL 续期）
- 入口：`src/main/java/com/hmdp/utils/RefreshTokenInterceptor.java#preHandle()`
- 核心步骤：
  1. 从请求头读取 token：`authorization`
  2. Redis key：`login:token:{token}`（Hash）
  3. `opsForHash().entries(key)` 取用户字段 Map
  4. `BeanUtil.fillBeanWithMap(...)` 转 `UserDTO`
  5. `UserHolder.saveUser(userDTO)` 写入 ThreadLocal
  6. `expire(key, LOGIN_USER_TTL, MINUTES)` 续期
- 收尾：`afterCompletion()` 调用 `UserHolder.removeUser()` 防止线程复用导致的内存泄漏/串号

#### 3.2.2 LoginInterceptor（仅做“是否已登录”的硬拦截）
- 入口：`src/main/java/com/hmdp/utils/LoginInterceptor.java#preHandle()`
- 判定方式：只看 `UserHolder.getUser()` 是否为空；为空直接 `response.setStatus(401)`

> 复盘点：`MvcConfig` 当前对 `/blog/**` 做了放行，这意味着 **发布博客/点赞等也可能不需要登录**（取决于具体路径是否覆盖）。这在“学习环境”里可能是刻意放开，也可能是后续需要收敛的安全点。

### 3.3 Controller → Service → Mapper → DB/Redis 的典型模板
1. Controller 接口方法接收参数（PathVariable/RequestBody/RequestParam）
2. 调用 Service（一般是 `*ServiceImpl`）处理业务
3. Service 内部：
   - 调用 `StringRedisTemplate` 读写 Redis
   - 或使用 MyBatis-Plus：`query()/update()/save()` 与数据库交互
4. 返回统一包装：`Result.ok(...)` / `Result.fail(...)`（入口：`src/main/java/com/hmdp/dto/Result.java`）
5. 发生 `RuntimeException`：由全局异常处理返回失败
   - 入口：`src/main/java/com/hmdp/config/WebExceptionAdvice.java`

---

## 4. 关键依赖（Key Dependencies / Key Data Structures）

### 4.1 Redis key 设计（建议复盘时画一张 Key 地图）
- 登录验证码：`login:code:{phone}`（String）
- 登录态：`login:token:{token}`（Hash：UserDTO 字段）
- 店铺缓存：`cache:shop:{id}`（String，可能是 Shop JSON，也可能是 RedisData JSON）
- 店铺类型缓存：`cache:shop-type:`（String：列表 JSON）
- 店铺互斥锁：`lock:shop:{id}`（String）
- 下单互斥锁：`lock:order:{userId}`（String/Redisson Lock）
- 秒杀库存：`seckill:stock:{voucherId}`（String 数字）
- 秒杀下单用户集合：`seckill:order:{voucherId}`（Set）
- 点赞集合：`blog:liked:{blogId}`（ZSet：member=userId，score=时间戳）
- Feed 收件箱：`feed:{userId}`（ZSet：member=blogId，score=时间戳）
- GEO 店铺：`shop:geo:{typeId}`（GEO）
- 签到：`sign:{userId}:{yyyyMM}`（Bitmap）

> 常量入口：`src/main/java/com/hmdp/utils/RedisConstants.java`

### 4.2 Lua 脚本承担的“原子性边界”
- `seckill.lua`：把“校验库存 + 防重复 + 扣库存 + 记录下单 + 投递 Stream”收敛在一次 Redis 原子执行里
- `unlock.lua`：把“比对锁归属 + 删除锁”收敛为原子操作（避免误删别人的锁）

### 4.3 Redis Stream 的消费模型
- 生产：Lua `XADD stream.orders * userId ... voucherId ... id ...`
- 消费：`VoucherOrderServiceImpl.StreamOrderConsumer` 使用 `XREADGROUP`
  - Stream Key：`stream.orders`
  - Group：`g1`，Consumer：`c1`

> 复盘点：当前代码未看到 `XGROUP CREATE`（创建消费组）的初始化逻辑；通常需要在部署/启动时确保 group 已存在，否则第一次消费会报错。

### 4.4 MyBatis-Plus 关键点
- 分页插件与防全表更新/删除插件
  - 入口：`src/main/java/com/hmdp/config/MybatisConfig.java`
- 常见写法：
  - `query().eq(...).page(new Page<>(...))`
  - `update().setSql("stock = stock - 1").gt("stock",0).update()`（乐观式扣减）

---

## 5. 核心业务逻辑（按模块拆解“关键设计点”）

### 5.1 登录链路（验证码登录 + Redis Token）
**入口**
- `UserController#sendCode()` → `UserServiceImpl#sendCode()`
- `UserController#login()` → `UserServiceImpl#login()`

**链路步骤**
1. `sendCode()`：
   - 校验手机号：`RegexUtils.isPhoneInvalid(phone)`
   - 生成验证码：`RandomUtil.randomNumbers(6)`
   - Redis 写入：`SET login:code:{phone} code EX 2min`
2. `login()`：
   - 从 Redis 取验证码比对
   - MySQL 查询用户，不存在则创建
   - 生成 token：`UUID.randomUUID(true)`
   - 将 `UserDTO` 写入 Redis Hash：`login:token:{token}`
   - 设置 TTL：`LOGIN_USER_TTL`（当前常量为 36000 分钟）
3. 后续请求：
   - `RefreshTokenInterceptor` 把用户信息放入 `UserHolder` 并续期
   - 需要登录的接口再由 `LoginInterceptor` 硬拦截

**关键设计点**
- “续期逻辑”放在拦截器，而不是每个业务接口里散落。
- ThreadLocal 的生命周期由 `afterCompletion()` 统一清理。

### 5.2 店铺详情缓存：逻辑过期 + 异步重建
**入口**
- `ShopController#queryShopById()` → `ShopServiceImpl#queryById()`

**核心路径**
- `queryById()`：优先走 `queryWithLogicalExpire()`
  - 命中缓存且未逻辑过期：直接返回
  - 命中缓存但逻辑过期：
    - 立即返回旧值（保证 RT）
    - 尝试加锁 `lock:shop:{id}`
    - 加锁成功则线程池异步 `saveShopRedis()` 重建缓存
  - 未命中/格式不匹配：兜底走 `queryWithPassThrough()` 并补缓存

**关键设计点**
- “逻辑过期”的本质：缓存不物理过期，让请求始终拿到一个值；重建在后台发生。
- 异步重建线程池入口：`ShopServiceImpl` 静态线程池 `CACHE_REBUILD_EXECUTOR`。

### 5.3 点赞：DB 计数 + Redis ZSet 去重与排序
**入口**
- `BlogController#likeBlog()` → `BlogServiceImpl#likeBlog()`

**核心逻辑**
- 使用 `blog:liked:{blogId}` ZSet：
  - member = `userId`
  - score = `System.currentTimeMillis()`（用于按时间排序/展示）
- 点赞时：
  - DB liked +1
  - ZSet `ZADD`
- 取消点赞时：
  - DB liked -1
  - ZSet `ZREM`

**关键设计点**
- DB 是“最终计数”，Redis 是“去重集合 + 排序列表”。

### 5.4 Feed 流：推模式（写扩散）+ 滚动分页
**入口**
- 推：`BlogServiceImpl#saveBlog()`
- 拉：`BlogServiceImpl#queryBlogFollow(max, offset)`

**核心逻辑**
- 推送：写扩散到粉丝的收件箱 `feed:{fanId}`
- 拉取：使用 `reverseRangeByScoreWithScores` 滚动分页
  - `max`：上一页最小时间戳
  - `offset`：同一时间戳的偏移

**关键设计点**
- 使用 `ORDER BY FIELD(id, ...)` 保证回表顺序与 Redis 一致，否则会乱序。

### 5.5 秒杀：Redis 原子校验 + Stream 异步落库 + Redisson 锁
**入口**
- `VoucherOrderController#seckillVoucher()` → `VoucherOrderServiceImpl#seckillVoucher()`

**核心逻辑分层**
1. **Redis 原子层**（入口：`seckill.lua`）
   - 校验库存是否存在/充足
   - 校验用户是否重复下单（Set）
   - 扣减库存（`INCRBY -1`）
   - 写入下单用户集合（Set）
   - `XADD` 投递到 `stream.orders`
2. **异步消费层**（入口：`VoucherOrderServiceImpl#init()` 启动消费线程）
   - `XREADGROUP` 拉取消息
   - 组内 ACK：`opsForStream().acknowledge(...)`
3. **落库层**（入口：`VoucherOrderServiceImpl#createVoucherOrder(VoucherOrder)`）
   - DB 侧再防重一次（`where user_id + voucher_id` count）
   - DB 扣库存（`stock = stock - 1` 且 `stock > 0`）
   - 保存订单

**关键设计点**
- Lua 保证“高并发下的资格判定与扣减”原子；DB 事务保证“最终一致落库”。
- `handleVoucherOrder()` 使用 Redisson 锁 `lock:order:{userId}`，避免同用户并发落库导致的重复订单。
- `VoucherOrderServiceImpl` 中注入 `IVoucherOrderService self` 并通过 `self.createVoucherOrder(...)` 调用，是为了确保事务注解生效（通过 Spring 代理调用）。

---

## 6. 复盘学习建议（Checklist）

### 6.1 建议梳理顺序（由浅入深）
1. 登录与拦截器（ThreadLocal、Token Hash、TTL 续期）
2. 店铺缓存（穿透/击穿/逻辑过期）
3. 点赞（ZSet）与关注（Set）
4. Feed 流（写扩散 + 滚动分页参数）
5. 秒杀（Lua + Stream + Redisson 锁 + DB 事务）

### 6.2 验证清单（你可以照这个做一次手工联调/压测）
- 登录：
  - `/user/code` 生成 `login:code:{phone}`
  - `/user/login` 返回 token，并写入 `login:token:{token}` Hash + TTL
  - 携带 `authorization` 调用任意接口可观察 TTL 续期
- 店铺：
  - `/shop/{id}` 命中缓存、逻辑过期重建是否按预期
  - `PUT /shop` 后缓存是否删除
- 点赞：
  - `PUT /blog/like/{id}`：DB liked 与 ZSet member 是否同步
  - `GET /blog/likes/{id}`：顺序是否稳定（`ORDER BY FIELD`）
- Feed：
  - 发 blog 后粉丝的 `feed:{fanId}` 是否写入
  - 滚动分页 `lastId/offset` 是否避免重复
- 秒杀：
  - `seckill:stock:{voucherId}`、`seckill:order:{voucherId}`、`stream.orders` 是否生成
  - Stream consumer 是否持续消费并 ACK
  - DB `tb_voucher_order` 是否落库且不重复

---

## 7. 附：最常用的“定位入口索引”

- 启动与扫描：`HmDianPingApplication`
- Web 拦截器链：`MvcConfig` → `RefreshTokenInterceptor` → `LoginInterceptor`
- Redis Key 常量：`RedisConstants`
- 店铺缓存：`ShopServiceImpl`
- Feed/点赞：`BlogServiceImpl`
- 关注：`FollowServiceImpl`
- 秒杀：`VoucherOrderServiceImpl` + `seckill.lua`
- 解锁脚本：`SimpleRedisLock` + `unlock.lua`
- 表结构参考：`src/main/resources/db/hmdp.sql`

