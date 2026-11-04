package com.hmdp;
import com.hmdp.entity.Shop;
import com.hmdp.service.impl.ShopServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.mysql.cj.x.protobuf.MysqlxDatatypes;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.quartz.SimpleTriggerFactoryBean;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.SHOP_GEO_KEY;

@SpringBootTest
class HmDianPingApplicationTests {
    // 1. 注入RedisIdWorker
    @Resource
    private RedisIdWorker redisIdWorker;
    // 2. 创建固定大小线程池（500个线程，和老师一致）
    private ExecutorService es = Executors.newFixedThreadPool(500);
    // 3. 高并发测试ID生成器（300个线程，每个线程生成100个ID，共30000个ID）
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Test
    void testIdWorker() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(300);
        Runnable task = () -> {
            for (int i = 0; i < 100; i++) {
                long id = redisIdWorker.nextId("order");
                System.out.println("id = " + id);
            }
            latch.countDown();
        };
        long begin = System.currentTimeMillis();
        for (int i = 0; i < 300; i++) {
            es.submit(task);

        }
        latch.await();
        long end = System.currentTimeMillis();
        System.out.println("time = " + (end - begin));

    }

    // 你原来的测试方法（保留不动！）
    @Resource
    private ShopServiceImpl shopService;

    @Test
    void testSaveShop() {
        shopService.saveShopRedis(1L, 10L);
    }




    @Test
    void loadShopData() {
        // 1. 查询所有店铺信息
        List<Shop> list = shopService.list();
        // 2. 按typeId分组，相同类型的店铺分到一组
        Map<Long, List<Shop>> map = list.stream()
                .collect(Collectors.groupingBy(Shop::getTypeId));
        // 3. 遍历分组，批量写入Redis
        for (Map.Entry<Long, List<Shop>> entry : map.entrySet()) {
            // 3.1 获取类型id，拼接Redis key
            Long typeId = entry.getKey();
            String key = SHOP_GEO_KEY + typeId;
            // 3.2 获取当前类型的所有店铺
            List<Shop> shopList = entry.getValue();
            // 3.3 构建批量写入的参数
            List<RedisGeoCommands.GeoLocation<String>> locations = new ArrayList<>(shopList.size());
            for (Shop shop : shopList) {
                // 重点：Point只存经纬度，店铺ID是add的第三个参数！
                locations.add(new RedisGeoCommands.GeoLocation<>(
                        shop.getId().toString(), // 成员：店铺ID
                        new Point(shop.getX(), shop.getY()) // 坐标：经度x在前，纬度y在后
                ));
            }
            // 3.4 批量写入Redis（一次请求搞定，效率高）
            stringRedisTemplate.opsForGeo().add(key, locations);
        }


    }
    @Test
    void testHyperLogLog() {
        // 1. 定义数组，缓存用户ID（用于批量添加优化）
        String[] values = new String[1000];
        int j = 0;

        // 2. 循环模拟100万次用户访问（100万个不同用户）
        for (int i = 0; i < 1000000; i++) {
            // 计算数组下标：i对1000取模，得到0~999的循环索引
            j = i % 1000;
            // 把当前用户ID存入数组
            values[j] = "user_" + i;

            // 当数组被填满（下标到999）时，批量发送到Redis
            if (j == 999) {
                // 调用PFADD命令，批量添加1000个用户ID到HyperLogLog
                stringRedisTemplate.opsForHyperLogLog().add("hl2", values);
            }
        }

        // 3. 调用PFCOUNT命令，统计不重复用户数量（UV数）
        Long count = stringRedisTemplate.opsForHyperLogLog().size("hl2");
        System.out.println("count = " + count);
    }

    @Test
    public void loadShopGeoData(){
        // 1. 查询所有店铺
        List<Shop> list = shopService.list();
        // 2. 分组，按typeId分组存入Redis GEO
        Map<Long, List<Shop>> map = list.stream().collect(Collectors.groupingBy(Shop::getTypeId));
        // 3. 分批写入Redis
        for (Map.Entry<Long, List<Shop>> entry : map.entrySet()) {
            Long typeId = entry.getKey();
            List<Shop> shops = entry.getValue();
            String key = SHOP_GEO_KEY + typeId;
            // 批量添加到Redis GEO
            for (Shop shop : shops) {
                stringRedisTemplate.opsForGeo().add(
                        key,
                        new Point(shop.getX(), shop.getY()),
                        shop.getId().toString()
                );
            }
        }
        System.out.println("GEO数据重建完成！");
    }

}

